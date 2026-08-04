# Installation locale

> Préparer un checkout KWABOR reproductible pour Android, KMP, Supabase et, sur macOS, iOS.

## Prérequis

| Outil | Référence vérifiée | Nécessaire pour |
| --- | --- | --- |
| JDK | Temurin 21 en CI ; bytecode JVM 17 | Gradle, KMP et Android |
| Gradle | Wrapper versionné 9.4.1 | Toutes les tâches de build |
| Android SDK | compile/target 36, minSdk 26 | APK Android |
| Android Studio | Version compatible SDK 36/Kotlin 2.4 | Exécution et debug Android |
| macOS + Xcode | iOS 17 minimum ; CI sur macOS 15 | SwiftUI, simulateur et archives iOS |
| Python 3 | Version courante supportée | Vérificateurs du dépôt et tests concurrents |
| FFmpeg/ffprobe | `ffprobe` accessible dans le PATH | Qualification de la vidéo embarquée |
| Docker + Supabase CLI | CLI 2.84.2 en CI, PostgreSQL local 17 | Migrations et pgTAP |
| Deno | 2.9.4 en CI | Edge Function `account-delete` |

Les versions CI sont la référence reproductible. Aucun gestionnaire de paquets JavaScript n'est
requis pour construire les applications mobiles.

## 1. Vérifier le checkout

Depuis la racine du dépôt :

```powershell
git status --short --branch
python -B tools/verify-repository-integrity.py
```

Le second contrôle vérifie notamment les templates, les fichiers sensibles ignorés et le wrapper
Gradle. Il ne configure aucun fournisseur distant.

## 2. Configurer Android localement

Créer le fichier local non versionné :

```powershell
if (-not (Test-Path local.properties)) { Copy-Item local.properties.example local.properties }
```

Si Android Studio a déjà créé `local.properties`, ne pas l'écraser : conserver notamment `sdk.dir`
et fusionner les clés `kwabor.*` depuis l'exemple.

Choisir `kwabor.environment=development`, puis renseigner si nécessaire :

- `kwabor.development.supabase.url` ;
- `kwabor.development.supabase.publishableKey` ;
- `kwabor.development.google.webClientId`.

Ces valeurs client sont publiques, mais doivent correspondre au même tier. Ne jamais ajouter de
secret, `google-services.json` ou clé de signature au dépôt. Le contrat complet est décrit dans
[environment-configuration.md](environment-configuration.md).

Un build debug sans configuration fournisseur reste possible pour vérifier le code. L'application
affiche alors un état d'indisponibilité et ne constitue pas une preuve de parcours connecté.

## 3. Construire Android et le code partagé

```powershell
.\gradlew.bat check :androidApp:assembleDebug --no-daemon --console=plain
```

L'APK attendu est :

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Ouvrir le dépôt dans Android Studio et exécuter la configuration `androidApp` pour le debug sur
émulateur ou appareil. Les variants `staging` et `release` et leurs contraintes de signature sont
documentés dans [android-release.md](android-release.md).

Sur macOS/Linux, remplacer `.\gradlew.bat` par `./gradlew`.

## 4. Configurer et construire iOS

Cette étape exige macOS. Créer le fichier local ignoré :

```bash
cp iosApp/Kwabor/Config/Local.xcconfig.example iosApp/Kwabor/Config/Local.xcconfig
```

Renseigner uniquement le tier nécessaire. Dans un `.xcconfig`, une URL Supabase HTTPS utilise la
forme `https:/$()/project-ref.supabase.co`, comme expliqué dans le guide d'environnement.

Construire ensuite le framework KMP correspondant :

```bash
./gradlew :shared:assembleSharedDebugXCFramework
xcodebuild \
  -project iosApp/Kwabor.xcodeproj \
  -scheme Kwabor \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Les configurations `Staging` et `Release` utilisent le XCFramework release. Voir
[iosApp/README.md](../iosApp/README.md) et [ios-release.md](ios-release.md).

## 5. Démarrer Supabase localement

Docker doit être disponible. Depuis la racine :

```powershell
supabase start
supabase test db
supabase status
```

`supabase start` lance la pile locale nécessaire à l'application, notamment Auth, PostgREST et la
base. La CI utilise `supabase db start` lorsqu'elle n'a besoin que de PostgreSQL pour les tests. La
configuration locale utilise PostgreSQL 17, applique les migrations ordonnées de
`supabase/migrations/` et charge `supabase/seed.sql`. Les tests pgTAP sont dans `supabase/tests/`.
Ne jamais exécuter de reset destructif contre staging ou production.

## 6. Vérifier l'Edge Function locale

```powershell
Push-Location supabase/functions/account-delete
deno install --frozen
deno fmt --check .
deno lint *.ts
deno check *.ts
deno test core_test.ts identity_test.ts
Pop-Location
```

## Problèmes fréquents

### Gradle ne trouve pas le SDK Android

Configurer l'emplacement du SDK via Android Studio ou l'environnement local. Ne pas committer un
chemin machine dans `local.properties`.

### L'application affiche « indisponible »

Vérifier que l'URL Supabase et la publishable key du tier sélectionné sont présentes et cohérentes.
Les valeurs vides sont volontairement acceptées pour un build local non connecté.

### Xcode ne trouve pas `Shared.xcframework`

Construire le XCFramework Debug ou Release avant `xcodebuild`, puis vérifier la configuration Xcode
sélectionnée.

### Les tests Supabase ne démarrent pas

Vérifier Docker et `supabase status`. Ne pas réutiliser ni arrêter un moteur Docker appartenant à un
autre projet sans confirmer son identité.

Étape suivante : [comprendre l'architecture](architecture.md).
