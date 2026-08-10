# 0035 — Persister et drainer les interactions Like/Favori par compte

- **Statut** : accepté
- **Date** : 2026-08-09
- **Décideurs** : Produit Kwabor, Architecture mobile, Data et Sécurité
- **Complète** : ADR-0011, ADR-0027 et ADR-0033
- **Remplace** : —

## Contexte et problème

Explore permet à un viewer authentifié d'aimer une fiche ou de l'ajouter à ses favoris. Profil →
Favoris permet aussi de retirer un favori. En réseau indisponible, le client applique aujourd'hui le
dernier état souhaité dans l'interface et le conserve uniquement dans `ExploreUiState`. Le message
utilisateur promet pourtant une synchronisation ultérieure.

Cette file volatile disparaît à l'éviction du processus, au redémarrage ou au changement de scope.
Elle ne possède aucun drain et ne peut donc pas honorer la promesse produit. Lancer d'abord le RPC,
puis écrire la file seulement après l'échec, laisse aussi une fenêtre où un timeout ambigu peut avoir
muté le serveur sans laisser de trace locale fiable.

La solution doit fonctionner de la même manière sur Android et iOS, rester cloisonnée par compte,
préserver les garanties inter-surfaces de l'ADR-0033 et ne pas introduire de moniteur réseau natif ou
de protocole multi-appareils spéculatif.

## Décision

### Écriture avant transport et dernier état souhaité

Toute mutation Like/Favori authentifiée est d'abord écrite dans une outbox Room. L'optimisme visuel
n'est appliqué qu'après le succès de cette écriture. Une configuration sans persistance peut encore
exécuter les lectures en ligne, mais ne doit jamais annoncer qu'une action hors ligne est durable :
elle retourne une erreur locale neutre si l'outbox est indisponible.

Le repli Room mémoire de l'ADR-0027 conserve les caches régénérables, mais publie une capacité
`MemoryOnly`. Le repository d'interactions refuse alors submit, hydratation, drain et purge avant de
consulter la base. Une base en mémoire n'est jamais assimilée à une outbox durable.

L'outbox contient au plus une ligne par `(account_id, listing_id, kind)` et coalesce les
actions rapides vers le dernier booléen souhaité. Une répétition du même état conserve
`operation_id`; un changement d'état remplace l'opération et obtient un nouvel identifiant local.
Cet identifiant est généré par SQLite, reste stable pendant tous les retries et n'est ni un numéro de
version serveur ni un identifiant analytique.

Après chaque transport, la suppression, le backoff ou la suspension de la ligne est conditionné par
un CAS sur `operation_id`. Une réponse ancienne ne peut donc ni effacer une intention plus récente,
ni publier sa confirmation dans l'interface. Les mutations sont drainées séquentiellement dans un
processus ; Favori conserve en plus la séquence locale de confirmation définie par l'ADR-0033.

### Schéma Room v4 et bornes

Room passe à la version 4 avec une table `interaction_outbox_operations` contenant :

- un `operation_id` entier auto-généré ;
- les UUID canoniques `account_id` et `listing_id` ;
- `kind` (`like` ou `favorite`) et `desired_selected` ;
- l'instant d'enqueue, le nombre de tentatives et la prochaine échéance ;
- une raison de suspension ou d'échec, sans message technique ni payload serveur.

Une contrainte unique protège la clé logique compte/fiche/type et un index sert le drain ordonné par
compte et échéance. La migration `3 → 4` ajoute uniquement cette table. Les schémas 1 à 4 restent
versionnés et les chemins `1/2/3 → 4` sont testés sans destruction des caches existants.

L'outbox est bornée à 1 000 lignes par compte et chaque lecture/drain traite au plus 100 lignes. Une
mise à jour d'une clé existante reste autorisée lorsque la borne est atteinte ; une nouvelle clé est
refusée honnêtement comme stockage indisponible. Les lignes dont la structure locale est corrompue
sont évincées par identifiant d'opération ; un code terminal inconnu est neutralisé comme rejet
non hydratable. Les erreurs SQLite ou I/O sont propagées.

### Drain, backoff et erreurs

Un coordinateur singleton appartient au `KwaborCompositionRoot`. Il observe le
`ViewerSessionScopeTracker`, possède un scope `SupervisorJob + dispatcher IO` fermé avec Koin et
publie des événements account-scoped consommés par Explore et Favoris.

Le drain se réveille lors :

- de l'enqueue ;
- de la restauration d'un compte authentifié ;
- du démarrage ou retour au premier plan ;
- de `ScreenAppeared` et d'un retry explicite ;
- de la prochaine échéance, avec un sommeil plafonné à cinq minutes.

Chaque réveil, retry manuel et hydratation porte le `ViewerSessionScope` exact capturé à son
origine. Une demande devenue ancienne ne peut ni annuler le réveil du nouveau compte, ni charger
ses lignes. L'hydratation normalise et déduplique au plus 1 000 identifiants, puis interroge Room
par lots de 50 avec un fence de scope avant et après chaque lecture.

Aucun moniteur de connectivité plateforme n'est ajouté. En premier plan, le délai automatique
maximal après un retour réseau silencieux est donc de cinq minutes ; un retour d'écran ou un retry
utilisateur l'accélère.

Les erreurs sont classées ainsi :

- `NetworkUnavailable` conserve la ligne et planifie un backoff exponentiel déterministe avec
  jitter, plafonné à cinq minutes ;
- `AuthenticationRequired` suspend le drain jusqu'à une session authentifiée ou un retry ;
- `Unexpected` suspend jusqu'au retry manuel afin de ne pas boucler sur un contrat cassé ;
- `Validation`, `NotFound` et `PermissionDenied` suppriment directement l'opération par CAS sur son
  identifiant et son compteur de tentative, retirent son optimisme visuel, publient un message
  neutre et déclenchent une réconciliation autoritative. Les reliquats terminaux reconnus d'une
  ancienne version ou d'un crash sont collectés transactionnellement au début du prochain enqueue,
  avant le contrôle de capacité ;
- un CAS perdu produit `Superseded` sans effet visible, puis la nouvelle intention est drainée.

Une section transport déjà commencée est non annulable jusqu'à son issue bornée par le client HTTP.
Avant de la démarrer, le compte actif est revalidé. Après son issue, aucun événement n'est publié si
le compte n'est plus courant.

### Session, confidentialité et suppression de compte

L'outbox est liée au compte, pas à l'epoch d'un écran. Une déconnexion masque immédiatement toutes
les intentions et arrête leur drain, mais les conserve sur l'appareil pour permettre une reprise du
même compte. Un autre compte ne peut ni les lire, ni les drainer, ni recevoir leurs événements.

La comparaison du scope local ne constitue pas, à elle seule, la barrière de sécurité : les SDK
natifs peuvent installer le jeton du compte B avant que Compose ou SwiftUI publie le nouveau scope.
Chaque transport durable envoie donc aussi l'`account_id` propriétaire de l'opération. Le setter
serveur v2 compare cet identifiant à `auth.uid()` avant tout verrou ou toute mutation. Un mismatch
retourne une erreur d'authentification, suspend l'opération de A et ne touche jamais les données de B.

Une reconnexion au même compte avec un nouvel epoch réhydrate le dernier état souhaité non terminal.
Avant toute étape asynchrone de suppression, y compris l'acquisition d'un jeton social, le client
capture l'identifiant du compte courant puis le bloque atomiquement dans le coordinateur. Le blocage
attend toute écriture ou tout drain déjà engagé, interdit ensuite submit, drain, hydratation et
publication d'événements pour ce compte, puis purge ses lignes d'outbox. Seule la tentative qui a
obtenu `Acquired` possède ce blocage ; une tentative ultérieure reçoit `AlreadyBlocked` et ne peut ni
continuer ni libérer le fence de la première. Si la purge locale échoue, la suppression distante
n'est pas lancée, le blocage est levé et l'utilisateur reçoit une erreur neutre.

L'acquisition incrémente en plus une génération monotone propre au compte. Une hydratation qui a lu
Room avant ce changement ne peut plus exposer son résultat après une reprise du même scope. Après la
purge, le coordinateur conserve aussi une invalidation de livraison jusqu'à acquittement des deux
surfaces : tout événement déjà tamponné dont la séquence précède ce watermark est rejeté, même si la
suppression distante échoue ensuite et réactive le même compte/epoch. La réconciliation relit alors
l'outbox vide et l'autorité serveur avant de retirer cette dette.

Avant l'appel destructif, un marqueur device-bound est persisté synchroniquement et vérifié. La
frontière distante est franchie par un callback non suspendu immédiatement avant le RPC. Une
annulation ou une erreur encore pré-transport nettoie ce marqueur et libère le blocage. À partir de
la frontière, un timeout, une perte de réponse, une erreur de décodage ou une annulation produit un
résultat inconnu : la session locale est neutralisée et le blocage reste actif comme après un succès
serveur confirmé. Un rejet serveur explicite libère le blocage. Si le nettoyage sécurisé du marqueur
échoue après ce rejet, les nouvelles mutations Auth restent suspendues jusqu'au retry local, mais le
fence d'interactions est tout de même libéré puisque l'absence de suppression distante est connue.

Le marqueur partagé de nettoyage de session est repris avant toute restauration et avant toute
nouvelle mutation Auth. Android et iOS réessaient ce nettoyage sans rejouer la suppression distante.
Android conserve en plus, dans des préférences app-private exclues du backup et du transfert, une
dette fournisseur sans PII armée par `commit` et relecture avant d'entrer dans le presenter
destructif. Au bootstrap, la session partagée est d'abord résolue sans être exposée : si elle reste
valide après un rejet explicite, seul le marqueur fournisseur est désarmé ; sans session, la dette
n'est effacée qu'après succès de `CredentialManager.clearCredentialState`. Cette décision précède le
routage de la session et toute nouvelle mutation Auth. Le foreground et le retry reprennent la même
procédure, tandis qu'un callback promoteur reste en attente dans le ViewModel. iOS conserve de même
un marqueur Keychain sans PII pour purger les hints et sessions des fournisseurs ; il n'est effacé
qu'après suppression vérifiée, lorsque les données protégées sont accessibles.

Le même identifiant capturé est inclus dans `AccountDeletionRequest`. La data source le compare à
la session principale Supabase avant de créer la session de réauthentification, puis exige que la
réauthentification rende encore ce même identifiant. Une installation anticipée du jeton B par le
SDK ne peut donc jamais transformer une suppression initiée sous A en suppression de B.

Les UUID et métadonnées techniques de retry restent exclus des logs et analytics. Ils suivent les
protections device-bound de l'ADR-0027 et ne contiennent aucun token, texte libre ou secret.

### Contrats serveur idempotents

`set_listing_favorite_v1` est déjà un setter d'état idempotent et reste l'autorité Favori.

Les RPC historiques `like_listing`/`unlike_listing` ne suffisent pas à l'outbox : si une fiche est
dépubliée entre le `DELETE` de `unlike_listing` et sa relecture, l'exception finale annule la
transaction et ressuscite l'intention au prochain affichage. Un RPC versionné
`set_listing_like_v1(uuid, boolean)` est donc ajouté :

- `true` crée uniquement sur une fiche publiée ; un retry d'un Like déjà présent reste confirmable
  après dépublication ;
- `false` supprime la relation du compte sans relecture publique obligatoire ;
- le résultat confirme l'état cible et rend `likes_count` nullable si la fiche n'est plus publique ;
- la fonction reste `SECURITY INVOKER`, exige onboarding complet, sérialise les mutations du compte,
  révoque `PUBLIC`/`anon` et n'accorde l'exécution qu'à `authenticated`.

Les transports durables appellent `set_listing_like_v2` et `set_listing_favorite_v2`. Ces wrappers
account-fenced exigent `p_expected_account_id`, le comparent au viewer JWT puis délèguent aux setters
v1 idempotents. Les fonctions v1 et les RPC historiques restent disponibles pour les clients déjà
publiés, mais ne sont jamais utilisées comme fallback silencieux par l'outbox.

Les setters d'état rendent les retries sûrs sans journaliser `operation_id` côté serveur. Entre deux
appareils, aucune version globale n'est introduite : la dernière écriture effectivement arrivée au
serveur gagne. Chaque appareil se réconcilie ensuite depuis l'autorité serveur.

### Présentation et convergence

`queuedInteractions` reste un miroir d'interface et n'est plus l'autorité. Au changement de scope et
après recréation du processus, Explore réhydrate uniquement les fiches visibles du compte courant.
Favoris applique un retrait optimiste après l'écriture Room, mais n'émet jamais un
`FavoriteChanged` confirmé avant la réussite serveur.

Les événements du coordinateur distinguent `Queued`, `Confirmed`, `Retrying`, `Rejected` et
`Superseded`. Like ne modifie que `liked/likes_count`; Favori ne modifie que `favorited`. Les deux
surfaces réutilisent les séquences et révisions de l'ADR-0033 afin qu'une réponse de feed ou une
confirmation retardée ne recouvre jamais l'autre type d'interaction.

Le flux d'événements accélère la convergence mais n'est pas une seconde base durable. Explore et
Favoris réhydratent l'outbox et relisent l'autorité serveur à l'apparition ou au rechargement ; une
surface créée après une confirmation déjà consommée ne dépend donc pas d'un replay mémoire du flux.

Le buffer mémoire reste borné et ne produit aucune attente de backpressure. La publication qui suit
un submit/drain est séquencée avant la libération de leur lease existante, sans lecture Room/réseau
supplémentaire ; une purge ne peut donc jamais placer son watermark avant cet événement. Pour la
livraison/réduction côté surface, seul le commit local bref du reducer est enregistré comme opération
active, là encore sans I/O sous lease. Si le buffer refuse un événement terminal, le coordinateur
publie un état de réconciliation confluent, lié au scope, avec
le plus grand `operation_id` terminal connu par fiche/type et le watermark de séquence de livraison
perdue. Il ne promet pas une livraison exactly-once : les surfaces enregistrent ces watermarks avant
de traiter un événement retardé, réhydratent les identifiants visibles par fenêtres de 1 000 au plus,
puis relisent l'autorité serveur quand une opération a disparu de l'outbox. Une lecture locale en échec
ne vaut jamais acquittement : le signal reste dirty et est repris au foreground ou au retour d'écran.
Explore et Favoris acquittent séparément la même révision seulement après le succès de toutes leurs
fenêtres ; seuls les événements `Queued` ou `Retrying` couverts par le watermark de livraison sont
revalidés, afin qu'un événement réellement postérieur soit réduit normalement.

Favoris sépare sa file d'intents UI de sa file d'événements durables, bornée à 64 éléments. Si cette
dernière refuse une livraison, un accumulateur lié au scope ne conserve que l'événement refusé le
plus récent et ne publie qu'une seule demande de réconciliation après le drainage des événements
acceptés. Cette dette reste publiée tant que l'acquittement Favoris exact (scope et watermark) n'a
pas réussi ; un échec d'hydratation ne déclenche ni boucle chaude ni acquittement, et un événement
refusé plus récent est republié après l'acquittement courant. L'accumulateur reste ainsi en O(1) et
n'effectue aucune lecture locale ou réseau sous son mutex.

## Conséquences

**Positives**

- une intention acceptée visuellement survit au redémarrage et à la perte réseau ;
- les toggles rapides convergent sans double incrément ni suppression d'une intention récente ;
- Android et iOS partagent le schéma, le backoff et les règles de session ;
- aucun SDK de connectivité ou protocole de version multi-appareils n'est ajouté.

**Négatives / compromis assumés**

- sans signal réseau natif, une reprise silencieuse peut attendre jusqu'à cinq minutes en premier
  plan ;
- une déconnexion conserve localement des UUID privés device-bound jusqu'à reconnexion ou purge du
  compte ;
- une tentative de suppression de compte purge d'abord l'outbox locale : si la suppression distante
  échoue ensuite, les intentions encore en attente ne sont pas restaurées ;
- la dernière écriture serveur gagne entre appareils, sans garantie d'ordre global ;
- Room v4 et le nouveau RPC Like deviennent des gates de compatibilité release.

## Validation exigée

- migrations Room `1/2/3 → 4`, schémas exportés et reprise après réouverture de la base ;
- coalescence même état/changement d'état, borne, ordre, CAS et corruption logique ;
- timeout ambigu, retry idempotent, backoff, suspension auth/manuelle et rejet terminal ;
- intention remplacée pendant un RPC non annulable ;
- A → B, A → invité → A, reconnexion du même compte et purge après suppression ;
- submit/drain en vol face à une suppression, blocage avant jeton social, reprise uniquement après
  échec explicite et rejet des événements tardifs ;
- résultat direct `Queued` capturé avant purge puis commité après reprise : la génération interdit le
  commit Explore/Favoris même si le signal mémoire a déjà été acquitté ;
- hydratation capturée avant purge puis rendue après reprise du même scope, et événement `Queued`
  tamponné avant purge puis livré après reprise : aucun des deux ne recrée un overlay ;
- annulation exactement après le `DELETE` Room mais avant livraison de `Acquired` : le worker termine
  l'invalidation de livraison puis libère tardivement le fence une seule fois, sans transport distant ;
- hydratation de plus de 50 fiches, bornée à 1 000, et transition de scope entre deux lots ;
- overflow réel du flux mémoire, watermark terminal avant événement retardé, réconciliation
  Explore/Favoris, échec de première hydratation puis reprise, et 1 001 fiches fenêtrées ;
- convergence Explore/Favoris dans les deux ordres, indépendance Like/Favori et process death ;
- pgTAP du setter Like répété, retrait après dépublication, ajout masqué refusé, retry vrai masqué,
  ACL/RLS et concurrence avec suppression de compte ;
- tests Android/iOS du foreground et de la réhydratation ;
- `spotlessCheck`, `detekt`, tests ciblés puis `check` et CI Supabase GitHub.

## Hors de cette décision

- l'historique de recherche de l'ADR-0031, les brouillons et les uploads média ;
- une synchronisation temps réel ou un ordre global entre appareils ;
- un nouveau client autre qu'Android/iOS ;
- l'activation production avant qualification backup/restore, staging et appareils physiques.

## Références

- [ADR-0011 — Persistance locale structurée avec Room KMP](0011-room-kmp-local-persistence.md)
- [ADR-0027 — Persistance locale device-bound](0027-device-bound-local-persistence.md)
- [ADR-0032 — Read model et mutation Favoris](0032-owner-favorites-read-model-and-compatible-mutation.md)
- [ADR-0033 — Cloisonnement et cohérence Favoris](0033-session-fenced-favorites-client-and-bidirectional-consistency.md)
- [PRD — Interactions et offline](../../PRD.md)
- [DESIGN — États offline et Favoris](../../DESIGN.md)
