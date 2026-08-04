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

SEC-001F utilise la porte ciblée suivante depuis la racine du dépôt avant toute validation hébergée :

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*AccountDeletion*" --console=plain
.\gradlew.bat :shared:compileTestKotlinIosX64 --console=plain
supabase db reset --local --yes
supabase test db
```

Depuis `supabase/functions/account-delete`, utiliser la même version Deno que la CI :

```powershell
npx -y deno@2.9.4 fmt --check .
npx -y deno@2.9.4 check --config deno.json index.ts
npx -y deno@2.9.4 test --config deno.json core_test.ts identity_test.ts
```

La preuve locale du 3 août 2026 est verte : tests Kotlin ciblés Android, compilation des tests
Kotlin/Native iOS X64, format/check Deno, 20/20 tests Edge, reset complet, lint
`public`/`app_private` et 753 assertions pgTAP. Ce total est un instantané, pas une valeur à figer dans
un script.

Les tests de suppression doivent continuer à prouver cumulativement :

- client Auth éphémère avec `MemorySessionManager`, sans persistance, refresh automatique ni
  callbacks de cycle de vie, et avec `LogLevel.NONE` ; credential envoyé seulement à Auth, identité
  temporaire égale à la session principale et nettoyage non annulable sans effacer la session
  principale sur échec ;
- body Edge composé exactement de `idempotency_key`, refus de tout champ supplémentaire ou secret ;
- égalité `userClaims.id`/`jwtClaims.sub`/`getUser().id`, `session_id` UUID, dernière AMR
  `password`/`oauth` dans la fenêtre de 300 secondes avec 30 secondes de tolérance future ;
- vérification et verrouillage atomiques de la session Auth vivante avant la première mutation, ACL
  `service_role` uniquement, puis reprise utilisateur avec reconnexion principale et nouvelle session
  éphémère si Auth existe encore ;
- sentinelle de profil pseudonymisée retenue uniquement pour son propriétaire, sans donnée de profil
  fournie par l'utilisateur, masquée au public et non modifiable sous tombstone ; refus de `completed`
  tant que l'utilisateur Auth existe ;
- après suppression Auth, nettoyage final et clôture directe ou par la réconciliation serveur
  idempotente.

Ces tests locaux ne prouvent pas les AMR réellement émises par email, Google ou Apple sur staging,
ni la politique de rétention/expurgation des en-têtes d'invocation ou des Log Drains. Ces deux preuves
restent des gates hébergées avant activation de `account-delete`.

## Intégrité, marque et média

```powershell
python -B -m unittest tools/test_verify_repository_integrity.py
python -B tools/verify-repository-integrity.py
python -B tools/verify-brand-assets.py
python -B tools/verify-onboarding-media.py
```

Les 119 tests de régression actuels verrouillent le contrat exact du Privacy Manifest iOS, notamment la
Required Reason API `UserDefaults` et `CA92.1`, ainsi que les invariants Firebase Android/iOS :
initialisation Android différée, collecte Crashlytics automatique interdite, instrumentation
Performance automatique interdite, envoi manuel, purges durables, transactions Firebase Installations,
retry, callbacks obsolètes, reprise atomique des anciens overrides iOS et absence de liaison Firebase
avant le nettoyage de première installation.

Les quatre sources iOS, les sept sources Android et les trois fichiers de configuration Android qui
portent ces garanties utilisent une empreinte
SHA-256 d'audit. Seuls le BOM UTF-8 et la représentation LF/CRLF sont normalisés ; tout autre changement,
même de formatage, exige une nouvelle revue avant mise à jour de l'empreinte. Les frontières globales
interdisent aussi les accès Firebase hors adaptateur depuis Swift, Objective-C, Kotlin ou Java, y
compris depuis `shared/src/androidMain`, par chargement dynamique ou via des échappements Unicode Java.
Les dépendances déclarées sont inspectées après évaluation Gradle ; les scripts Gradle et catalogues de
versions sont aussi parcourus dans tout le dépôt puis verrouillés par inventaire et empreinte. Les
notations Maven directes, `group`/`name`, Groovy, TOML, concaténées ou calculées ne peuvent donc pas
introduire Firebase hors d'`androidApp` sans faire échouer `check` ou l'intégrité statique.
Les mutations adversariales vérifient les retours anticipés, gates forcées, appels supplémentaires,
imports hors frontière, appel direct de l'adaptateur privé, variantes Objective-C et manifestes Android
de variante. Les invariants sémantiques sont exercés avant les empreintes afin qu'un test ne réussisse
pas seulement grâce au changement de hash.

Les 39 tests JVM Android ciblés exercent en plus l'installation neuve sans configuration, les deux
phases persistantes, les pannes d'écriture avec snapshot mémoire/disque fidèle, le rollback de
l'historique, le rebind du même compte, le replay borné du choix échoué, son remplacement par une
révocation sûre lors d'un changement de session, les purges diagnostics/FID,
les changements de compte et les callbacks obsolètes.
Ces tests inspectent les sources et détectent une régression de structure ; ils ne simulent ni le
Keychain iOS, ni un crash de processus réel, ni le réseau Firebase. Les PolicyTests Swift et les scénarios sur
appareil/macOS restent les preuves comportementales requises. Cette commande reste manuelle dans cette
tranche : aucun workflow CI n'a été modifié sans accord explicite.

La tâche `:androidApp:verifyFirebaseMergedManifests`, appelée par `check`, régénère les manifestes
debug, staging et release. Elle exige l'absence de `FirebaseInitProvider`, `AD_ID`, des permissions
AdServices, d'Install Referrer et de `android.ext.adservices`, puis exactement six valeurs de collecte
à `false`.

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
