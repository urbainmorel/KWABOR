# Environnements Kwabor

Ce document est le contrat de configuration mobile et le runbook de provisionnement. Il ne contient aucune valeur d'accès.

## Environnements autorisés

| Nom | Usage | Distribution | Données distantes |
|---|---|---|---|
| `development` | Développement local | APK/Xcode local non distribués | Supabase local ou projet de développement explicitement choisi |
| `staging` | QA, tests internes, bêta | Builds internes/TestFlight | Projets Supabase et Firebase Kwabor staging |
| `production` | Stores | AAB/App Store signés | Projets Supabase et Firebase Kwabor production |

Toute autre valeur est rejetée par le build Android et par la composition root partagée. Les clients Android et iOS conservent les identifiants `com.kwabor.android` et `com.kwabor.ios` dans les deux projets Firebase ; la séparation est portée par les projets fournisseurs et les configurations injectées.

## Contrat de configuration client

| Clé | Sensibilité | Android local | iOS local | GitHub Environment |
|---|---|---|---|---|
| `KWABOR_ENVIRONMENT` | Publique | `kwabor.environment` | `KWABOR_ENVIRONMENT` | Variable |
| `KWABOR_SUPABASE_URL` | Publique | `kwabor.supabase.url` | `KWABOR_SUPABASE_URL` | Variable |
| `KWABOR_SUPABASE_PUBLISHABLE_KEY` | Publique | `kwabor.supabase.publishableKey` | `KWABOR_SUPABASE_PUBLISHABLE_KEY` | Variable |
| `KWABOR_GOOGLE_WEB_CLIENT_ID` | Publique | client OAuth Web attendu par Supabase | — | Variable staging/production |
| `KWABOR_GOOGLE_IOS_CLIENT_ID` | Publique | — | client OAuth iOS qualifié par tier | Variable staging/production |
| `KWABOR_GOOGLE_SERVER_CLIENT_ID` | Publique | — | client OAuth Web attendu par Supabase | Variable staging/production |
| `KWABOR_GOOGLE_REVERSED_CLIENT_ID` | Publique | — | schéma callback dérivé du client iOS | Variable staging/production |
| `KWABOR_VERSION_CODE` | Publique | `kwabor.versionCode` | — | Entrée du workflow Android |
| `KWABOR_VERSION_NAME` | Publique | `kwabor.versionName` | — | Entrée du workflow Android |
| `KWABOR_ANDROID_KEYSTORE_BASE64` | Secret | — | — | Secret production |
| `KWABOR_ANDROID_KEYSTORE_PASSWORD` | Secret | `kwabor.android.signing.storePassword` | — | Secret production |
| `KWABOR_ANDROID_KEY_ALIAS` | Secret | `kwabor.android.signing.keyAlias` | — | Secret production |
| `KWABOR_ANDROID_KEY_PASSWORD` | Secret | `kwabor.android.signing.keyPassword` | — | Secret production |
| `KWABOR_IOS_DEVELOPMENT_TEAM` | Publique | — | `KWABOR_DEVELOPMENT_TEAM` | Variable staging/production |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_BASE64` | Secret | — | — | Secret staging/production |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | Secret | — | — | Secret staging/production |
| `KWABOR_IOS_PROVISIONING_PROFILE_BASE64` | Secret | — | — | Secret staging/production |
| `KWABOR_FIREBASE_ANDROID_CONFIG_BASE64` | Configuration d'intégrité | fichier généré par workflow | — | Secret |
| `KWABOR_FIREBASE_IOS_CONFIG_BASE64` | Configuration d'intégrité | — | fichier généré par workflow | Secret |
| `KWABOR_FIREBASE_PROJECT_ID` | Publique | vérification workflow | vérification workflow | Variable staging/production |
| `KWABOR_FIREBASE_IOS_CONFIG_PATH_*` | Chemin local non versionné | — | `Local.xcconfig` | — |

La clé Supabase publishable et les fichiers de configuration Firebase identifient un client, mais ne donnent aucun privilège serveur. La sécurité métier reste assurée par RLS. Ils ne doivent néanmoins pas être versionnés afin d'éviter les mélanges d'environnements et de permettre leur rotation.

Les secrets serveur — service role Supabase, Firebase Admin, FedaPay, OpenAI, OpenRouter, Gemini et Open Exchange Rates — restent exclusivement dans Supabase Secrets ou dans le coffre du fournisseur qui exécute le serveur. Ils ne sont jamais déclarés dans un build mobile.

## Configuration locale

### Android

1. Copier `local.properties.example` vers `local.properties`.
2. Renseigner le tier et ses valeurs Supabase publiques.
3. Ne jamais versionner `local.properties` ni `androidApp/google-services.json`.

Les clés génériques `kwabor.supabase.*` ne sont reprises que par le tier déclaré dans `kwabor.environment`. Les clés qualifiées `kwabor.development.supabase.*`, `kwabor.staging.supabase.*` et `kwabor.production.supabase.*` permettent de valider plusieurs variants sans réutilisation croisée. Une valeur d'environnement inconnue bloque le build.

La matrice exacte des variants, la signature et la génération d'artefacts sont documentées dans [Release Android](android-release.md).

### iOS

1. Copier `iosApp/Kwabor/Config/Local.xcconfig.example` vers `iosApp/Kwabor/Config/Local.xcconfig`.
2. Renseigner les valeurs communes et les paires Supabase qualifiées development/staging/production nécessaires.
3. Renseigner, pour chaque tier, le client OAuth iOS, le client Web/serveur distinct et le reversed client ID exact. Les deux client IDs doivent respecter le format Google `*.apps.googleusercontent.com`.
4. Renseigner pour chaque tier utilisé un chemin absolu `KWABOR_FIREBASE_IOS_CONFIG_PATH_*` vers son `GoogleService-Info.plist`, conservé hors dépôt.
5. Ne jamais versionner ce fichier ni `GoogleService-Info.plist`.

`Debug.xcconfig`, `Staging.xcconfig` et `Release.xcconfig` chargent les valeurs communes puis le fichier local optionnel, et remappent uniquement les clés du tier attendu. Les mêmes clés génériques peuvent être injectées par `xcodebuild` dans la CI, où la configuration Xcode fixe le tier.

Dans un fichier `.xcconfig`, `//` ouvre un commentaire. Une URL HTTPS doit donc être écrite sous la forme `https:/$()/project-ref.supabase.co`, que Xcode résout en `https://project-ref.supabase.co`.

## GitHub Environments

Les environnements `staging` et `production` existent dans `urbainmorel/KWABOR` et n'acceptent que les branches protégées. `production` interdit le contournement administrateur et exige une approbation de `urbainmorel`. Seule la variable non sensible `KWABOR_ENVIRONMENT` est déjà renseignée.

Les variables Supabase, OAuth Google et les deux configurations Firebase doivent être ajoutées seulement après création et vérification des projets correspondants. Aucun workflow ne doit utiliser une valeur de `staging` pour un artefact production.

## Provisionnement Supabase propriétaire

Le compte CLI actuellement disponible ne contient aucune organisation Kwabor. Le propriétaire doit d'abord choisir l'organisation et le plan facturé, puis créer deux projets distincts, par exemple `kwabor-staging` et `kwabor-production`.

Pour chaque projet :

1. relever le project ref, l'URL et la clé publishable ;
2. lier explicitement le checkout avec `supabase link --project-ref <ref>` sans versionner le mot de passe de base ;
3. appliquer les migrations sur staging et exécuter `supabase test db` ;
4. vérifier les grants/RLS négatifs avant de reproduire la migration en production ;
5. configurer Auth avec un mot de passe minimal de 8 caractères, un OTP email de 6 chiffres et un délai minimal de 30 secondes entre deux envois ;
6. publier les templates OTP français d'inscription et de récupération avec la variable Supabase `{{ .Token }}`, puis brancher un SMTP de production vérifié ;
7. provisionner les trois révisions juridiques actives décrites ci-dessous ;
8. renseigner les variables GitHub de l'environnement correspondant ;
9. délier ou relier explicitement avant toute commande distante suivante afin d'éviter une erreur de cible.

La production ne doit jamais être utilisée comme environnement de test ou comme source de seed de développement.

Les nouveaux projets Supabase gratuits ne doivent pas être supposés capables d'utiliser les
templates d'authentification Kwabor avec le SMTP par défaut. Le propriétaire doit confirmer un
plan compatible ou configurer un SMTP personnalisé pour staging et production, puis prouver la
réception de l'OTP d'inscription et de l'OTP Recovery avant toute bêta.

### Gate propriétaire pour Google et Apple

La présence des boutons natifs dans le code ne rend pas les fournisseurs opérationnels. Le
propriétaire doit configurer et tester séparément `staging` et `production`, sans partager un secret
OAuth ni une configuration Supabase entre les deux.

Pour Google :

1. créer les clients OAuth Android attendus pour `com.kwabor.android` et pour chaque certificat de
   signature réellement utilisé ; enregistrer les empreintes demandées par Google pour debug interne,
   staging et clé d'upload/Play App Signing selon le canal ;
2. créer le client OAuth iOS pour `com.kwabor.ios`, puis reporter son client ID et son reversed client
   ID exacts dans les variables du tier ;
3. créer un client OAuth de type Web pour Supabase, autoriser uniquement le callback indiqué par le
   projet Supabase concerné et injecter le même client ID public via
   `KWABOR_GOOGLE_WEB_CLIENT_ID` côté Android et `KWABOR_GOOGLE_SERVER_CLIENT_ID` côté iOS ;
4. activer le fournisseur Google dans Supabase Auth avec le client Web en premier, puis les audiences
   mobiles acceptées ; conserver la vérification de nonce active et stocker le client secret Web
   uniquement dans la configuration serveur Supabase ;
5. prouver sur un appareil signé la connexion d'un compte existant, la création d'un nouveau compte,
   la reprise d'onboarding et la ré-authentification de suppression.

La procédure de référence est la documentation
[Login with Google de Supabase](https://supabase.com/docs/guides/auth/social-login/auth-google).
Le client Web/serveur n'est pas un secret ; son secret OAuth associé l'est et ne doit jamais entrer
dans une variable de build mobile.

Pour Apple :

1. un Account Holder ou Admin Apple active Sign in with Apple sur l'App ID
   `com.kwabor.ios`, choisi comme App ID primaire ou groupé de manière explicitement validée ;
2. régénérer les profils de provisioning staging/production après l'ajout de la capacité et vérifier
   que l'entitlement signé est présent dans l'archive, pas seulement dans le projet Xcode ;
3. activer le fournisseur Apple dans chacun des deux projets Supabase et enregistrer le bundle ID
   natif parmi les audiences acceptées ; la V1 utilise `AuthenticationServices` et
   `signInWithIdToken`, pas un flux web ;
4. enregistrer les domaines et sources d'email Kwabor auprès du relais privé Apple avant d'envoyer
   des OTP ou messages aux adresses masquées ;
5. prouver sur appareil réel la première autorisation, le retour ultérieur sans nom fourni par Apple,
   l'annulation, la reprise d'onboarding et la ré-authentification de suppression.

Le flux natif seul ne requiert pas de Services ID ni de secret `.p8` tournant. Si un flux Apple web
est ajouté ultérieurement, il devient une décision séparée avec Services ID, clé privée protégée et
rotation du secret ; voir
[Login with Apple de Supabase](https://supabase.com/docs/guides/auth/social-login/auth-apple) et
[configuration Apple](https://developer.apple.com/documentation/signinwithapple/configuring-your-environment-for-sign-in-with-apple).

Pour l'activation Promoteur, ajouter exactement `kwabor://auth/promoter-activate` aux redirects
autorisés du projet Supabase, déployer la migration AUTH-005 puis la fonction `account-delete`. Le
token d'invitation brut ne doit apparaître ni dans une table, ni dans les logs d'exploitation, ni
dans Analytics. L'envoi email/WhatsApp des invitations n'est pas encore livré : l'opérateur
habilité doit utiliser une procédure serveur contrôlée et ne jamais republier un lien dans un canal
collectif.

La RPC d'activation n'accepte qu'un compte dont l'onboarding est complet, sans tombstone, et dont le
JWT signé contient une AMR forte récente. Vérifier sur chaque tier que son claim `amr`, exposé par
`auth.jwt()` et fourni à PostgreSQL dans le contexte PostgREST `request.jwt.claim(s)`, est un tableau
d'objets `{ "method": "...", "timestamp": <epoch_seconds> }`. L'entrée au timestamp le plus récent
doit être `password` ou `oauth`, dater d'au plus cinq minutes et ne pas être située à plus de trente
secondes dans le futur. Une AMR absente/malformée, `otp`, `magiclink`, `token_refresh`, une preuve
ancienne ou un timestamp futur doit être refusé. Si un fournisseur réel n'émet pas cette forme, ne
pas élargir silencieusement la règle : bloquer le tier et revoir sa configuration Auth.

Après déploiement de `account-delete`, vérifier explicitement qu'une requête sans bearer, avec bearer
invalide ou avec identité ré-authentifiée différente reçoit un refus. `verify_jwt=true` reste
obligatoire : la plateforme Supabase valide le JWT avant l'exécution, puis
`withSupabase({ auth: 'user' })` exige le contexte utilisateur et `getUser()` confirme encore
l'utilisateur live. Aucun de ces contrôles ne doit être désactivé ou remplacé par la seule lecture
des claims du token.

### Réconciliation des suppressions de compte

La table `account_deletion_requests` est un tombstone serveur privé qui survit à la suppression de
`auth.users`. Elle ne contient ni email, ni nom, ni contenu, ni credential. Le rôle `service_role`
est le seul à pouvoir la lire ou la modifier.

Les politiques RLS Storage `account deletion fences storage inserts/updates` sont restrictives :
elles doivent rester présentes quand MEDIA-001 ajoute ses politiques permissives. Elles prennent le
même verrou transactionnel que `prepare_account_deletion`, refusent les écritures d'un autre
propriétaire et bloquent tout nouvel upload ou upsert dès qu'une suppression existe. La fonction
`account-delete` revalide encore ces blocages après révocation des sessions. Ne jamais contourner
cette barrière avec une clé `service_role` dans un chemin contrôlé par un client.

`activate_promoter_invite` et `complete_user_onboarding` prennent aussi le verrou partagé de
l'utilisateur avant de vérifier l'absence de tout tombstone. Une activation ou finalisation déjà
engagée finit avant le nettoyage exclusif ; après création du tombstone, même la finalisation
idempotente d'un profil auparavant complet échoue avec `42501`. Après chaque migration, tester ces
deux refus avec un JWT encore valide afin de couvrir le délai d'expiration des access tokens révoqués.

Une alerte d'exploitation doit signaler toute ligne `prepared` restée au-delà du délai défini par le
runbook incident. Quand `pg_cron` est disponible, la migration installe le job
`kwabor-account-deletion-reconcile` chaque jour à `03:23 UTC`. Il refait le nettoyage idempotent des
demandes dont l'utilisateur Auth a déjà disparu, les clôt, puis purge les tombstones `completed`
âgés de plus de 30 jours. Sans `pg_cron`, le déploiement doit fournir un ordonnanceur privilégié
équivalent avant d'ouvrir la suppression de compte. La réconciliation se fait sans exposer
l'identifiant dans les logs :

- si l'utilisateur Auth existe, lui faire reprendre la suppression avec une session et une
  ré-authentification fraîches ; une nouvelle clé client reprend la clé effective déjà préparée ;
- si l'utilisateur Auth n'existe plus, laisser la fonction privilégiée refaire le nettoyage et
  marquer la demande `completed` avec sa clé effective ;
- ne jamais supprimer manuellement un utilisateur Auth encore présent sur la seule base d'un
  tombstone `prepared`.

Avant release candidate, le propriétaire et le responsable légal doivent valider la conservation
technique de 30 jours, l'exécution quotidienne, l'accès opérateur audité et la mention
correspondante dans la politique de confidentialité. Les IDs tokens, nonces, mots de passe et tokens
d'invitation ne sont jamais des données de réconciliation.

### Révisions juridiques requises par l'inscription

L'inscription ne peut être finalisée que si Supabase expose exactement une révision française active et déjà effective pour chacun des types `terms`, `privacy_policy` et `ugc_license`. Le propriétaire fournit pour chaque révision une version approuvée, une URL HTTPS publique, le SHA-256 du contenu et sa date d'effet. Aucun texte ou hash de démonstration ne doit être copié depuis les tests vers staging ou production.

Une révision créée est immuable : son type, sa version, sa langue, son URL, son hash et sa date d'effet ne peuvent plus être modifiés ou supprimés, y compris par une opération d'administration ordinaire. Seul le drapeau `active` peut changer. Pour publier une nouvelle version sans fenêtre incohérente :

1. insérer la nouvelle révision inactive ;
2. dans une même transaction, désactiver l'ancienne puis activer la nouvelle ;
3. vérifier qu'un client anonyme lit trois documents effectifs et qu'un client authentifié ne peut ni les modifier ni écrire directement son profil ou son rôle ;
4. exécuter un parcours d'inscription staging et contrôler les trois lignes `user_legal_acceptances` associées à l'utilisateur.

La RPC `complete_user_onboarding` est l'unique voie de finalisation. Elle refuse d'abord tout
tombstone de suppression, puis écrit atomiquement le profil, le rôle utilisateur de base, les trois
preuves d'acceptation et `onboarding_completed_at`. Le client ne doit recevoir aucun droit direct
d'écriture sur ce timestamp ou sur les preuves juridiques.

## Provisionnement Firebase propriétaire

L'authentification Firebase CLI locale est expirée. Le propriétaire doit exécuter `npx firebase-tools login --reauth`, choisir l'organisation Google Cloud et créer deux projets isolés. Dans chacun, il enregistre une app Android `com.kwabor.android` et une app iOS `com.kwabor.ios`, puis conserve les fichiers générés hors Git.

Avant activation des SDK dans `OBS-001`, vérifier pour chaque environnement :

- projet et app mobile cohérents ;
- APNs configuré uniquement avec les credentials Apple du propriétaire ;
- aucun compte de service Firebase Admin dans les clients ;
- fichiers encodés et stockés dans les secrets GitHub du bon environnement ;
- Analytics/Crashlytics/Performance/Remote Config soumis au consentement et aux règles de confidentialité.

L'intégration `OBS-001A` est prête côté code et workflows. Les releases exigent désormais les configurations Firebase encodées en base64, valident le bundle/package cible et détruisent les fichiers injectés à la fin du job. La procédure détaillée, les valeurs sûres et le contrat de consentement sont décrits dans [Observabilité mobile](observability.md).

## Gate avant release

- Les deux projets Supabase sont distincts, migrés et testés.
- Google est configuré dans chaque projet Supabase avec clients Android/iOS/Web du même tier,
  vérification de nonce active et secret Web exclusivement serveur.
- Sign in with Apple est activé sur l'App ID, présent dans les profils signés et configuré dans
  Supabase avec les audiences natives attendues.
- La connexion fédérée, l'activation Promoteur et la ré-authentification de suppression sont prouvées
  sur appareils signés staging ; activation avec `password`/`oauth` de moins de cinq minutes
  réussit, tandis qu'AMR absente, OTP/magic-link, preuve ancienne, timestamp futur, tombstone,
  identité différente et bearer absent échouent.
- Le job quotidien de réconciliation des tombstones `prepared` est actif et alerté ; la rétention
  technique de 30 jours des tombstones `completed` est validée par le propriétaire et le
  responsable légal.
- Les deux projets Firebase et leurs quatre apps mobiles sont distincts et vérifiés.
- Les variables/secrets GitHub existent dans le bon environnement.
- Un build staging ne référence aucun project ref production, et réciproquement.
- Aucun fichier de configuration fournisseur, token, mot de passe ou clé serveur n'est suivi par Git.
- La clé d'upload Android appartient au propriétaire, est sauvegardée hors dépôt et ses quatre secrets GitHub production sont complets.
- L'App ID `com.kwabor.ios` active APNs et Sign in with Apple ; les profils de distribution correspondants sont régénérés puis injectés dans chacun des deux GitHub Environments.
