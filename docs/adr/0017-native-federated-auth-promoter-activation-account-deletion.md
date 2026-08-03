# 0017 — Authentification fédérée native, activation Promoteur et suppression de compte

- **Statut** : accepté
- **Date** : 2026-07-27
- **Décideurs** : Équipe
- **Remplace** : —
- **Complété par** : ADR-0025 pour la preuve de ré-authentification de suppression

## Contexte et problème

AUTH-005 ajoute trois parcours sensibles qui partagent la session Supabase sans avoir les mêmes
frontières de confiance :

- Google doit être acquis par les composants natifs Android et iOS, et Sign in with Apple par
  `AuthenticationServices` sur iOS uniquement ;
- une invitation Promoteur peut ouvrir l'application avec une preuve de session temporaire, mais
  ne doit ni remplacer silencieusement un compte déjà connecté ni accorder un rôle critique ;
- la suppression irréversible doit ré-authentifier l'identité, effacer les données applicatives,
  révoquer toutes les sessions et supprimer l'utilisateur Auth malgré les reprises réseau.

Les ID tokens, nonces, mots de passe, tokens d'invitation et sessions sont des secrets éphémères.
Ils ne doivent pas devenir des paramètres de navigation persistés, des propriétés Analytics ou des
logs. La suppression doit en revanche laisser une preuve opérationnelle minimale permettant de
réconcilier une exécution interrompue après l'effacement des données.

Le PRD demande un accès direct au tableau de bord après activation Promoteur. Le tableau de bord
réel appartient à B2B-003 et n'existe pas encore ; AUTH-005 ne doit donc pas présenter un écran
factice comme s'il était livré.

## Options envisagées

- **OAuth ouvert dans une WebView et suppression pilotée par le client** : peu de code plateforme,
  mais acquisition moins native, secrets plus difficiles à contenir et privilège serveur impossible
  à protéger correctement.
- **Session d'invitation importée sans tenir compte de la session courante et suppression immédiate
  non idempotente** : parcours court, mais risque de substitution de compte et d'état partiellement
  supprimé impossible à reprendre.
- **Acquisition native, validation Supabase, activation RPC à privilèges bornés et suppression
  orchestrée par Edge Function** : davantage de contrats et de tests, mais frontières de confiance
  explicites et reprise déterministe.

## Décision

Nous retenons l'acquisition native des identités, Supabase Auth comme autorité de session, des RPC
atomiques pour l'activation Promoteur et une Edge Function idempotente pour la suppression, parce
que cette combinaison maintient les secrets hors de l'UI et les décisions de privilège côté serveur.

Android acquiert Google avec Credential Manager. iOS acquiert Google avec Google Sign-In et Apple
avec `AuthenticationServices`. Chaque tentative crée un nonce aléatoire à usage unique ; le
fournisseur reçoit la forme attendue et le data layer partagé reçoit uniquement l'ID token, le nonce
brut correspondant et, lorsqu'ils existent, des indices de prénom/nom destinés à l'onboarding.
Kwabor ne demande ni access token Google ni refresh token fournisseur.

Les boutons natifs servent à la connexion, à l'inscription, à l'activation Promoteur et à la
ré-authentification de la Danger Zone. Une identité nouvelle ou dont l'onboarding est incomplet
reprend le parcours de révision du profil ; une identité complète reprend la destination protégée.
Une annulation fournisseur est silencieuse et ne devient pas une erreur technique visible.

Une invitation Promoteur contient un token aléatoire dont seul le SHA-256 est conservé en base. La
prévisualisation et l'activation exigent une adresse Auth confirmée identique à celle de l'invitation.
Le callback n'importe aucune preuve de session lorsqu'une session existe déjà. Sans session, seul
un code d'autorisation PKCE peut établir une session temporaire ; les fragments et les jetons
implicites sont rejetés. Cette session temporaire est supprimée si la prévisualisation échoue ou si
l'utilisateur annule. Un lien invalide ne déconnecte donc jamais le compte courant.

Sur iOS, un callback reçu au lancement ou pendant la restauration attend la fin du bootstrap Auth
dans une file en mémoire dédupliquée. Le marqueur de nettoyage provisoire est créé avant l'appel au
shared uniquement si l'état restauré ne contient aucune session et si le callback porte un code
PKCE. Un callback sans code ou traité avec une session préexistante n'arme jamais ce nettoyage. Si
la création du marqueur et son rollback échouent tous deux, le client tente immédiatement la
suppression locale de toute session en mode fermé avant d'exposer une erreur. Un échec de
restauration de session conserve toutes les destinations protégées derrière un écran de retry ;
il n'est jamais assimilé à une session anonyme valide. Toute erreur de callback armé déclenche
également la déconnexion puis la suppression du marqueur avant d'être affichée. Si une suppression
de compte démarre, elle invalide la file et le callback en vol avant l'appel serveur, puis conserve
la route propriétaire jusqu'au résultat terminal afin que le callback de suppression ne soit pas
perdu par la disparition de sa vue SwiftUI.

L'activation verrouille l'invitation et vérifie à nouveau son statut, son expiration, l'organisation
vérifiée, la classe Commerciale/Événementielle et l'identité. Le compte doit avoir terminé son
onboarding et ne porter aucun tombstone de suppression. Le serveur exige aussi que l'entrée la plus
récente du claim `amr` du JWT signé, déterminée par son `timestamp`, soit `password` ou `oauth`,
date d'au plus cinq minutes et ne dépasse pas l'horloge serveur de plus de trente secondes. La
migration lit ce claim depuis le contexte PostgREST signé `request.jwt.claim(s)`, source logique
également exposée par `auth.jwt()`, afin de rester compatible avec l'image locale Supabase utilisée
par la CI. Une AMR absente, malformée, ancienne, future au-delà de cette tolérance, ou terminée par
`otp`, `magiclink`, `token_refresh` ou toute autre méthode échoue en mode fermé. Ces claims ne sont
jamais reconstruits depuis des métadonnées utilisateur.

L'activation peut attribuer uniquement le rôle produit `promoteur` vérifié et le rôle d'organisation
`editeur` actif, ou relever un `moderateur` actif vers `editeur`. Elle n'attribue jamais Propriétaire,
Gestionnaire ou Admin, ne transfère jamais `listings.owner_id` et rattache seulement la fiche à
l'organisation. Jusqu'à B2B-003, le client affiche un succès réel avec le nom du commerce, conserve
une destination Promoteur typée et propose le retour à l'accueil ; l'accès direct au tableau de bord
reste une exigence explicitement ouverte.

La suppression de compte exige la saisie exacte de `SUPPRIMER`, puis une ré-authentification par mot
de passe ou par un nouvel ID token Google/Apple avec nonce. L'Edge Function vérifie le bearer courant
et exige que l'identité ré-authentifiée porte le même identifiant utilisateur. Le client réutilise
une clé UUID d'idempotence lors d'un retry.

La préparation serveur refuse la suppression tant que l'utilisateur possède une organisation ou
des objets Storage à traiter. Elle sérialise les demandes par utilisateur, réutilise la demande
`prepared` existante même si un client redémarré fournit une nouvelle clé, supprime les données
personnelles applicatives et neutralise les attributions résiduelles des fiches. Les politiques
Storage restrictives utilisent le même verrou transactionnel et refusent tout nouvel objet possédé
dès qu'un tombstone existe. Après révocation de toutes les sessions, l'Edge Function revalide encore
les blocages, puis seulement supprime l'utilisateur Auth et marque la demande `completed`.

`account_deletion_requests` est un tombstone sans clé étrangère vers `auth.users`, inaccessible à
`anon` et `authenticated`. Il conserve uniquement l'identifiant pseudonyme de l'ancien compte, la
clé d'idempotence, le statut et les horodatages ; aucun email, nom, contenu, mot de passe, token ou
nonce n'y est copié. Tout tombstone bloque l'activation Promoteur et la finalisation d'onboarding,
y compris le chemin idempotent d'un profil auparavant complet. Ces deux RPC prennent le verrou
partagé de l'utilisateur avant leur contrôle ; la préparation de suppression prend le verrou
exclusif correspondant. Une opération commencée avant la suppression termine donc avant son
nettoyage, tandis qu'une opération arrivée après observe le tombstone et échoue.

La réconciliation opérationnelle des tombstones `prepared` est obligatoire :

1. si l'utilisateur Auth existe encore, l'utilisateur reprend le parcours avec une session et une
   ré-authentification fraîches ; l'Edge Function récupère la clé effective de la première demande ;
2. si l'utilisateur Auth n'existe plus, la fonction privilégiée quotidienne refait le nettoyage
   idempotent puis marque le tombstone `completed` avec sa clé effective ;
3. aucune suppression d'un utilisateur Auth encore présent n'est relancée manuellement sans une
   nouvelle preuve d'autorisation ;
4. les tombstones `completed` sont techniquement purgés après 30 jours ; cette durée doit être
   validée avec le responsable légal avant la release candidate.

L'Edge Function conserve `verify_jwt=true` dans `supabase/config.toml`. La plateforme Supabase
rejette donc un bearer absent ou invalide avant l'exécution. Dans la fonction, le wrapper
`withSupabase({ auth: 'user' })` exige à nouveau un contexte utilisateur, puis `getUser()` effectue
une lecture live et l'identité ré-authentifiée doit correspondre à cet utilisateur. Ces contrôles
complémentaires échouent tous en mode fermé et doivent être testés après chaque déploiement.

## Conséquences

**Positives**

- Les interfaces d'identité restent natives et les clients ne détiennent aucun secret OAuth serveur.
- Un lien Promoteur malformé, expiré ou destiné à un autre email ne remplace pas une session active.
- Les rôles critiques et la possession d'une fiche ne peuvent pas être obtenus par activation.
- La suppression est ré-authentifiée, idempotente et réconciliable après interruption.
- Les secrets éphémères sont exclus des états persistés, des logs et de l'observabilité.

**Négatives / compromis assumés**

- Le provisionnement Google, Apple et Supabase doit être répété et prouvé pour staging et production.
- Une interruption après la suppression Auth mais avant le marquage final nécessite une
  réconciliation serveur du tombstone.
- Les suppressions avec propriété d'organisation ou médias Storage sont bloquées jusqu'à transfert
  ou nettoyage explicite.
- L'accès direct au tableau de bord Promoteur reste incomplet tant que B2B-003 n'a pas consommé la
  destination typée.

**À revoir si**

- Supabase modifie les audiences acceptées, le contrôle des ID tokens ou le contrat d'authentification
  des Edge Functions.
- Kwabor ajoute un fournisseur social, un autre client applicatif ou une suppression différée.
- La politique juridique de conservation impose une autre durée ou un autre contenu de tombstone.
