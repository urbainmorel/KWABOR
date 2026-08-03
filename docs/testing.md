# Tests et qualité

> Exécuter les validations proportionnées au risque, puis la gate complète avant de proposer une PR.

## Gate locale principale

Sous Windows :

```powershell
.\gradlew.bat check --no-daemon --console=plain
```

Sous macOS/Linux :

```bash
./gradlew check --no-daemon --console=plain
```

`check` dépend de Spotless et Detekt dans chaque module. Dans `shared`, il vérifie aussi la pureté du
domaine et l'historique des schémas Room, en plus des tests configurés.

## Tests ciblés

| Commande Windows | Portée |
| --- | --- |
| `.\gradlew.bat :androidApp:testDebugUnitTest --console=plain` | Tests unitaires Android/JVM |
| `.\gradlew.bat :shared:testAndroidHostTest --console=plain` | Tests shared, Room et Robolectric |
| `.\gradlew.bat spotlessCheck --console=plain` | Formatage Kotlin/KTS |
| `.\gradlew.bat detekt --console=plain` | Analyse statique Kotlin |
| `.\gradlew.bat :androidApp:lintDebug --console=plain` | Lint Android debug |

Sur macOS, le runtime KMP iOS se teste avec :

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Rapports HTML habituels :

```text
androidApp/build/reports/tests/testDebugUnitTest/index.html
shared/build/reports/tests/testAndroidHostTest/index.html
```

## Gate mobile élargie

Pour une tranche Android/KMP qui touche le code partagé :

```powershell
.\gradlew.bat check :androidApp:assembleDebug --no-daemon --console=plain
```

Les cibles Apple sont désactivées sur Windows et leurs tâches peuvent être ignorées par Gradle. Une
commande verte sur ce poste ne constitue donc aucune preuve Kotlin/Native ou iOS. La preuve iOS
exige le job `macos-15`, Xcode, les tests Swift et, avant release, un parcours sur appareil iOS.

## Supabase et PostgreSQL

Avec Docker et Supabase CLI disponibles :

```powershell
supabase db start
supabase test db
python -B tools/test-event-details-concurrency.py
```

Une modification de migration/RLS doit aussi passer un reset isolé et le lint Supabase adaptés au
lot. Ne jamais utiliser un reset destructif sur staging ou production. Le harnais de concurrence
événement est séparé de la suite pgTAP standard et exige la stack locale attendue.

## Edge Function `account-delete`

Depuis `supabase/functions/account-delete` :

```powershell
deno install --frozen
deno fmt --check .
deno lint *.ts
deno check *.ts
deno test core_test.ts identity_test.ts
```

La CI utilise Deno 2.9.4.

## Intégrité, marque et média

```powershell
python -B tools/verify-repository-integrity.py
python -B tools/verify-brand-assets.py
python -B tools/verify-onboarding-media.py
```

Le vérificateur vidéo exige `ffprobe`. Quand un asset de lancement ou son câblage change, la CI peut
exiger en plus la matrice Android définie dans `.github/workflows/android-launch-evidence.yml`.

## Validation iOS native

Le job macOS de `.github/workflows/ci.yml` est la source exacte. Il :

1. compile et exécute les PolicyTests Swift purs ;
2. exécute `:shared:iosSimulatorArm64Test` ;
3. construit les XCFrameworks Debug/Release ;
4. résout les Swift packages verrouillés ;
5. construit Debug, Staging et Release sur simulateur sans signature.

Ne pas simplifier manuellement le bloc `xcrun swiftc` : maintenir la liste versionnée des fichiers
de politiques dans le workflow ou extraire d'abord un script dédié.

## Arborescence des tests

```text
androidApp/src/test/          Tests Android/JVM et politiques Compose
shared/src/commonTest/        Domaine, data et runtimes multiplateformes
shared/src/androidHostTest/   Room, migrations et intégration Koin sur hôte Android
shared/src/iosTest/           Contrôleurs et runtime iOS KMP
iosApp/PolicyTests/           Politiques Swift pures
supabase/tests/               pgTAP, RLS, grants et contrats SQL
supabase/functions/*/*_test.ts Tests Deno des Edge Functions
```

## Avant une PR

- Commencer par les tests du comportement modifié, puis élargir selon le risque.
- Exécuter `git diff --check`.
- Vérifier les scripts d'intégrité concernés.
- Exécuter `check`; ajouter APK, Kotlin iOS, Supabase ou Xcode selon le périmètre.
- Ne jamais ajouter baseline Detekt/ktlint, `@Suppress` ou désactivation de test sans justification
  explicitement approuvée.
- Documenter honnêtement les validations impossibles sur le poste local.

Étape suivante : [préparer une contribution](../CONTRIBUTING.md).
