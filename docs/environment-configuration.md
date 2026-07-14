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
| `KWABOR_FIREBASE_ANDROID_CONFIG_BASE64` | Configuration d'intégrité | fichier généré par workflow | — | Secret |
| `KWABOR_FIREBASE_IOS_CONFIG_BASE64` | Configuration d'intégrité | — | fichier généré par workflow | Secret |

La clé Supabase publishable et les fichiers de configuration Firebase identifient un client, mais ne donnent aucun privilège serveur. La sécurité métier reste assurée par RLS. Ils ne doivent néanmoins pas être versionnés afin d'éviter les mélanges d'environnements et de permettre leur rotation.

Les secrets serveur — service role Supabase, Firebase Admin, FedaPay, OpenAI, OpenRouter, Gemini et Open Exchange Rates — restent exclusivement dans Supabase Secrets ou dans le coffre du fournisseur qui exécute le serveur. Ils ne sont jamais déclarés dans un build mobile.

## Configuration locale

### Android

1. Copier `local.properties.example` vers `local.properties`.
2. Renseigner les trois clés `kwabor.*`.
3. Ne jamais versionner `local.properties` ni `androidApp/google-services.json`.

Sans URL et clé publishable, l'application affiche son état d'indisponibilité sûr. Une valeur d'environnement inconnue bloque le build.

### iOS

1. Copier `iosApp/Kwabor/Config/Local.xcconfig.example` vers `iosApp/Kwabor/Config/Local.xcconfig`.
2. Renseigner les trois valeurs.
3. Ne jamais versionner ce fichier ni `GoogleService-Info.plist`.

`Base.xcconfig` est versionné, ne contient aucune valeur distante et charge le fichier local optionnel. Les mêmes clés peuvent être injectées par `xcodebuild` dans la CI.

Dans un fichier `.xcconfig`, `//` ouvre un commentaire. Une URL HTTPS doit donc être écrite sous la forme `https:/$()/project-ref.supabase.co`, que Xcode résout en `https://project-ref.supabase.co`.

## GitHub Environments

Les environnements `staging` et `production` existent dans `urbainmorel/KWABOR` et n'acceptent que les branches protégées. `production` interdit le contournement administrateur et exige une approbation de `urbainmorel`. Seule la variable non sensible `KWABOR_ENVIRONMENT` est déjà renseignée.

Les variables Supabase et les deux configurations Firebase doivent être ajoutées seulement après création et vérification des projets correspondants. Aucun workflow ne doit utiliser une valeur de `staging` pour un artefact production.

## Provisionnement Supabase propriétaire

Le compte CLI actuellement disponible ne contient aucune organisation Kwabor. Le propriétaire doit d'abord choisir l'organisation et le plan facturé, puis créer deux projets distincts, par exemple `kwabor-staging` et `kwabor-production`.

Pour chaque projet :

1. relever le project ref, l'URL et la clé publishable ;
2. lier explicitement le checkout avec `supabase link --project-ref <ref>` sans versionner le mot de passe de base ;
3. appliquer les migrations sur staging et exécuter `supabase test db` ;
4. vérifier les grants/RLS négatifs avant de reproduire la migration en production ;
5. renseigner les variables GitHub de l'environnement correspondant ;
6. délier ou relier explicitement avant toute commande distante suivante afin d'éviter une erreur de cible.

La production ne doit jamais être utilisée comme environnement de test ou comme source de seed de développement.

## Provisionnement Firebase propriétaire

L'authentification Firebase CLI locale est expirée. Le propriétaire doit exécuter `npx firebase-tools login --reauth`, choisir l'organisation Google Cloud et créer deux projets isolés. Dans chacun, il enregistre une app Android `com.kwabor.android` et une app iOS `com.kwabor.ios`, puis conserve les fichiers générés hors Git.

Avant activation des SDK dans `OBS-001`, vérifier pour chaque environnement :

- projet et app mobile cohérents ;
- APNs configuré uniquement avec les credentials Apple du propriétaire ;
- aucun compte de service Firebase Admin dans les clients ;
- fichiers encodés et stockés dans les secrets GitHub du bon environnement ;
- Analytics/Crashlytics/Performance/Remote Config soumis au consentement et aux règles de confidentialité.

## Gate avant release

- Les deux projets Supabase sont distincts, migrés et testés.
- Les deux projets Firebase et leurs quatre apps mobiles sont distincts et vérifiés.
- Les variables/secrets GitHub existent dans le bon environnement.
- Un build staging ne référence aucun project ref production, et réciproquement.
- Aucun fichier de configuration fournisseur, token, mot de passe ou clé serveur n'est suivi par Git.
