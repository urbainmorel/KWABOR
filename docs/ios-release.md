# Release iOS Kwabor

Ce runbook décrit le flux manuel de bêta fermée iOS : archive Staging signée, export IPA puis upload
séparé vers un groupe TestFlight interne. Il ne couvre aucune soumission App Store publique.

## Matrice des configurations

| Configuration | Tier distant | Identité visible | Framework KMP | APNs | Usage |
|---|---|---|---|---|---|
| `Debug` | `development` | Kwabor Dev | `Shared` debug | sandbox | simulateur et appareil de développement |
| `Staging` | `staging` | Kwabor Staging | `Shared` release | production | archive interne/TestFlight pointant staging |
| `Release` | `production` | Kwabor | `Shared` release | production | archive App Store |

Les trois configurations gardent le bundle ID `com.kwabor.ios`. Les projets Supabase distincts et les fichiers Firebase injectés assurent la séparation des données.

Les fichiers `Debug.xcconfig`, `Staging.xcconfig` et `Release.xcconfig` sélectionnent explicitement leur tier. `Local.xcconfig` reste ignoré par Git et ne contient que les valeurs du propriétaire. Xcode permet officiellement d'appliquer des fichiers de configuration distincts aux builds : [Adding a build configuration file](https://developer.apple.com/documentation/xcode/adding-a-build-configuration-file-to-your-project).

## Versionnement

- `KWABOR_MARKETING_VERSION` : trois entiers séparés par des points, par exemple `1.0.0` ;
- `KWABOR_CURRENT_PROJECT_VERSION` : entier strictement positif et croissant pour chaque upload App Store Connect ;
- valeurs locales initiales : `0.1.0` et `1`.

Le workflow reçoit ces valeurs comme entrées et les valide avant d'importer les éléments de signature. Apple limite `CFBundleShortVersionString` aux chiffres et aux points dans le format majeur.mineur.correctif : [CFBundleShortVersionString](https://developer.apple.com/documentation/bundleresources/information-property-list/cfbundleshortversionstring).

## Icône et lancement

Le catalogue utilise `kwabor_icone_app.png`, à la racine du dépôt, comme source de build verrouillée du symbole carré. L'icône iOS 1024 × 1024 est un redimensionnement opaque de ce PNG, sur fond ink `#0E0E0D`, sans redessin de la silhouette ni de la courbe intérieure. Le propriétaire de marque doit encore confirmer qu'il s'agit du master haute définition officiel, ou fournir son remplacement avant la validation perceptuelle finale. Xcode génère les tailles iOS à partir de cette source, conformément à la [documentation App Icon Apple](https://developer.apple.com/documentation/xcode/configuring-your-app-icon/).

Le logo horizontal complet utilise séparément `kwabor_2.png`. `LaunchWordmark.imageset/LaunchWordmark.png` en est une copie binaire exacte de 2172 × 724, au ratio 3:1. `LaunchScreen.storyboard` l'affiche centré en `scaleAspectFit`, avec un inset horizontal de 24 points et le fond ink du catalogue. La vue SwiftUI conserve le même wordmark au-dessus du lecteur jusqu'à ce que `AVPlayerLayer.isReadyForDisplay` confirme la première frame. Ce raccord évite tout flash vide sans recadrer, recolorer ou réencoder le logo officiel.

Les PNG sont déterministes et régénérables sur Windows avec :

```powershell
.\tools\generate-brand-assets.ps1
```

La CI verrouille les hashes, dimensions, modes PNG, copies exactes et références Xcode. Le même contrôle sans dépendance tierce est disponible localement :

```powershell
python -B tools/verify-brand-assets.py
```

## Privacy Manifest

`PrivacyInfo.xcprivacy` est une ressource de la cible. L'hôte ne déclare aucun tracking. Il déclare son
accès direct à `UserDefaults` avec la raison approuvée `CA92.1`, car les préférences concernées restent
accessibles uniquement à Kwabor : état de présentation de l'intro, reprise d'authentification,
amorçage des notifications et marqueurs de migration. Les consentements d'observabilité liés au
compte résident dans le Keychain. L'inventaire hôte déclare aussi
le nom, l'adresse e-mail, l'identifiant utilisateur, la ville de profil et les interactions produit
comme données liées au compte. Les likes/favoris relèvent de la fonctionnalité ; les événements
d'usage ne partent qu'après opt-in Analytics. La coordonnée ponctuelle utilisée pour proposer la ville
n'est ni transmise ni conservée. Apple documente les catégories et raisons autorisées dans
[Describing use of required reason API](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).

`IOS-PRIVACY-001B1` a inventorié les traitements de l'hôte et des SDK et corrigé les catégories
factuellement prouvées. Le rapport complet est dans
[l'inventaire de confidentialité iOS](audits/2026-08-03-ios-privacy-inventory.md). Cette étape locale
ne constitue pas encore une preuve Store complète : `IOS-PRIVACY-001B2` doit rapprocher l'archive
Release et ses réglages production du Privacy Report Xcode, des décisions de rétention du propriétaire
et du questionnaire App Store Connect. Les manifests fournis par les SDK tiers ne dispensent pas
Kwabor de ce contrôle global. Apple exige le nom `PrivacyInfo.xcprivacy`, son inclusion dans les
ressources et rejette les clés invalides :
[Privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files),
[Adding a privacy manifest](https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk).
Le détail Firebase est tenu dans [Observabilité mobile](observability.md).

Le premier build iOS distribué avec une configuration Firebase réelle doit partir d'une installation
propre. Avant TestFlight, effacer les installations internes ayant déjà exécuté une ancienne build
Firebase et confirmer qu'aucune build Firebase antérieure n'a été distribuée à des utilisateurs. Si
une telle distribution est découverte, la release est bloquée jusqu'à un plan de migration dédié :
l'API publique Crashlytics ne peut pas annuler un envoi déjà démarré sous un ancien override
automatique. L'archive doit contenir les valeurs Analytics/Crashlytics/Performance désactivées par
défaut, puis les parcours refus → accord → révocation doivent être vérifiés sur appareils staging.
Le test staging doit aussi tuer l'app entre les écritures sensibles, vérifier qu'une purge contenant
un rapport survit jusqu'au lancement suivant et confirmer qu'une seconde purge demandée après le check
unique du processus garde les diagnostics désactivés jusqu'au redémarrage, sans couper un consentement
Analytics ou Remote Config indépendant.

Avant chaque release candidate : résoudre les packages, générer le Privacy Report Xcode depuis
l'archive exacte, rapprocher le résultat du code, des SDK et des traitements backend présents, puis
mettre à jour les formulaires App Store Connect. La politique de confidentialité publique approuvée
doit aussi être accessible depuis l'app ; son URL propriétaire reste à fournir.

## Capacités et profils

La cible déclare :

- `aps-environment`, development en Debug et production en Staging/Release ;
- `com.apple.developer.applesignin = Default`.

Apple précise que la valeur APNs finale vient du provisioning profile et que TestFlight utilise production : [APS Environment Entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/aps-environment). Sign in with Apple doit être activé sur l'App ID par un Account Holder ou Admin : [About Sign in with Apple](https://developer.apple.com/help/account/capabilities/about-sign-in-with-apple).

Le propriétaire doit donc :

1. inscrire `com.kwabor.ios` dans son équipe Apple Developer ;
2. activer Push Notifications et Sign in with Apple sur cet App ID ;
3. créer ou régénérer un certificat Apple Distribution et un provisioning profile App Store incluant ces capacités ;
4. sauvegarder le certificat et sa clé privée hors dépôt ;
5. renseigner les variables et secrets des Environments `staging` et `testflight-internal` pour la
   bêta ; la future production publique reste un provisioning séparé hors de ce workflow.

Aucun profil, certificat, mot de passe, fichier `.p12` ou `.mobileprovision` n'est versionné.

## Validation simulateur non signée

Sur macOS :

```bash
./gradlew :shared:assembleSharedDebugXCFramework
xcodebuild -project iosApp/Kwabor.xcodeproj -scheme Kwabor -configuration Debug -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build

./gradlew :shared:assembleSharedReleaseXCFramework
xcodebuild -project iosApp/Kwabor.xcodeproj -scheme Kwabor -configuration Staging -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project iosApp/Kwabor.xcodeproj -scheme Kwabor -configuration Release -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Ces commandes valident le projet et les ressources ; elles ne produisent pas un artefact distribuable.

## Workflow d'archive signée et TestFlight interne

Le workflow `iOS closed beta archive and TestFlight` s'exécute uniquement depuis `main`.
`archive-only` utilise l'Environment `staging` ; `upload-testflight-internal` réaudite `staging` puis
exige `testflight-internal`. Tous deux doivent interdire le bypass administrateur, n'admettre que les
branches protégées sans politique personnalisée hybride, exiger un reviewer et empêcher
l'auto-approbation. L'Environment `staging` contient :

| Nom | Type | Contenu |
|---|---|---|
| `KWABOR_SUPABASE_URL` | Variable | URL publique du projet ciblé |
| `KWABOR_SUPABASE_PUBLISHABLE_KEY` | Variable | clé publishable du projet ciblé |
| `KWABOR_SUPABASE_PROJECT_REF` | Variable | project ref Supabase staging exact |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | Variable | project ref production distinct, utilisé comme garde négative |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | Variable | SHA-256 protégé du project ref staging |
| `KWABOR_FIREBASE_PROJECT_ID` | Variable | project ID Firebase exact du tier |
| `KWABOR_GOOGLE_IOS_CLIENT_ID` | Variable | client OAuth iOS `*.apps.googleusercontent.com` |
| `KWABOR_GOOGLE_SERVER_CLIENT_ID` | Variable | client OAuth Web/serveur distinct, configuré dans Supabase |
| `KWABOR_GOOGLE_REVERSED_CLIENT_ID` | Variable | schéma callback exact dérivé du client iOS |
| `KWABOR_IOS_DEVELOPMENT_TEAM` | Variable | Team ID Apple sur 10 caractères |
| `KWABOR_IOS_BUNDLE_ID` | Variable | bundle ID exact `com.kwabor.ios` |
| `KWABOR_IOS_PROVISIONING_PROFILE_NAME` | Variable | nom exact du profil App Store staging |
| `KWABOR_IOS_USES_NON_EXEMPT_ENCRYPTION` | Variable | déclaration export compliance attendue |
| `KWABOR_FIREBASE_IOS_CONFIG_BASE64` | Secret | `GoogleService-Info.plist` du tier encodé en Base64 |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_BASE64` | Secret | certificat + clé privée exportés en `.p12`, encodés Base64 |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | Secret | mot de passe du `.p12` |
| `KWABOR_IOS_PROVISIONING_PROFILE_BASE64` | Secret | profil App Store encodé Base64 |

L'opération `archive-only` reçoit `expected_sha`, `validated_ci_run_id`, `build_number` et
`version_name`, puis :

1. exige le SHA exact de `main` et le run CI réussi explicitement fourni ;
2. valide Staging, version, build number, Supabase, Firebase et les identifiants OAuth Google ;
3. importe le certificat dans un keychain temporaire et vérifie le profil ;
4. assemble le XCFramework release, archive avec signature manuelle et exporte l'IPA interne ;
5. revérifie Info.plist, callback Google, Privacy Manifest, assets, dSYM, signature et entitlements ;
6. archive IPA, xcarchive, dSYM, hashes, provenance et reçu GEL ;
7. supprime keychain et profil du runner, y compris après échec.

`upload-testflight-internal` reçoit en plus `archive_run_id`, les notes françaises et la confirmation
`UPLOAD-TESTFLIGHT-INTERNAL`. Il retélécharge seulement l'artefact immuable du run `archive-only`,
revérifie SHA, signature, profil et backend Staging, puis utilise les variables App Store Connect
`KWABOR_ASC_KEY_ID`, `KWABOR_ASC_ISSUER_ID`, `KWABOR_ASC_APP_ID`,
`KWABOR_TESTFLIGHT_INTERNAL_GROUP_ID` et le secret `KWABOR_ASC_PRIVATE_KEY_BASE64`. Il attend le
traitement Apple et associe le build uniquement au groupe interne configuré.

Le job d'upload s'exécute sous `testflight-internal` : cet Environment doit donc contenir une copie
strictement concordante de toutes les variables staging et des trois secrets de signature ci-dessus,
en plus des quatre variables App Store Connect et de sa clé privée. Le workflow compare de nouveau
ces valeurs à l'artefact et échoue fermé sur toute divergence. Cette duplication est volontairement
explicite ; elle ne doit jamais être remplacée par une valeur production ou un secret de dépôt global.

Aucune opération ne soumet le build à l'App Review, n'active un groupe externe ni ne publie une
version publique. Le traitement TestFlight et l'installation sur appareils réels restent des preuves
G6 obligatoires.
