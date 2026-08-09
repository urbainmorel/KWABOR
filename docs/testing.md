# Tests et qualité

> Exécuter les validations proportionnées au risque, puis la gate complète avant de proposer une PR.

## Stratégie rapide sans baisse de qualité

Le cycle de développement commence par les contrôles directement liés aux fichiers modifiés :
compilation de la cible, tests de la feature, Detekt du module et pgTAP du contrat concerné. Les
commandes Gradle compatibles sont regroupées dans une seule invocation afin de réutiliser le daemon,
le graphe configuré et le cache. Une gate globale n’est relancée localement qu’après stabilisation du
lot, pas après chaque petite correction.

Le workflow GitHub répartit ensuite les preuves longues entre des workers indépendants : intégrité
dépôt/médias, Gradle, Edge Function, base Supabase complète et préparation iOS. Les configurations
Xcode Debug, Staging et Release consomment le même artefact XCFramework puis s’exécutent en matrice
parallèle. Les checks protégés `quality`, `iOS simulator build` et
`Android launch evidence gate` restent inchangés ; un lot n’est donc pas déclaré livrable avant le
succès du commit exact sur GitHub. La concurrence annule uniquement les anciens runs d’une même PR,
jamais un run de `main`.

En cas d’échec hébergé, ne relancer localement que la frontière en cause avant de republier : test
Kotlin ciblé, fichier pgTAP, configuration Xcode concernée ou vérificateur média. Cette discipline
évite les matrices locales répétées tout en conservant une preuve finale Android/iOS/Supabase.

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
| `python -B -m unittest tools/test_verify_repository_integrity.py` | Contrats privacy, sauvegarde locale et intégrité |

Sur macOS, le runtime KMP iOS se teste avec :

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Ce test exécute aussi la politique Room iOS sur le système de fichiers du simulateur : exclusion des
sauvegardes, appel Foundation de la protection `CompleteUntilFirstUserAuthentication`, idempotence,
protection déterministe de la famille SQLite et repli mémoire fail-closed. Le simulateur ne restitue
pas toujours la classe de protection après écriture ; la relecture exacte reste donc une preuve à
qualifier sur appareil. La compilation `:shared:compileTestKotlinIosX64` sous Windows ne prouve que
les signatures Kotlin/Native ; le test runtime macOS et une qualification sur appareil restent requis.

Sur Android, `:shared:testAndroidHostTest` ouvre réellement Room dans `noBackupFilesDir` et couvre le
nettoyage du cache historique ainsi que le repli mémoire. Cette preuve hôte ne remplace pas les tests
`bmgr` sur API 30/31/36.1 ni les transferts sur un appareil OEM représentatif.

### Qualification Android des sauvegardes

Sur chaque niveau d’API, installer l’APK à qualifier puis relever d’abord l’état du gestionnaire et le
transport actifs afin de pouvoir les restaurer après le test :

```powershell
adb -s <serial> shell dumpsys package com.kwabor.android
adb -s <serial> shell bmgr enabled
adb -s <serial> shell bmgr list transports
```

Activer temporairement le gestionnaire, sélectionner le transport local disponible sur l’image et
demander une sauvegarde explicite :

```powershell
adb -s <serial> shell bmgr enable true
adb -s <serial> shell bmgr transport com.android.localtransport/.LocalTransport
adb -s <serial> shell bmgr backupnow com.kwabor.android
```

L’attendu KWABOR est `Backup is not allowed`. Dans ce cas aucun jeu de sauvegarde n’existe : une
désinstallation/restauration ne fournirait pas de preuve supplémentaire. Si le paquet est accepté,
considérer le test en échec, conserver les journaux et exécuter le protocole officiel complet de
sauvegarde/restauration avant toute correction. À la fin, remettre le transport initial et l’état
initial de `bmgr` ; supprimer uniquement les données de test explicitement créées.

La preuve locale du 4 août 2026 couvre Android 11/API 30 avec une APK ciblant l’API 36 : le paquet ne
porte pas `ALLOW_BACKUP` à l’exécution et le transport local le refuse. Les API 31/36.1, un appareil
OEM et le transfert Android↔iOS restent des gates séparées.

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
python -B tools/test-search-history-concurrency.py
python -B tools/test-favorites-concurrency.py
```

Une modification de migration/RLS doit aussi passer un reset isolé et le lint Supabase adaptés au
lot. Ne jamais utiliser un reset destructif sur staging ou production. Les harnais de concurrence
événement, historique et favoris sont séparés de la suite pgTAP standard et exigent la stack locale
attendue.

Sur ce poste Windows, les tâches qui nécessitent Docker sont déléguées à GitHub Actions. La preuve
EXPLORE-002B2A exige un démarrage propre depuis toutes les migrations, la suite pgTAP, le lint, les
advisors et les autres gates protégées sur le SHA exact de la PR.

### Classement catalogue EXPLORE-002B2A

Le fichier `supabase/tests/explore_catalog_summaries_v2_test.sql` verrouille le nouveau contrat sans
modifier `list_catalog_summaries(...)`. Il doit notamment prouver :

- l'identité exacte de la fonction v2, `security invoker`, le `search_path` vide, les grants directs
  `anon`/`authenticated` et l'absence d'exécution `PUBLIC` ou `service_role` ;
- la lecture published-only sous RLS et la compatibilité inchangée du RPC v1 ;
- le score `views_count + 5 × likes_count` calculé en `bigint`, ses tie-breakers et les tris autorisés ;
- les phases événement ongoing/upcoming/ended, les bornes exactes et l'intersection semi-ouverte
  `[start, end)` des fenêtres UTC ;
- les bornes prix XOF inclusives, indépendantes et validées avant l'attribution sponsorisée ;
- au plus deux placements sponsorisés en tête, y compris avec des tailles de page qui coupent la
  première rangée, sans dupliquer ni supprimer les autres fiches éligibles ;
- le curseur v2, son snapshot stable, la ligne sentinelle, les continuations sans doublon et le refus
  des curseurs malformés, futurs, v1 ou réutilisés avec un autre filtre, tri ou prix.

Ne pas figer dans cette documentation le nombre total d'assertions : la CI exacte de la PR constitue
la preuve, et le plan pgTAP évolue avec le contrat.

## Recherche catalogue SEARCH-001A

SEARCH-001A combine un RPC PostgreSQL versionné, un runtime KMP, un repli sur le cache Room Explore
et deux UI natives. Les validations doivent couvrir ces quatre frontières ; un test vert du seul
écran ne prouve pas le contrat serveur ni le comportement hors ligne.

### Contrat serveur

Sur une stack Supabase locale jetable, reconstruire la base depuis toutes les migrations puis lancer
la suite pgTAP complète :

```powershell
supabase db start
supabase db reset --local --yes
supabase test db
supabase db lint --local --level warning
```

Le reset est destructif pour la base locale ciblée : vérifier le projet et les ports avant de
l’exécuter, et ne jamais appliquer ce protocole à staging ou production. Le fichier
`supabase/tests/search_catalog_summaries_v1_test.sql` doit notamment prouver :

- la signature stable, `security invoker`, le `search_path` fixé, les grants `anon`/`authenticated`
  et l’absence de grant public inattendu ;
- la recherche par nom, ville, catégorie et tags, y compris une requête dont les mots correspondent
  à plusieurs champs ;
- l’invisibilité des brouillons pour les appels anonymes comme authentifiés ;
- le trim, la casse, les caractères spéciaux traités comme texte et le refus des requêtes, limites
  ou curseurs invalides ;
- la ligne sentinelle, la continuation sans omission et le refus d’un curseur réutilisé avec une
  autre requête ou un autre filtre.

Ne pas figer le nombre total d’assertions dans les scripts : il évolue avec les migrations. Toute
modification du document indexé, des grants, du tri ou du curseur exige un nouveau test pgTAP
négatif correspondant.

### Domaine, data, Room et Android

Sous Windows, la porte ciblée de la tranche est :

```powershell
.\gradlew.bat :shared:testAndroidHostTest --tests "*Search*" :androidApp:testDebugUnitTest --tests "*Search*" :androidApp:compileDebugKotlin --no-daemon --console=plain
```

Elle doit couvrir la validation de la requête, les états submit/refresh/append, l’annulation des
réponses obsolètes, le mapping du RPC, le repli uniquement sur erreur réseau, les erreurs Room
typées, la déduplication, les bornes de pagination locale et l’ouverture réelle du cache Room. Les
tests Android vérifient aussi les politiques de pagination/accessibilité et que l’événement
`search_query` ne contient pas le texte brut.

Élargir ensuite selon le risque :

```powershell
.\gradlew.bat spotlessCheck detekt check :androidApp:lintDebug :androidApp:assembleDebug --no-daemon --console=plain
```

### Kotlin iOS et SwiftUI

Sous Windows, la compilation Kotlin/Native suivante vérifie les signatures du contrôleur iOS, mais
ne remplace pas une exécution Apple :

```powershell
.\gradlew.bat :shared:compileTestKotlinIosX64 --no-daemon --console=plain
```

La preuve fonctionnelle exige ensuite, sur macOS, `:shared:iosSimulatorArm64Test`, les PolicyTests
Swift et les builds simulateur décrits dans « Validation iOS native ». Elle doit couvrir les effets
du contrôleur, le changement de portée, l’accessibilité, la pagination et l’ouverture d’une fiche.

### Parcours fonctionnel minimal

Sur Android et iOS :

1. soumettre une recherche par nom, ville et catégorie dans l’onglet actif, puis dans « Tout » ;
2. vérifier pagination, refresh, changement de contexte et ouverture d’une fiche sans doublon ;
3. confirmer qu’une simple frappe ne lance ni requête ni événement Analytics ;
4. après avoir alimenté Explore, couper le réseau et vérifier le badge hors ligne ainsi qu’un match
   par nom/ville/catégorie depuis Room ;
5. vérifier qu’un tag présent seulement côté serveur n’est pas annoncé comme disponible hors ligne :
   le cache résumé actuel ne garantit pas cette parité ;
6. redémarrer l’application et confirmer que SEARCH-001A n’affiche aucun récent : cette tranche ne
   persiste volontairement aucune requête.

Le point 6 n’est pas la politique produit finale. L’historique durable des comptes, les récents
invités, l’import explicite et les signaux consentis pour l’Assistant IA et le fil organique doivent
être validés dans la tranche HISTORY séparée, uniquement à partir des requêtes soumises.

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
appareil/macOS restent les preuves comportementales requises. Les contrôles statiques reproductibles
sont répartis dans les workers GitHub ; les preuves appareil restent manuelles.

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
