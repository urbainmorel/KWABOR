# 0031 — Versionner la synchronisation hors ligne de l’historique Search

- **Statut** : proposé
- **Date** : 2026-08-04
- **Décideurs** : proposition Architecture, Data et Mobile ; validations Produit, Sécurité,
  Juridique/DPO et Opérations requises avant acceptation
- **Gate** : aucune outbox ni synchronisation V2 ne doit être activée avant validation des choix
  ouverts listés dans cet ADR
- **Complète** : ADR-0027, ADR-0028, ADR-0029, ADR-0030
- **Remplace** : —

## Contexte et problème

L’ADR-0029 fixe une autorité Supabase pour un compte, un miroir Room lié à l’appareil, un scope
invité exclusivement local, des plafonds de 200 entrées actives côté serveur et de 50 entrées par
scope et appareil, ainsi que des effacements unitaire et global. L’ADR-0030 livre cette autorité sous
la forme d’un snapshot V1 complet : une absence dans ce snapshot doit remplacer le miroir, jamais
être interprétée comme un delta.

Ce contrat V1 ne suffit pas pour une outbox. Après une perte réseau, un même appel peut avoir été
appliqué côté serveur sans que le client ait reçu sa réponse. Une mutation ancienne peut aussi
arriver après un effacement effectué sur un autre appareil. Sans identité idempotente, ordre serveur
et marqueur d’effacement, un retry peut dupliquer une resoumission ou faire réapparaître du texte que
l’utilisateur a supprimé.

La synchronisation doit en outre rester sûre lors d’une déconnexion, d’un passage du compte A au
compte B, d’un import invité ou d’une suppression de compte. Elle doit fonctionner sur un réseau
intermittent sans utiliser l’horloge du client comme autorité et sans conserver le texte effacé dans
un journal technique.

## Options envisagées

- **Continuer avec des snapshots V1** : la lecture est simple et sûre par remplacement complet,
  mais une outbox ne peut pas distinguer un retry d’une nouvelle action ni résoudre une mutation
  arrivée après un effacement.
- **Résoudre les conflits par timestamp client ou dernier arrivé** : réduit le schéma, mais les
  horloges mobiles ne sont pas fiables et un ancien appareil peut ressusciter un historique effacé.
- **Conserver un journal complet contenant les requêtes** : facilite le rejeu, mais multiplie les
  copies du texte brut et contredit son effacement immédiat.
- **Versionner par compte avec génération, révisions, tombstones sans texte et mutations
  idempotentes** : ajoute des métadonnées et des migrations, mais fournit un ordre autoritatif et une
  frontière vérifiable entre données actives, effacements et retries.

## Proposition soumise à validation

Si cet ADR est accepté, HISTORY-001B utilisera un protocole V2 par compte fondé sur une génération,
un ordre de révisions serveur, des tombstones unitaires, un watermark global et une outbox
idempotente. Le scope invité restera hors de ce protocole.

### État autoritatif par compte

Supabase conservera, sous l’identité issue exclusivement de `auth.uid()` :

- un état de synchronisation avec `generation`, `current_revision`, `clear_revision` et
  `min_supported_revision` ;
- les entrées actives avec leur identifiant stable, leur génération et la révision de leur dernière
  modification ;
- les tombstones unitaires avec seulement le compte, l’identifiant de l’entrée, la génération, la
  révision, la date serveur et une cause typée ;
- un registre bornable de mutations avec la clé `(user_id, mutation_id)`, le type d’opération, les
  identifiants techniques d’entrée ou de lot et le résultat déjà attribué.

Une tombstone, un watermark, un curseur ou un enregistrement d’idempotence ne contiendra **ni texte
de recherche, ni forme canonique, ni hash ou empreinte dérivée de ce texte**. Le texte brut existera
uniquement dans l’entrée active nécessaire à l’affichage et au rejeu du récent. Il restera interdit
dans les logs, Analytics, rapports de crash et messages d’erreur.

Les tables seront protégées par RLS et sans privilège direct pour les rôles mobiles. Les RPC V2
authentifiées, à `search_path` vide et avec contrôle explicite de `auth.uid()`, resteront l’unique
frontière distante. Elles n’accepteront jamais un `user_id` choisi par le client. Toutes les
mutations d’un compte prendront le même verrou transactionnel par utilisateur que la suppression de
compte.

### Génération, révisions et watermark

- La génération initiale vaut `1`. Elle change uniquement lors d’un effacement global accepté par
  le serveur.
- `current_revision` est un entier 64 bits strictement croissant **par compte**, jamais une séquence
  globale entre utilisateurs.
- Chaque changement visible reçoit une révision unique dans la transaction qui le publie : création
  ou resoumission, suppression unitaire, effacement global et éviction due au plafond serveur.
- Une resoumission et l’éventuelle éviction de la 201e entrée peuvent consommer deux révisions
  consécutives, mais deviennent visibles dans une seule transaction.
- `clear_revision` est le watermark persistant du dernier effacement global. Cet effacement supprime
  physiquement tous les textes actifs, avance la génération et publie un changement `CLEAR` sans
  lister ni recopier les textes supprimés.
- `min_supported_revision` définit la plus ancienne base encore rejouable. Une lecture ou mutation
  plus ancienne reçoit `snapshot_required` ou une erreur de conflit typée ; elle n’est jamais
  automatiquement rebasée.

Les timestamps serveur gouvernent l’ordre d’affichage authentifié. L’horloge du téléphone ne peut
ni fixer une révision ni imposer `last_submitted_at` au serveur.

### Mutations idempotentes

Chaque tentative distante est créée une seule fois dans Room avec un `mutation_id` UUID stable,
persisté avant le premier appel réseau. Tous ses retries réutilisent cet identifiant. Les mutations
transportent aussi la génération attendue, la révision de base et l’identifiant stable de l’entrée
ou du lot concerné. Une intention privacy peut créer une tentative successeur, mais ne réutilise ni
ne modifie jamais l’UUID d’une tentative antérieure.

Sous le verrou du compte, le serveur :

1. refuse une session absente, différente ou déjà engagée dans une suppression de compte ;
2. retourne le résultat original si `(user_id, mutation_id)` existe déjà, sans nouvelle révision ;
3. refuse la réutilisation de l’identifiant pour un autre type ou une autre cible technique ;
4. valide la génération, la révision minimale et les invariants de l’opération ;
5. applique le changement et son enregistrement d’idempotence dans la même transaction.

Le registre conserve les identifiants et révisions nécessaires pour restituer le premier résultat,
mais aucun payload de requête et aucune empreinte de ce payload. Le client a donc l’invariant ferme
de ne jamais réutiliser un UUID pour une autre intention.

Les opérations V2 seront séparées et typées plutôt que réunies dans un payload générique :
enregistrer ou resoumettre, supprimer une entrée, vider le scope authentifié et importer un lot
invité confirmé. Les noms et signatures SQL exacts seront figés par la migration, mais chaque
réponse exposera au minimum la version du protocole, le résultat, la génération et la révision
serveur obtenues.

### Collision canonique et identité autoritative

Un `RECORD` créé hors ligne porte une identité locale `L`, alors qu’une entrée canonique identique
peut déjà exister côté serveur sous l’identité `S`, notamment hors des 50 lignes du miroir. Le
serveur conserve `S`. Sa réponse et le registre d’idempotence retournent donc toujours le
`mutation_id`, l’identité cliente `L` et l’identité autoritative `S`, y compris lors d’un retry après
perte de l’acquittement.

Avant de retirer la commande ou son épingle, Room applique ce résultat dans une transaction unique :
fusion de `L` avec une éventuelle ligne `S`, remappage vers `S` des commandes dépendantes, barrières,
masques et références techniques, puis suppression de l’alias local. Un événement serveur portant
`S` reçu avant l’acquittement ne peut ni créer un doublon visible, ni écraser le payload de `L` ; il
force la même réconciliation après récupération du résultat idempotent.

Une limite demeure irréductible avec les invariants proposés : si l’utilisateur supprime un
`RECORD` authentifié avant que `L` ait été relié à `S`, et si le premier appel n’a jamais atteint le
serveur, le client ne peut cibler une éventuelle entrée `S` inconnue après avoir effacé le texte sans
conserver texte, hash ou identifiant dérivé. Il ne doit donc pas présenter une simple annulation de
`L` comme un effacement distant garanti. Le choix entre conserver une donnée minimale protégée
jusqu’à résolution, imposer une reconnexion avant finalisation, modifier le contrat d’identité ou
accepter une suppression uniquement locale avec risque de réapparition reste un gate
Produit/Sécurité/Juridique-DPO. HISTORY-001B ne sera pas implémenté avant cet arbitrage.

### Resoumission et effacements

- Une resoumission canonique d’une entrée encore active conserve son identifiant et `created_at`,
  actualise `last_submitted_at` avec l’horloge logique serveur et reçoit une nouvelle révision.
- Une suppression unitaire retire immédiatement le texte actif et publie une tombstone portant
  uniquement l’identifiant de cette entrée. Un retry de la même suppression est un succès
  idempotent.
- Une mutation de resoumission visant une identité tombstonée après sa révision de base est refusée.
  Elle ne peut pas recréer silencieusement cette identité.
- Lors du pull, une tombstone serveur `DELETE` ou `CAPACITY` plus récente que la révision de base
  d’un `RECORD` local annule cette commande et supprime son entrée dans la même transaction. Le
  client n’applique jamais la tombstone à l’entrée seule, ce qui orphelinerait l’outbox.
- Après réconciliation, une nouvelle soumission explicite du même texte est une nouvelle intention
  et reçoit un nouvel identifiant. Elle n’efface ni ne réutilise la tombstone antérieure.
- Un effacement global est prioritaire sur les enregistrements et imports de sa génération
  antérieure. Ces mutations obsolètes sont abandonnées après le pull ; elles ne sont pas réécrites
  dans la nouvelle génération.
- Un `CLEAR` local crée une barrière de génération provisoire identifiée par son
  `privacy_intent_id`. Les nouvelles soumissions explicites et les imports invités confirmés après
  cette action restent
  visibles ou réservés, épinglés et ordonnés localement, mais leurs commandes dépendent de la
  barrière : elles ne devinent jamais `generation + 1` et ne partent pas sous l’ancienne génération.
  Après acquittement du `CLEAR` puis pull/bootstrap, Room leur attribue atomiquement la génération et
  la révision de base réellement observées avant envoi. Ce rattachement précède leur premier appel et
  conserve leur UUID stable ; ce n’est pas le rebase d’une mutation déjà tentée.
- Un second `CLEAR` local supprime immédiatement les `RECORD` et imports créés derrière la première
  barrière. Si le premier n’a avec certitude jamais été envoyé, l’action est coalescée dans sa
  intention et sa tentative `never_sent` existantes. S’il est en vol, ambigu ou déjà appliqué, il
  n’est jamais annulé ni réécrit : un nouveau `CLEAR`, un nouveau `privacy_intent_id` et une nouvelle
  barrière dépendent de son acquittement et de l’observation de sa génération. Le coordinateur
  persiste l’état `attempted` avant le premier appel réseau ; après un crash, seul un état durable
  `never_sent` autorise la coalescence. Ces états survivent au redémarrage.
- Une suppression unitaire ou globale locale retire le texte de Room avant le réseau. L’échec réseau
  conserve uniquement la commande d’effacement sans recopier le texte dans l’outbox.

Room conserve avec cette commande un masque de confidentialité durable et sans texte : identité de
l’entrée pour `DELETE`, génération locale concernée pour `CLEAR`, compte propriétaire et
`privacy_intent_id`. La création du masque, la suppression du texte et l’ajout de la première
tentative sont atomiques. Tant que la commande n’est pas réconciliée, un pull ne rematérialise ni une identité
masquée, ni un `UPSERT` de la génération couverte par un `CLEAR`. Le masque survit aux retries et au
redémarrage ; une erreur terminale reste fail-closed et ne rend jamais le texte à nouveau visible.
La résolution impose un bootstrap final avant de retirer le masque.

### Intentions de confidentialité au-delà du plancher

`DELETE` et `CLEAR` sont des intentions logiques de confidentialité distinctes de leurs tentatives
réseau. Elles possèdent un `privacy_intent_id` local stable ; chaque tentative possède son propre
`mutation_id`. Une génération obsolète ou une révision passée sous `min_supported_revision` ne
termine jamais l’intention et ne retire ni son masque, ni sa barrière.

Chaque tentative référence l’intention par clé étrangère. Remplacer une tentative terminale par son
successeur ne recrée, ne réattribue et ne supprime jamais le masque ou la barrière ; seule la
résolution terminale de l’intention les retire dans une transaction avec ses tentatives. Les
cascades et identifiants stables survivent au redémarrage.

Après le bootstrap imposé, le client demande un état autoritatif ciblé sous le verrou du compte ;
l’absence d’une ligne dans le snapshot borné à 50 n’est jamais considérée comme une preuve. Si le
serveur prouve que l’effet est déjà acquis, l’intention peut être acquittée. Sinon Room crée
atomiquement une tentative successeur avec un nouvel UUID, la génération et la révision de base
observées : `DELETE` vise l’identité autoritative connue, `CLEAR` vise l’état courant du compte.
L’ancienne tentative n’est ni réécrite ni réutilisée. Le masque ou la barrière ne disparaît qu’après
acquittement idempotent du successeur et bootstrap final.

Un successeur `CLEAR` peut supprimer des entrées ajoutées sur un autre appareil après l’intention
initiale si le serveur ne peut plus prouver l’ancien résultat après purge des métadonnées. Ce choix
privacy-first, ainsi que le contrat d’état ciblé nécessaire, reste un gate Produit/Sécurité et doit
être explicite pour l’utilisateur. La collision dont l’identité autoritative reste inconnue demeure
soumise au gate séparé décrit plus haut.

Le serveur ordonne les mutations concurrentes à leur application sous verrou. Sans conserver un
texte ou une empreinte dans une tombstone, il ne peut pas prouver qu’une entrée entièrement nouvelle
créée hors ligne sur un appareil correspond au texte d’une identité inconnue supprimée ailleurs.
La règle proposée est donc : une tombstone gagne pour la même identité connue ; la génération gagne
pour tout effacement global ; une nouvelle identité n’est créée qu’à partir d’une soumission
explicite conforme au protocole. Le niveau de garantie attendu pour ce cas limite reste un gate
Produit/Sécurité avant acceptation.

### Bootstrap et lecture différentielle

Le protocole exposera deux lectures atomiques :

- un bootstrap V2 retourne les 50 entrées actives les plus récentes, la génération,
  `current_revision`, `clear_revision` et `min_supported_revision` ;
- une synchronisation V2 reçoit la génération connue et `after_revision`, puis retourne des
  changements ordonnés par révision avec pagination keyset bornée.

Une page différentielle contient des événements typés :

- `UPSERT` avec l’identifiant, le texte actif, `created_at`, `last_submitted_at`, la génération et la
  révision ;
- `DELETE` avec l’identifiant, la génération, la révision et la cause, sans texte ni empreinte ;
- `CLEAR` avec la nouvelle génération et la révision du watermark, sans liste de textes.

La réponse fournit aussi la tête serveur observée, le prochain curseur et l’indication de page
suivante. Une génération ou un curseur trop ancien, une révision sous le plancher ou un état
incohérent impose un bootstrap avec remplacement atomique. Le premier passage de V1 vers V2 est
toujours un remplacement complet ; aucune absence du snapshot V1 n’est fusionnée comme un delta.

Ce remplacement ne supprime jamais aveuglément l’overlay local de l’outbox. Dans la même transaction,
un `RECORD` encore compatible avec la génération et le plancher reste épinglé au-dessus du snapshot.
Si sa génération ou sa révision de base n’est plus supportée, la commande devient un conflit
terminal : l’entrée purement locale et la commande sont retirées ensemble, ou la version serveur du
snapshot est restaurée pour une identité déjà autoritative. Les masques `DELETE`/`CLEAR` restent
fail-closed jusqu’à leur réconciliation. Aucun bootstrap ne peut supprimer une entrée tout en
laissant une commande qui en dépend.

Une réponse de mutation est un accusé de réception, pas la preuve que le client a observé toutes les
révisions précédentes. Elle ne fait donc jamais avancer directement le curseur appliqué de Room.

### Algorithme client `pull → apply → outbox → pull`

Pour le compte de la session courante, le coordinateur exécute :

1. **pull** : demander toutes les pages depuis la dernière révision appliquée ou effectuer un
   bootstrap si le serveur l’impose ;
2. **apply** : appliquer les événements, les masques de confidentialité et avancer le curseur dans
   une même transaction Room, puis borner le miroir à 50 entrées sans évincer une entrée encore
   référencée par une commande `RECORD` ;
3. **outbox** : envoyer en FIFO les mutations de ce compte, une par une, avec leur UUID stable ; un
   `CLEAR` acquitté suspend le drain jusqu’au pull/bootstrap suivant, et aucune commande dépendante
   de sa barrière ne peut le dépasser ;
4. **pull** : relire le serveur pour recevoir les évictions, changements concurrents et révisions
   que les accusés de réception ne permettent pas de sauter.

Une interruption ambiguë réessaie le même UUID. Seules les erreurs réseau ou serveur transitoires
utilisent un backoff exponentiel borné avec jitter. Une validation ou un payload incohérent, la
réutilisation fautive d’un UUID et un conflit de génération/plancher encore présent après la
réconciliation requise sont terminaux pour `RECORD` et `IMPORT`. Pour `DELETE` et `CLEAR`, ce conflit
déclenche la tentative successeur privacy-first définie plus haut et ne résout jamais l’intention.
Une session absente, expirée ou rattachée à un autre compte est au contraire une suspension :
aucun retry n’a lieu avant réauthentification du même compte, mais commande, entrée source, épingle
et masque sont conservés. La suppression du compte déclenche la purge dédiée. Aucun conflit n’est
masqué par un retry automatique.

L’ajout local de l’entrée et de sa commande outbox est atomique. L’outbox référence l’entrée Room au
lieu de dupliquer son texte. Toute entrée référencée par un `RECORD` en attente est épinglée jusqu’à
son résultat terminal. Un `UPSERT` serveur concurrent pour cette identité ne remplace pas les champs
qui constituent le payload local ; il marque `bootstrap_required`, tandis qu’une tombstone plus
récente applique la résolution atomique définie plus haut. Le bornage évince d’abord les entrées non
épinglées. Si cet épinglage empêche de conserver les 50 entrées serveur normalement attendues, Room
marque aussi `bootstrap_required` et effectue un remplacement complet après résolution de l’outbox,
afin de ne perdre durablement aucun élément du miroir. Une suppression invitée peut annuler une
entrée exclusivement locale. Pour un compte, une suppression ne remappe ou n’annule un `RECORD`
qu’après résolution sûre de son identité autoritative selon le gate décrit plus haut. Un effacement
global retire les textes et les commandes d’enregistrement/import antérieures de la génération
locale avant d’ajouter sa propre commande et son masque durable.

L’échec de persistance de l’historique ne bloque pas la recherche catalogue déjà validée, mais reste
une erreur data observable et expurgée. Seule la soumission Search explicite crée l’entrée ; saisie,
retry, refresh et pagination n’en créent aucune.

### Isolation invité, compte A et compte B

- Le scope invité est Room-only, ne crée aucune outbox serveur et utilise une horloge monotone
  logique exprimée en epoch millisecondes. Dans la transaction de soumission, Room calcule
  `max(nowEpochMilliseconds, lastPersisted + 1)` à partir d’une horloge injectée et du dernier instant
  persistant du scope. L’ordre résiste ainsi aux égalités, au recul de l’horloge et au redémarrage,
  tout en restant compatible avec `lastSubmittedAtEpochMilliseconds`. Sa désinstallation ou l’échec
  de la politique disque peut le faire disparaître, conformément à l’ADR-0027.
- Chaque miroir, état de synchronisation et commande outbox authentifiés porte le compte
  propriétaire. La couche data compare toujours ce compte à la session Supabase active ; elle ne
  fait pas confiance à un scope transmis par l’UI.
- Une déconnexion masque immédiatement le miroir A, annule ses opérations en vol et affiche le
  scope invité, sans supprimer A ni vider son outbox.
- Une connexion à B ne peut ni lire, ni appliquer, ni vider l’outbox de A. Une reconnexion à A reprend
  son protocole après un pull.
- Chaque changement de session incrémente une génération locale de coordinateur. Toute réponse
  tardive vérifie encore le compte et cette génération avant d’écrire dans Room.

Android Compose et iOS SwiftUI transmettent leurs événements de session au même coordinateur KMP ;
ils ne réimplémentent pas la sélection du scope ou la politique de synchronisation.

### Plafonds local et serveur

- Le serveur conserve au plus 200 requêtes canoniques actives par compte. La 201e insertion évince
  l’entrée active la plus ancienne dans la même transaction et publie une tombstone de cause
  `CAPACITY`, afin que les autres appareils puissent la retirer.
- Room conserve au plus 50 requêtes canoniques par scope et appareil. L’éviction du miroir d’un
  compte ne crée jamais de suppression serveur. L’éviction invitée est définitive.
- Une entrée de compte référencée par un `RECORD` en attente est prioritaire dans ce plafond local.
  Le miroir évince temporairement une entrée serveur non épinglée et exige un bootstrap de
  remplissage après résolution de la commande ; il n’orpheline jamais l’outbox.
- Les commandes de confidentialité `DELETE` et `CLEAR` ne sont jamais supprimées pour satisfaire le
  plafond des entrées. Leurs masques techniques sans texte ne comptent pas comme requêtes actives.
- Plus de 50 nouvelles requêtes distinctes encore non synchronisées ne peuvent pas être conservées
  sans violer le plafond local déjà accepté. La recommandation est d’abandonner la plus ancienne
  commande `RECORD` encore purement locale avec son entrée, sans effet serveur ; ce compromis exige
  toutefois une validation Produit explicite.

### Import invité explicite

L’import ne démarre qu’après une confirmation utilisateur rattachée au compte actuellement actif.
Room capture de façon atomique au plus 50 identifiants invités, leurs versions locales et leur ordre,
puis crée un lot idempotent appartenant à ce compte. Ces lignes et versions sont épinglées sans
dupliquer leur texte dans l’outbox. Juste avant chaque envoi, une transaction vérifie qu’elles sont
toutes présentes et inchangées, puis matérialise le payload uniquement en mémoire. Une source
absente ou modifiée invalide atomiquement le lot et exige une nouvelle confirmation ; le client
n’envoie jamais le texte d’une autre version. Aucun login ne déclenche une fusion implicite.

Le bornage ne peut pas évincer une source épinglée. Une resoumission locale annule d’abord le lot qui
référence sa version avant de modifier l’entrée. Une suppression ou un `CLEAR` invité gagne toujours :
le texte et le lot concerné disparaissent dans la même transaction. Les épingles survivent au
redémarrage. La course avec une requête réseau d’import déjà en vol doit être résolue sans conserver
une seconde copie persistante du texte et reste un gate explicite avant implémentation.

Une contrainte locale réserve chaque couple `(guest_entry_id, version)` à un seul lot en attente sur
l’appareil, tous comptes confondus. Une seconde confirmation chevauchante échoue en totalité avec une
erreur typée expurgée ; elle ne fait ni import partiel, ni réaffectation silencieuse à un autre compte.
En mode `move`, le nettoyage vérifie que la réservation appartient encore au lot acquitté avant de
supprimer la source et libère la réservation dans la même transaction. Aucun lot ne peut donc retirer
une ligne encore nécessaire à un autre.

Si le compte possède une barrière `CLEAR` en attente, un nouveau lot confirmé dépend de cette
barrière comme un `RECORD` post-effacement. Ses réservations invitées restent locales, mais aucun
payload ne part avant que la génération serveur obtenue soit observée et liée au lot.

La durée de vie d’une réservation est exactement celle de son lot en attente, garantie par clé
étrangère avec cascade et transaction. Toute résolution terminale — succès `copy`/`move`, mismatch,
annulation locale, conflit de génération ou purge du compte — retire le lot et ses réservations ;
`move` retire en plus uniquement les sources dont version et réservation concordent encore. Un
timeout ambigu conserve au contraire le lot et ses réservations pour réutiliser le même UUID.

Le serveur traite le lot de la plus ancienne entrée à la plus récente, utilise son horloge logique,
déduplique les textes canoniques et traite une collision active comme une resoumission conservant
l’identité serveur. Le lot porte une génération et une révision de base. Un effacement global
concurrent invalide le lot ; le client ne le rebase pas et une nouvelle confirmation est nécessaire.

Un échec ou un timeout laisse les entrées invitées intactes. Après accusé de réception idempotent,
le nettoyage local compare encore l’identifiant et la version capturés afin de ne pas effacer une
entrée invitée modifiée entre-temps. Le choix entre conserver les entrées invitées après succès
(`copy`) ou supprimer seulement les versions importées (`move`) reste ouvert.

### Suppression de compte

La suppression de compte prend le même verrou que le protocole, marque le compte en cours de
suppression puis refuse toute nouvelle lecture, mutation ou import. Sa purge serveur couvre les
entrées actives, la préférence désactivée, l’état de synchronisation, les tombstones et le registre
d’idempotence ; les clés étrangères vers Auth avec cascade restent une seconde garantie.

Le coordinateur local suspend et masque immédiatement le compte, annule ses travaux et purge de
façon idempotente son miroir, son état de synchronisation, son outbox et ses lots d’import. Aucune
donnée invitée ou d’un autre compte n’est supprimée par une purge correctement scopée.

Une réussite serveur suivie d’un échec de purge Room doit rester fail-closed : le scope supprimé ne
redevient jamais visible. Le mécanisme durable précis — marqueur de reprise protégé ou invalidation
de toute la base Room liée à l’appareil — reste à valider avant implémentation.

### Transition depuis les RPC V1

Un writer V1 ne fournit ni `mutation_id`, ni génération attendue, ni révision de base. Un appel V1
retardé peut donc recréer une entrée après un `CLEAR` V2. Conserver des writers V1 actifs empêcherait
de promettre la sémantique de non-résurrection du protocole.

La recommandation, sous réserve de confirmer qu’aucun client Store ne consomme ces RPC, est de :

1. backfiller les lignes V1 dans la génération initiale avec des révisions déterministes par compte ;
2. publier les RPC et métadonnées V2 dans la même migration contrôlée ;
3. révoquer l’exécution des mutations V1 avant d’activer toute outbox V2 ;
4. faire effectuer à chaque client V2 un bootstrap qui remplace entièrement tout miroir antérieur.

La lecture snapshot V1 peut rester temporairement disponible en lecture seule si un consommateur
réel le requiert. Si un client V1 a déjà été distribué, une politique de version minimale ou une
phase de compatibilité explicitement moins forte doit être approuvée ; l’outbox V2 ne peut pas être
présentée comme sûre pendant ce chevauchement.

### Purge des métadonnées techniques

Les tombstones et clés d’idempotence ne peuvent pas croître sans limite, mais les purger trop tôt
réautorise un ancien appareil à rejouer une mutation. Toute purge future devra :

- conserver le dernier watermark global ;
- avancer atomiquement `min_supported_revision` au-delà des événements ou résultats supprimés ;
- imposer un bootstrap aux clients situés sous ce plancher ;
- rejeter les `RECORD`/imports dont la révision de base est devenue trop ancienne, mais orienter les
  intentions `DELETE`/`CLEAR` vers la réconciliation et la tentative successeur privacy-first.

Aucune durée de conservation, aucun job de purge et aucun seuil calendaire ne sont décidés ici. Ils
requièrent des contraintes opérationnelles sur la durée hors ligne supportée et une validation
Juridique/DPO.

### Personnalisation et rétention volontairement absentes

Ce protocole ne produit aucun signal pour l’Assistant IA, le fil organique ou le classement
sponsorisé. Il n’ajoute aucun RPC d’activation de la préférence, qui reste désactivée. Il ne met pas
en œuvre la rétention glissante proposée de 180 jours et ne crée aucun job de purge du texte actif.

Jusqu’à une décision Juridique/DPO séparée, le texte actif disparaît uniquement par effacement
unitaire/global, éviction au plafond serveur ou suppression du compte. Toute activation de
personnalisation, toute dérivation de signal et toute rétention calendaire exigent une décision et
une livraison distinctes.

## Gates avant acceptation

Les points suivants ne sont pas tranchés par le statut proposé de cet ADR :

1. **Conflits d’effacement** : confirmer la priorité de `CLEAR`, le rejet sans rebase des anciennes
   générations, la portée d’une tombstone à l’identité connue et le nouvel identifiant après une
   soumission explicite post-suppression ; valider aussi la barrière provisoire des soumissions
   post-`CLEAR`, plusieurs commandes dépendantes, les `CLEAR` locaux répétés après envoi/timeout,
   les redémarrages et un `CLEAR` distant concurrent.
2. **Writers V1** : prouver qu’aucun client distribué n’en dépend, ou approuver une politique de
   version minimale et la période de compatibilité dégradée.
3. **Import invité** : choisir `move` ou `copy`, confirmer le traitement serveur de la plus ancienne
   à la plus récente et la resoumission en cas de collision canonique ; valider aussi l’épinglage et
   la vérification atomique des versions, les courses modification/éviction/effacement/redémarrage,
   les lots chevauchants lors d’un changement de compte, le cas des 50 sources toutes épinglées et le
   traitement privacy-safe d’un import déjà en vol.
4. **Durée des métadonnées** : fixer avec Juridique/DPO et les opérations la fenêtre de rejeu des
   tombstones et clés d’idempotence, puis la cadence de purge et le plancher supporté.
5. **Compétition miroir/outbox et plus de 50 soumissions hors ligne** : confirmer l’épinglage des
   `RECORD` en attente, l’éviction temporaire d’entrées serveur non épinglées et le bootstrap de
   remplissage sans écrasement du payload par un `UPSERT` concurrent ; confirmer aussi l’annulation
   atomique entrée+commande face aux tombstones serveur `DELETE`/`CAPACITY` ainsi qu’à une génération
   ou révision de base devenue non supportée lors d’un bootstrap, y compris après redémarrage. Enfin,
   approuver la perte de la plus ancienne commande `RECORD` purement locale, ou définir un autre
   comportement qui respecte toujours le plafond de 50.
6. **Masques de confidentialité locaux** : confirmer leur forme sans texte, leur persistance pendant
   retry/redémarrage et le comportement fail-closed qui interdit toute réapparition lors d’un pull,
   puis couvrir ces invariants par des tests de concurrence et de reprise.
7. **Horloge invitée** : valider l’horloge logique epoch persistante et tester égalités, concurrence,
   recul de l’horloge plateforme et reprise après redémarrage.
8. **Session et authentification** : confirmer que déconnexion, expiration et changement de compte
   suspendent sans résoudre ni désépingler les mutations, puis tester la reprise par le même compte
   et l’absence de fuite vers un autre.
9. **Échec de purge locale après suppression du compte** : choisir entre un marqueur protégé de
   reprise et l’invalidation fail-closed de la base Room complète.
10. **Abus et capacité** : borner la taille des lots, la pagination, la fréquence des mutations et la
   croissance maximale du registre avant toute exposition de production.
11. **Collision canonique avant effacement** : valider le remappage atomique `L → S`, la reprise après
   perte d’acquittement et les dépendances `DELETE`/masques ; choisir surtout la sémantique d’un
   effacement demandé avant que le serveur ait pu révéler `S`, sans prétendre garantir ce qui est
   impossible sans donnée corrélable persistante.
12. **Confidentialité après avancement du plancher** : valider l’état autoritatif ciblé, le lien
    intention/tentatives successeurs, leurs clés étrangères/cascades et les cas floor avancé,
    génération identique ou changée, perte d’acquittement et redémarrage ; confirmer le choix
    privacy-first d’un `CLEAR` successeur pouvant couvrir des entrées distantes plus récentes.

La rétention glissante du texte et l’activation de la personnalisation restent des gates distincts
déjà identifiés par l’ADR-0029 ; accepter le protocole de synchronisation ne les accepte pas.

## Conséquences

**Positives**

- Un timeout réseau se réconcilie sans dupliquer une mutation.
- Un effacement global invalide les anciennes générations sans conserver les textes supprimés.
- Les comptes A et B, l’invité et la suppression de compte ont des frontières explicites et
  testables.
- Le client peut progresser par petits deltas, tout en revenant à un snapshot sûr après une longue
  absence ou une purge de métadonnées.
- La transition V1 ne confond jamais une absence de snapshot avec un événement différentiel.

**Négatives / compromis assumés**

- Le schéma Supabase, Room et les tests de concurrence deviennent plus complexes.
- Les tombstones et enregistrements d’idempotence ont un coût de stockage jusqu’à leur purge
  approuvée.
- Le plafond local peut perdre une ancienne soumission jamais synchronisée si le choix recommandé
  est validé.
- Une garantie stricte par texte après suppression unitaire serait incompatible avec l’absence de
  texte ou d’empreinte dans les tombstones ; la sémantique doit donc rester fondée sur l’identité.
- Aucun writer V1 ne peut rester silencieusement actif avec les garanties complètes de V2.

**À revoir si**

- la durée hors ligne officiellement supportée change ;
- un besoin légal impose export, portabilité ou effacement des métadonnées selon une autre fenêtre ;
- plusieurs writers serveur non mobiles doivent participer au même ordre de révisions ;
- un futur usage exige une garantie de suppression par texte plutôt que par identité ;
- la personnalisation ou une rétention calendaire est validée dans une décision séparée.

## Références

- [PRD — Recherche, historique, Assistant IA et paramètres](../../PRD.md)
- [Design — Search, récents et confidentialité](../../DESIGN.md)
- [ADR-0027 — Persistance locale liée à l’appareil](0027-device-bound-local-persistence.md)
- [ADR-0028 — Recherche par mots-clés et repli hors ligne](0028-versioned-keyword-search-and-bounded-offline-fallback.md)
- [ADR-0029 — Autorité et confidentialité de l’historique](0029-search-history-authority-and-privacy.md)
- [ADR-0030 — Autorité Search en snapshot avant le protocole offline](0030-online-search-history-snapshot-boundary.md)
