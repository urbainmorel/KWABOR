# Release iOS Kwabor

Ce runbook décrit la fondation de build, de signature et d'archive iOS. L'export IPA et l'envoi TestFlight restent une gate de publication dédiée après validation du compte App Store Connect.

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

Le catalogue utilise une icône iOS source unique de 1024 × 1024 pixels, ink `#0E0E10` et mark blanc, sans transparence. Xcode génère les tailles iOS à partir de cette source, conformément à la [documentation App Icon Apple](https://developer.apple.com/documentation/xcode/configuring-your-app-icon/).

Le lancement natif s'appuie sur `UILaunchScreen`, une couleur ink et le mark central, sans storyboard ni logique applicative. Apple documente `UIColorName` et `UIImageName` pour ce contrat : [UILaunchScreen](https://developer.apple.com/documentation/bundleresources/information-property-list/uilaunchscreen).

Les PNG sont déterministes et régénérables sur Windows avec :

```powershell
.\tools\generate-ios-brand-assets.ps1
```

## Privacy Manifest

`PrivacyInfo.xcprivacy` est une ressource de la cible. Il déclare l'état réel de la fondation : aucun tracking ni Required Reason API utilisée directement ; les événements produit et l'ID opaque de ville sont déclarés pour Analytics, sans liaison à l'identité.

Ce fichier doit être réaudité dès qu'une feature collecte une donnée ou qu'un SDK est mis à jour. Firebase et chaque SDK tiers gardent leur propre manifest pour leurs collectes internes ; le manifest applicatif ne recopie que les données définies par l'hôte. Apple exige le nom `PrivacyInfo.xcprivacy`, son inclusion dans les ressources et rejette les clés invalides : [Privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files), [Adding a privacy manifest](https://developer.apple.com/documentation/bundleresources/adding-a-privacy-manifest-to-your-app-or-third-party-sdk). Le détail Firebase est tenu dans [Observabilité mobile](observability.md).

Avant chaque release candidate : générer le Privacy Report Xcode, rapprocher le résultat du code et des SDK présents, puis mettre à jour les formulaires App Store Connect.

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
5. renseigner les variables et secrets GitHub de staging puis production.

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

## Workflow d'archive signée

Le workflow `iOS archive artifact` s'exécute uniquement depuis `main`, dans le GitHub Environment `staging` ou `production`. Chaque environnement doit contenir :

| Nom | Type | Contenu |
|---|---|---|
| `KWABOR_SUPABASE_URL` | Variable | URL publique du projet ciblé |
| `KWABOR_SUPABASE_PUBLISHABLE_KEY` | Variable | clé publishable du projet ciblé |
| `KWABOR_IOS_DEVELOPMENT_TEAM` | Variable | Team ID Apple sur 10 caractères |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_BASE64` | Secret | certificat + clé privée exportés en `.p12`, encodés Base64 |
| `KWABOR_IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | Secret | mot de passe du `.p12` |
| `KWABOR_IOS_PROVISIONING_PROFILE_BASE64` | Secret | profil App Store encodé Base64 |

Le workflow :

1. valide tier, version, build number et configuration Supabase ;
2. importe le certificat dans un keychain temporaire ;
3. vérifie bundle ID, APNs production et Sign in with Apple dans le profil ;
4. assemble le XCFramework release et archive avec signature manuelle ;
5. vérifie Info.plist, Privacy Manifest, assets, dSYM, signature et entitlements ;
6. publie uniquement l'archive compressée et son SHA-256 ;
7. supprime keychain et profil du runner, y compris après échec.

L'export App Store, le téléversement TestFlight et le rollout appartiennent à `STORE-IOS-001`. Cette séparation empêche qu'un simple build de fondation publie involontairement une version.
