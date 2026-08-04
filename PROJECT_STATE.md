# PROJECT_STATE.md — Kwabor

## Phase actuelle

Reprise V1 — audit de préparation terminé, stabilisation sécurité prioritaire en cours.

## Dernière tâche terminée

- PR fondation `#1` mergée dans `main`.
- PR FND-004 `#2` mergée dans `main` avec CI `quality` verte.
- PR FND-005 `#4` vérifiée avec CI `quality` verte.
- Contrats domaine ajoutés pour catalogue, auth, profil, social, promotion et notifications.
- Contrats sensibles durcis après revue QA : création campagne via demande/devis, onboarding auth avec acceptations obligatoires.
- Migrations Supabase initiales ajoutées : référentiels, profils/rôles, fiches, médias, social, favoris, likes, notifications, claims, signalements, campagnes et paiements.
- RLS initiale validée par pgTAP : lecture publique limitée aux fiches publiées, écriture `listings` par rôle vérifié × `listing_class`, UGC rattaché obligatoire, claims patrimoniaux bloqués, paiements/campagnes non insérables par client.
- Seeds Bénin minimaux ajoutés : villes, catégories et fiches publiées de test.
- Scaffold KMP mobile-only stabilisé avec `shared`, `androidApp` et hôte `iosApp`.
- ADR fondateurs normalisés sous `docs/adr/`.
- Shell Compose partagé, primitives domaine, tokens design et i18n FR minimale ajoutés.
- Cadrage mobile-only validé : Android/iOS uniquement, Android Compose Multiplatform, iOS SwiftUI, aucun autre client applicatif dans la roadmap active.
- Modèle d'équipe vérifiée cadré : Propriétaire > Gestionnaire > Éditeur > Modérateur, droits cumulatifs et budgets contrôlés côté serveur/RLS.
- Ancienne cible non mobile supprimée du dépôt et du build Gradle ; cible de compilation associée retirée de `shared`.
- Cibles iOS KMP ajoutées dans `shared`, XCFramework `Shared` configuré et bridge `KwaborSharedBridge` exposé à Swift.
- Hôte iOS SwiftUI minimal créé avec projet Xcode, scheme partagé et job GitHub Actions macOS `iOS simulator build`.
- PR mobile-only `#5` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Socle Supabase équipes ajouté : `organizations`, `organization_members`, `organization_invites`, `member_ad_budgets`, helpers RLS privés et grants explicites.
- RLS équipes validée par pgTAP : lecture limitée aux membres, invitations selon Propriétaire/Gestionnaire, blocage Éditeur, budgets publicitaires alloués selon rôle et plafond Gestionnaire.
- PR DATA-TEAM-001 `#6` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Modèles domaine organisations ajoutés : organisation, membre, invitation, budget publicitaire, hiérarchie Propriétaire > Gestionnaire > Éditeur > Modérateur, requêtes validées et contrat `OrganizationRepository`.
- PR DOMAIN-TEAM-001 `#7` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Couche data organisations ajoutée : DTO Supabase, mappers domaine, contrat `OrganizationDataSource`, implémentation `DataOrganizationRepository` et tests `commonTest`.
- PR DATA-TEAM-002 `#8` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- RPC Supabase organisations ajouté : création/révocation/acceptation d'invitation et suspension membre, avec pgTAP.
- `OrganizationDataSource` branché sur Supabase PostgREST/RPC via `postgrest-kt`, moteurs Ktor Android/iOS et fabrique client sans secret commité.
- PR DATA-TEAM-003 `#9` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Repository catalogue branché sur Supabase PostgREST : villes, catégories, liste/recherche de fiches, détail et médias, sans fuite Supabase dans le domaine.
- Tests `commonTest` ajoutés pour DTO/mappers catalogue, pagination, erreurs data et détail de fiche.
- PR DATA-CATALOG-001 `#10` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Socle Auth partagé ajouté : `auth-kt`, `DataAuthRepository`, `SupabaseAuthDataSource`, `SessionManager` Kwabor et mapping domaine sans fuite de tokens.
- Stockage sécurisé de session ajouté côté Android via AndroidX Security Crypto et côté iOS via Keychain/CoreFoundation.
- Factories Android/iOS ajoutées pour créer le repository Auth avec stockage sécurisé plateforme ; permission réseau Android déclarée.
- Tests `commonTest` ajoutés pour session manager, validation auth, mapping session et garde d'activation promoteur côté client.
- Interactions catalogue Like/Favori ajoutées côté Supabase : policies RLS séparées, RPC idempotents authentifiés, trigger interne de maintien `listings.likes_count`.
- Contrats domaine et data ajoutés pour lire l'état viewer, lire un batch d'états, liker/unliker et ajouter/retirer un favori sans exposer Supabase au domaine.
- Fabrique `createAuthenticatedCatalogRepository` ajoutée pour consommer la session auth partagée lors des actions catalogue authentifiées.
- Tests pgTAP ajoutés pour anonymes, isolation utilisateur, fiche non publiée, idempotence, compteur de likes et batch publié.
- Tests `commonTest` ajoutés pour mapping DTO, validation identifiant fiche, batch vide, délégation Like/Favori et absence de session.
- PR DATA-CATALOG-002 `#13` mergée dans `main` avec `quality` et `iOS simulator build` verts.
- Design system Compose complété : tokens spacing/radius/sizing/typo, `PriceTag` compact/plein, badge sponsorisé, états empty/error/offline/loading skeleton et carte catalogue previewable.
- Previews Compose ajoutées pour `PriceTag`, carte catalogue, états transverses, light/dark.
- Socle SwiftUI aligné avec des tokens iOS minimaux et un aperçu de badge sponsorisé dans l'hôte iOS.
- Tests `commonTest` ajoutés pour le formatage `PriceTag` et les tokens de fondation.
- PR FND-006 `#15` mergée dans `main` avec `quality` et `iOS simulator build` verts après recréation propre depuis l'ancienne PR empilée `#14`.
- Repository GitHub rendu public afin de débloquer l'exécution GitHub Actions sans limite privée bloquante immédiate.
- EXPLORE-001 démarrée : écran Explore stateless, modèle d'état lecture seule, presenter partagé alimenté par `CatalogRepository`, onglets/chips sélectionnables, états loading/empty/error/offline et tests `commonTest` ciblés.
- EXPLORE-001A ajoutée : runtime partagé `KwaborRuntimeDependencies`, horloge système, injection Android du `CatalogRepository` réel depuis `local.properties` / propriétés Gradle / variables d'environnement, sans secret commité.
- Bridge iOS préparé pour recevoir `KWABOR_SUPABASE_URL` et `KWABOR_SUPABASE_PUBLISHABLE_KEY` depuis l'environnement du scheme ou Info.plist locale.
- EXPLORE-001B ajoutée côté carte Compose Android : abstraction KMP `ListingCoverImage`, actual Android avec Coil/Ktor, fallback placeholder, textes bornés et état loading réellement assigné avant chargement repository.
- Tests `commonTest` ajoutés pour la création des dépendances runtime sans secret et validations Gradle `:shared:check`, `:androidApp:assembleDebug`, `:shared:assembleSharedDebugXCFramework`, `check` vertes.
- EXPLORE-001C implémentée localement : état viewer Like/Favori chargé par batch, toggles Like/Favori branchés dans Explore, mur souple auth non bloquant, queue offline en mémoire avec mise à jour optimiste, messages i18n FR et tests `commonTest` ciblés.
- Runtime Android ajusté pour utiliser un catalogue Supabase authentifié avec le même `SessionManager` sécurisé, sans exposer `SessionManager` ni Supabase à `androidApp` ou à l'UI.
- PR EXPLORE-001 `#16` mergée dans `main` avec `quality` verte et `iOS simulator build` vert sur GitHub Actions macOS.
- AUTH-001A implémentée sur branche : mur souple Explore relié à une bottom sheet email OTP, création de profil minimal avec acceptations légales, badge session invité/connecté et reprise de l'action Like/Favori après authentification.
- Tests `commonTest` ajoutés pour `AuthPresenter`, vérification OTP + profil dans `DataAuthRepository` et interaction Explore en attente d'authentification.
- PR AUTH-001A `#17` mergée dans `main` avec `quality` verte et `iOS simulator build` vert sur GitHub Actions macOS.
- V1-GOV-001 implémentée sur branche : feuille de route `docs/v1-production-delivery.md`, backlog exhaustif et ADR Room KMP, IA multi-provider, Firebase et FedaPay.
- Protection de `main` activée : passage par PR, conversations résolues, admins inclus, force-push/suppression interdits, checks `quality` et `iOS simulator build` requis.
- PR V1-GOV-001 `#18` mergée dans `main` au commit `d7f0e09`, avec `quality`, pgTAP et `iOS simulator build` verts.
- CI-004 implémentée sur branche : checkout `v7.0.0`, setup-java `v5.5.0` et setup-gradle `v6.2.0`, tous compatibles Node 24.
- PR CI-004 `#19` mergée dans `main` au commit `c05c5fb`, avec `quality`, pgTAP et `iOS simulator build` verts sans avertissement Node 20.
- ARCH-001 implémentée sur branche : modules Koin isolés `core`, `catalog`, `auth` et `organization`, graphe strict sans override et client Supabase partagé par racine.
- Android initialise le graphe au niveau `Application` puis injecte explicitement les contrats dans Compose ; iOS conserve une racine Koin dédiée et fournit son bridge à SwiftUI.
- Les factories manuelles `KwaborRuntimeDependencies` et repositories ont été supprimées ; aucun appel Koin ni type Supabase ne fuit dans l'UI ou le domaine.
- Tests du graphe ajoutés pour configuration absente, URL non HTTPS, modules publics et Auth avec session sécurisée.
- PR ARCH-001 `#20` mergée dans `main` au commit `a61c356`, avec `quality`, pgTAP et `iOS simulator build` verts.
- CI-005 implémentée sur branche : la gate `:shared:detekt` dépend désormais explicitement des analyses typées `commonMain`, Android et iOS ainsi que d'une analyse dédiée de `commonTest`.
- Les 97 alertes préexistantes révélées ont été traitées sans baseline, `@Suppress` ni affaiblissement de seuil : contrats repositories scindés par responsabilité, causes d'erreurs data conservées, valeurs de validation regroupées et composables/presenters découpés.
- La convention Compose officielle est déclarée via `FunctionNaming.ignoreAnnotated = ["Composable"]`; les actions UI sont regroupées par feature et la route Explore utilise un contrôleur dédié.
- Validation locale CI-005 : `check` vert, 109 tests Android host verts, Detekt `commonMain`/Android/iOS/`commonTest` vert et compilation Kotlin iOS simulateur verte.
- PR CI-005 `#21` mergée dans `main` au commit `fad25f3`, avec `quality`, pgTAP et `iOS simulator build` verts.
- ARCH-002 implémentée sur branche : shell, navigation racine, design system, composants, écrans et previews Compose déplacés de `shared` vers `androidApp`.
- Compose et Coil ont été retirés du module `shared` ; l'image catalogue Android est désormais un composable Android normal et l'ancien placeholder Compose iOS a été supprimé.
- Les tests des tokens et du formatage de prix ont été transférés vers les tests JVM de `androidApp` ; la gate Detekt Android couvre explicitement aussi ces tests.
- Validation locale ARCH-002 : `check`, APK debug et compilation Kotlin iOS simulateur verts ; 100 tests partagés et neuf tests JVM Android sans échec ; Detekt application/tests et KMP vert.
- PR ARCH-002 `#22` mergée dans `main` au commit `6c0464f`, avec `quality`/pgTAP verts en 3 min 32 s et `iOS simulator build` vert en 4 min 56 s.
- ARCH-003 implémentée sur branche : `ExploreViewModel` et `AuthViewModel` Android Lifecycle par feature, sans base générique, exposent des `StateFlow` en lecture seule et des `Intent`/`Effect` scellés.
- `KwaborApp` ne construit plus de presenter et ne détient plus l'état Auth/Explore ; la route observe les flux avec le lifecycle et coordonne les effets auth/reprise d'interaction.
- Les presenters Auth/Explore sont fournis par des modules Koin dédiés ; chaque ViewModel reçoit un scope principal créé par la composition root, remplacé par un `TestScope` dans les tests.
- Un état utilisateur sûr remplace le shell vide lorsque la configuration distante obligatoire est absente, sans exposer de détail technique.
- Tests ARCH-003 ajoutés pour sélection d'onglet, auth requise, poursuite invité, reprise Like authentifiée, OTP, conservation de saisie pendant la restauration de session et effet d'authentification.
- Validation locale ARCH-003 : `check`, APK debug et compilation Kotlin iOS simulateur verts en 4 min 53 s ; 100 tests partagés et 16 tests JVM Android sans échec ; Detekt application/tests et KMP vert.
- PR ARCH-003 `#23` mergée dans `main` au commit `6cff9d1`, avec `quality`/pgTAP verts en 3 min 24 s et `iOS simulator build` vert en 5 min 23 s.
- NAV-001 implémentée sur branche : Android utilise Navigation Compose 2.9.8 avec cinq routes sérialisées, restauration des back stacks et mur souple invité unifié ; `MainActivity` ingère les intents `singleTop` sans rejouer un lien consommé.
- iOS utilise désormais une `TabView` SwiftUI et un `NavigationStack` par racine ; le schéma `kwabor` est déclaré dans un Info.plist versionné sans valeur sensible.
- Le contrat partagé définit les cinq destinations et valide strictement `kwabor://app/<destination>` ; les liens universels/App Links restent différés jusqu'à disponibilité d'un domaine vérifiable.
- ADR-0015 accepte la navigation native et remplace le shell Compose partagé de l'ADR-0008.
- Tests NAV-001 ajoutés pour toutes les destinations et les rejets scheme/host/chemin/query/fragment ; la restauration de session est explicitement attendue avant de traiter un deep link Android.
- Validation locale NAV-001 : `check`, APK debug et compilation Kotlin iOS simulateur verts en 5 min 17 s ; Detekt application/tests et KMP, lint et `git diff --check` verts.
- PR NAV-001 `#24` mergée dans `main` au commit `8152d0e`, avec `quality`/pgTAP verts en 3 min 29 s après relance d'un conflit de port runner et `iOS simulator build` vert en 6 min 36 s.
- ENV-001A implémentée sur branche : le runtime partagé accepte uniquement `development`, `staging` ou `production`, et les composition roots Android/iOS reçoivent explicitement cet environnement.
- Android injecte le tier via `BuildConfig` et `local.properties`; iOS utilise un `Base.xcconfig` versionné et un `Local.xcconfig` ignoré, avec substitution sûre dans Info.plist.
- Exemples `.env`, `local.properties` et `.xcconfig` ajoutés sans valeur distante ; fichiers Firebase générés et logs CLI exclus de Git.
- Runbook `docs/environment-configuration.md` ajouté avec matrice des variables/secrets, séparation stricte des projets et procédures propriétaire Supabase/Firebase.
- GitHub Environments `staging` et `production` créés : branches protégées seulement ; production exige l'approbation `urbainmorel`, interdit le bypass administrateur ; variable `KWABOR_ENVIRONMENT` renseignée dans chacun.
- Validation ciblée ENV-001A : tests Android host, Detekt commonMain/commonTest, compilation Android et compilation Kotlin iOS simulateur verts en 1 min 56 s.
- Validation globale ENV-001A : `check`, APK debug et compilation Kotlin iOS simulateur verts en 3 min 01 s ; lint, Spotless, Detekt et `git diff --check` verts ; valeur Android `preview` correctement rejetée au build.
- PR ENV-001A `#25` mergée dans `main` au commit `aa74969`, avec `quality`/pgTAP verts en 4 min 17 s et `iOS simulator build` vert en 5 min 47 s.
- ANDROID-REL-001 implémentée sur branche : variants `debug`/`staging`/`release` strictement reliés aux tiers development/staging/production, versionnement injecté et séparation des configurations Supabase par environnement.
- Les variants staging/release activent R8 et le shrink de ressources ; le mapping est conservé. La production refuse tout artefact sans les quatre credentials de la clé d'upload, sans générer de certificat factice.
- Icône adaptive, splash Android 12+ et identité visible par variant ajoutés conformément au design Kwabor ; les assets système utilisent le master opaque `kwabor_icone_app.png` sans détourage ni recoloration, centré à 75 % pour préserver toute sa silhouette dans les zones sûres et le masque circulaire Android. Le wordmark applicatif complet utilise séparément `kwabor_2.png`.
- Workflow manuel `Android release artifact` ajouté : exécution depuis `main`, GitHub Environment ciblé, approbation production, validation de la configuration distante, injection temporaire du keystore, gate `check`, checksum et artefacts bornés.
- Runbook `docs/android-release.md` ajouté avec versionnement, Play App Signing, secrets, commandes et contrôles avant téléversement.
- Validation ciblée ANDROID-REL-001 : APK staging minifié produit en 7 min 27 s, environnement `staging`, version `0.1.0-staging`, label attendu, signature debug vérifiée et mapping R8 présent ; release sans signature et signature partielle correctement rejetées.
- Validation globale ANDROID-REL-001 : `check`, APK debug, APK staging R8 et compilation Kotlin iOS simulateur verts en 10 min ; 100 tests partagés et 16 tests JVM Android sans échec, Detekt/Spotless/lint verts. Une configuration Supabase générique production reste absente du BuildConfig staging et n'alimente que release.
- PR ANDROID-REL-001 `#26` mergée dans `main`, avec `quality`/pgTAP verts en 3 min 46 s et `iOS simulator build` vert en 3 min 04 s.
- IOS-REL-001 implémentée sur branche : configurations Xcode `Debug`, `Staging` et `Release` reliées respectivement aux tiers development/staging/production et aux XCFrameworks KMP debug/release attendus.
- Version, Team ID, profil et paramètres Supabase iOS sont injectés via xcconfig ou build settings ; aucune valeur fournisseur réelle ni certificat n'est versionné.
- Entitlements APNs et Sign in with Apple ajoutés avec valeurs development/production par configuration ; leur validité finale reste contrôlée par l'App ID et le provisioning profile Apple du propriétaire.
- Privacy Manifest initial ajouté comme ressource de cible, sans tracking. IOS-PRIVACY-001A déclare
  désormais l'accès direct de l'hôte à `UserDefaults` avec la raison Apple `CA92.1` ; le réaudit des
  données collectées et des SDK reste obligatoire avant la publication Store.
- Icône iOS 1024 opaque générée depuis `kwabor_icone_app.png` et launch screen natif ink/wordmark ajoutés ; le logo horizontal est une copie exacte de `kwabor_2.png`, affichée en aspect fit sans détourage, recoloration ni redessin.
- Workflow manuel `iOS archive artifact` ajouté : protection `main`, GitHub Environment, version Apple stricte, keychain/profil temporaires, validation équipe/bundle/capacités, archive signée, dSYM, manifest, signature, checksum et nettoyage après échec.
- La CI macOS construit désormais les XCFrameworks debug/release puis les configurations simulateur Debug/Staging/Release sans signature.
- Validation locale IOS-REL-001 : `check`, APK debug et compilation Kotlin iOS simulateur verts en 59 s ; Detekt/Spotless/lint verts. JSON assets, XML/plists, PNG/dimensions/opacité, déterminisme et unicité des objets PBX validés ; workflows conformes au parseur Prettier YAML.
- PR IOS-REL-001 `#27` mergée dans `main` après `quality`/pgTAP et compilation macOS des trois configurations simulateur vertes.
- OBS-001A livrée : Firebase Analytics, Crashlytics, Performance et Remote Config sont intégrés nativement sur Android et iOS, sans imposer de SDK Firebase au domaine partagé.
- La collecte Analytics/Crashlytics/Performance et le fetch Remote Config sont refusés par défaut sur les deux plateformes ; la révocation arrête les nouveaux fetch/listeners et invalide leurs callbacks. Toute future valeur typée doit rester consent-gated et revenir à sa valeur embarquée sûre, indépendamment du cache interne Firebase.
- Le contrat partagé expose le catalogue fermé des événements PRD, des diagnostics et des traces ; le contexte analytique refuse les identifiants non opaques afin d'éviter noms, emails, texte libre et autres PII.
- Android active Google Services uniquement en présence du fichier injecté et les workflows release valident projet/bundle avant tout build ; iOS sélectionne uniquement un plist au bundle ID exact et verrouille Firebase 12.16.0 avec ses dépendances SwiftPM.
- Remote Config reste consenti, natif et fail-safe pour de futurs flags UX sûrs ; aucun modèle, URL, hash, révision ou cache de vidéo d'intro ne fait plus partie de son contrat actif.
- Privacy Manifest, Info.plist, AndroidManifest et runbook `docs/observability.md` documentent les collectes, le consentement, l'injection, la vérification appareil et les responsabilités propriétaire.
- Validation locale OBS-001A : tests communs et Android ciblés, Detekt/Spotless, `check`, lint, APK debug, compilation Kotlin iOS simulateur, chemin Android configuré et `git diff --check` verts ; XML/plists, YAML et verrou SwiftPM validés.
- PR OBS-001A `#28` mergée dans `main` après `quality`/pgTAP verts en 4 min 00 s et compilation macOS des configurations simulateur Debug/Staging/Release verte en 19 min 41 s.
- AUTH-002 implémentée sur branche : intro native Android Compose et iOS SwiftUI avec MP4 portrait H.264 embarqué, silencieux et disponible hors connexion dès la première installation, reduced-motion, passage manuel et poursuite en invité.
- ADR-0021 remplace le canal distant : la révision embarquée initiale `1` est identique sur Android/iOS et toute révision supérieure est lue exactement une fois après installation de la release Store correspondante.
- Le vérificateur impose des MP4 byte-identical, H.264 portrait, silencieux, fast-start et ≤ 3 Mio, des fallbacks identiques, des constantes de révision égales et le couplage strict entre changement d'octets et incrément contre la base Git.
- La navigation invitée native protège les destinations authentifiées par un mur souple sans créer de session persistante ; iOS dispose désormais du flux OTP minimal équivalent pour s'authentifier depuis une destination protégée.
- ADR-0021, `PRD.md`, `DESIGN.md` et les runbooks onboarding/observabilité documentent la révision embarquée, la release Store obligatoire, la non-répétition et le rollback par build correctif. ADR-0016 est conservé comme historique supersédé.
- Validation locale AUTH-002 : validateur média/ffprobe, tests Android et partagés, Spotless, Detekt, lint, `check`, APK debug, contenu MP4 de l'APK, `git diff --check` et 66 tests pgTAP verts.
- La première CI complète de la PR `#29` a passé la vérification média, `quality` et pgTAP en 4 min 35 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release en 16 min 26 s.
- PR AUTH-002 `#29` mergée dans `main` au commit `aac92ba` après une seconde CI verte sur le commit de clôture : `quality`/pgTAP en 5 min 04 s et les trois configurations iOS simulateur en 16 min 02 s.
- AUTH-003 implémentée sur branche : email OTP, mot de passe initial, identité, ville manuelle/GPS local, devise, révisions juridiques, consentements observabilité et primer notifications équivalents Android Compose/iOS SwiftUI, avec reprise des sessions incomplètes et secrets absents des états/logs.
- La finalisation serveur atomique crée le profil, le rôle utilisateur et les trois preuves juridiques ; 28 policies RLS et 8 RPC mutantes refusent désormais toute écriture produit tant que `onboarding_completed_at` est absent, y compris pour une session OTP dotée d'anciens rôles privilégiés.
- Le primer notifications est persistant par installation et repris après un arrêt entre la RPC et l'accueil ; consentements, annulation post-OTP et demande système sont protégés contre les doubles actions et les échecs de persistance locale.
- Le splash système et l'AppIcon utilisent le symbole `kwabor_icone_app.png`. Le lancement applicatif Android/iOS utilise le wordmark officiel `kwabor_2.png` bit-identique, au ratio 3:1 et sans recoloration ; Android respecte le masque circulaire natif avant le raccord Compose, tandis qu'iOS l'affiche dès son storyboard.
- La revue croisée AUTH-003 garantit aussi le primer après une session OTP déjà complète, rend la demande de localisation Android single-flight, masque les secrets des requêtes sociales/promoteur, distingue un OTP expiré et remplace le rectangle GPS par un polygone local du Bénin excluant notamment Lomé et Lagos.
- Validation locale AUTH-003 : 60 tests Android et 129 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug et compilation Kotlin iOS Simulator verts en 8 min 13 s ; vidéo embarquée H.264 portrait silencieuse/faststart validée et 167 assertions pgTAP vertes.
- La CI du commit `8f10fc9` a passé `quality` et pgTAP en 4 min 01 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release sous Xcode 16.4 en 20 min 14 s. Les ponts Swift/KMP de session, d'état et de consentement juridique sont ainsi validés nativement.
- AUTH-004 implémentée sur branche : connexion email puis mot de passe, récupération email → OTP Recovery → nouveau mot de passe, annulation sûre et déconnexion depuis Profil sont disponibles en Android Compose et iOS SwiftUI natif.
- Une session Supabase créée par un OTP Recovery reste explicitement non authentifiée. Les phases de mise à jour et de nettoyage sont persistées, reprises hors ligne et fermées en mode fail-closed après annulation, crash ou échec de suppression locale ; aucun OTP ou mot de passe n'est conservé dans un état UI persistant.
- La vérification d'inscription OTP d'un compte déjà complet ne peut plus contourner la connexion par mot de passe. Android et iOS conservent un marqueur non secret jusqu'à déconnexion confirmée ; iOS neutralise aussi une ancienne session Keychain lors d'une nouvelle installation avant d'autoriser l'accueil.
- Le template Supabase Recovery français utilise uniquement `{{ .Token }}`. Validation locale AUTH-004 après correctif du bridge Swift/Kotlin : 64 tests Android et 155 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug, compilation Kotlin iOS simulateur, intégrité du logo canonique et absence de modification des assets logo/intro verts en 11 min 12 s.
- La première CI AUTH-004 a passé `quality`/pgTAP mais révélé deux comparaisons d'enums Kotlin non résolues par Swift. Le correctif expose des propriétés sémantiques booléennes stables depuis le partagé, avec deux tests de pont dédiés ; aucune comparaison directe aux enums concernés ne subsiste dans Swift.
- La seconde CI du commit `a723a45` a passé `quality`, la vérification du média embarqué et pgTAP en 5 min 21 s, puis les XCFrameworks et les configurations SwiftUI simulateur Debug/Staging/Release sous Xcode 16.4 en 17 min 19 s.
- BRAND-001 implémentée sur branche : wordmark complet centré au lancement Android/iOS et conservé jusqu'à la première frame vidéo, copies raster strictement identiques au master `kwabor_2.png`, AppIcon et splash système toujours issus exclusivement de `kwabor_icone_app.png`. Le vérificateur Python standard-library contrôle hashes, dimensions, modes, ratio, provenance, catalogues/références Android/iOS et est exécuté par la CI. Validation locale : 67 tests Android et 155 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug, intégrité brand/média et `git diff --check` verts en 8 min 21 s ; une installation fraîche sur émulateur Android 11 confirme la séquence pictogramme système → wordmark complet → vidéo sans flash du fallback. La CI du commit `8324d27` a passé `quality` en 4 min 45 s et la compilation SwiftUI/storyboard Debug/Staging/Release en 16 min 50 s.
- AUTH-005 est implémentée dans la PR atomique `#33` : Google natif Android/iOS, Sign in with Apple iOS, reprise de l'onboarding social, activation Promoteur sécurisée, ré-authentification Danger Zone et Edge Function idempotente `account-delete`.
- L'activation Promoteur vérifie l'email confirmé de l'invitation, ne remplace jamais une session existante et borne l'attribution à Promoteur vérifié + Éditeur d'organisation. La fiche est rattachée à l'organisation sans transfert de propriété ni élévation Propriétaire/Gestionnaire/Admin.
- La suppression exige `SUPPRIMER` et une identité fraîche identique au bearer, refuse les conflits de propriété/Storage, révoque les sessions, efface l'utilisateur Auth et conserve un tombstone serveur privé sans PII métier. Une fonction privilégiée réconcilie quotidiennement les opérations interrompues puis purge les tombstones complétés après 30 jours.
- ADR-0017 et les runbooks environnement/onboarding documentent le provisionnement propriétaire OAuth/Supabase/Apple, les frontières de confidentialité et la réconciliation des suppressions interrompues.
- Validation locale AUTH-005 : 112 tests Android et 180 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug et compilation des sources/tests Kotlin iOS Simulator verts ; reset Supabase complet, 240 assertions pgTAP, lint `public`/`app_private`, 19 tests Deno, vérifications brand/média/YAML et `git diff --check` verts. La revue croisée a en plus verrouillé la restauration iOS en échec, la priorité de la suppression de compte sur les callbacks Promoteur, le nettoyage fail-closed des sessions provisoires, la ré-authentification du même utilisateur et la preuve AMR serveur récente.
- Les premiers builds Xcode de la PR `#33` ont détecté l'export incomplet des chaînes AUTH-005 vers Swift, le nom importé du code d'annulation Google Sign-In et un masquage de propriété Promoteur. Les correctifs conservent un pont KMP typé sans texte dupliqué ; le run final `30294633454` du commit `578f8c4` a passé `quality`/pgTAP en 4 min 43 s, puis les XCFrameworks et les configurations iOS simulateur Debug/Staging/Release sous Xcode 16.4 en 19 min 27 s.
- L'audit V1 du 30 juillet 2026 confronte code, PRD/DESIGN, données, tests, CI, distribution et exploitation dans `docs/audits/2026-07-30-v1-production-readiness.md`. L'avancement réel est estimé à 25–30 % du PRD V1 actuel et la préparation production à 15–20 %.
- SEC-001A est implémentée localement sur `codex/sec-001-authorization-guardrails` : onboarding Google/Apple, grants Social/équipes/claims/signalements, RPC de modération, cohérence taxonomique et matrice Guide/Promoteur/Institution sont durcis par migrations forward-only séparées.
- Le hotfix OAuth/ACL ne dépend plus de la validation taxonomique fail-closed. Le runbook `docs/runbooks/security-authorization-preflight.md` impose sauvegarde, audit humain des anciennes lignes d’autorité et validation de la taxonomie avant tout déploiement persistant.
- Validation SEC-001A : 2 migrations appliquées localement, 7 suites et 316 assertions pgTAP vertes, lint sans diagnostic dans `public`/`app_private`, `spotlessCheck`, `detekt`, `check` et `git diff --check` verts. Les commits `f6593d4`/`4b9e3fd`/`12ddba2` sont publiés dans la PR brouillon `#35` ; le run final `30557976298` a passé `quality` puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release.
- ARCH-004 est implémentée dans la PR brouillon empilée `#36` : le contrat et l'implémentation de dispatchers quittent le domaine pour `shared.app`, le binding Koin appartient désormais à la composition root et les consommateurs Android/iOS conservent la même injection déterministe.
- La gate `verifyDomainPurity`, rattachée à `check`, refuse tout fichier domaine dans un source set plateforme et tout import autre que Kotlin ou intra-domain. Un test négatif contrôlé a prouvé les deux refus avant suppression des probes.
- Validation locale ARCH-004 : 180 tests partagés et 112 tests JVM Android sans échec, compilation Kotlin iOS Simulator, `spotlessCheck`, `detekt`, lint, `check`, APK debug et `git diff --check` verts ; deux re-revues indépendantes ne relèvent aucun P0/P1/P2.
- Validation CI ARCH-004 sur le commit `ea856a0` : le run `30564229960` a passé `quality`/pgTAP en 4 min 43 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release en 21 min 59 s.
- STAB-003 est implémentée dans la PR brouillon empilée `#37` : les inventaires Android/iOS et runbooks reflètent les contrats réels, les plugins Firebase Android ne sont appliqués qu'après injection du fichier fournisseur, et les artefacts sensibles ou générés sont ignorés quel que soit leur sous-dossier.
- Le wrapper officiel Gradle 9.4.1 est régénéré et verrouillé par checksums de distribution, JAR et launchers. `tools/verify-repository-integrity.py`, exécuté en CI, refuse les templates incomplets ou préremplis, les propriétés wrapper divergentes et les secrets/artefacts mobiles suivis par Git.
- Validation locale STAB-003 : vérificateurs dépôt/média/marque et `git diff --check` verts, wrapper téléchargé et exécuté depuis un cache vide, `spotlessCheck`, `detekt`, lint, `check`, compilation Kotlin iOS Simulator et 292 tests Android/shared verts en 8 min 04 s. Les APK debug et staging ont été produits sans variable `KWABOR_*` ni configuration Firebase ; deux revues indépendantes finales ne relèvent aucun P0/P1/P2.
- Validation CI STAB-003 sur le commit `c275699` : le run `30573401220` a passé le nouveau contrôle d'intégrité, `quality`/pgTAP en 4 min 55 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release en 19 min 26 s.
- BRAND-002 sépare désormais les assets launcher 108 dp du splash 288 dp. Les cinq canevas 288/432/576/864/1152 px sont générés directement depuis la source de build 1254 px du dépôt avec la géométrie 75 % existante ; cette source, le wordmark, les dérivés launcher et tous les assets iOS restent bit-identiques. Sa confirmation comme master officiel reste une décision propriétaire ouverte.
- Le vérificateur standard-library verrouille hashes, dimensions, silhouette claire dans le cercle sûr et XML actif du wrapper/thème SplashScreen. Deux générations sont idempotentes ; les corruptions Android/iOS, la réintroduction `nodpi` et les détournements XML masqués en commentaire sont refusés.
- Validation locale BRAND-002 : vérificateurs dépôt/média/marque, actionlint/YAML/Bash, `spotlessCheck`, `detekt`, lint, `check`, APK debug hermétique et 292 tests Android/shared verts sans échec. La porte finale Gradle a terminé en 13 min 07 s et trois revues indépendantes ne relèvent aucun P0/P1/P2.
- Le premier run BRAND-002 `30585538585` a correctement bloqué `quality` : les trois APIs ont construit et lancé l'app configurée, mais l'assertion exigeait à tort que l'intro soit encore la surface active après dix secondes alors que le landing onboarding était déjà affiché. La vidéo partielle API 30 confirme le raccord jusqu'à l'intro ; les fenêtres API 31/36 sont trop courtes pour une conclusion perceptuelle. Le harness enregistre désormais quinze secondes et accepte explicitement intro ou landing, sans relâcher le refus de l'écran de configuration.
- La PR brouillon empilée `#38` contient l'implémentation historique REMOTE-INTRO-001. Cette architecture est supersédée par ADR-0021 et doit être neutralisée par INTRO-STORE-001 avant toute release V1 ; aucun média distant ne constitue désormais l'état cible.
- Le run final `30654047648` du commit `4606309` a passé les sept checks, dont les trois jobs API 30/31/36 et les configurations iOS simulateur. Ses neuf cellules sont techniquement intègres, mais la revue perceptuelle rejette la matrice : wordmark absent de la preuve API30/xhdpi et API31/xxxhdpi, monogramme absent de la preuve API30/mdpi et API30/xxxhdpi. Il s'agit de trous de preuve, pas d'une preuve que l'app saute ces surfaces.
- Le run de clôture `30661731938` du commit `94a31d5` passe les sept checks, dont `quality`, API 30/31/36 et les configurations iOS simulateur. Les neuf cellules sont techniquement intègres ; leur preuve continue de premier lancement montre perceptuellement HOME → monogramme → wordmark complet → intro. Le harnais conserve sa tolérance de cadence à 4,5 secondes, borne à trois essais les seuls transitoires `75/124` et n'accepte `UNKNOWN (0)` qu'avec le statut réussi, l'avertissement AOSP exact et les contrôles PID/activité/UI/flux aval.
- CATALOG-002 est implémentée sur `codex/catalog-002-paginated-summary` : liste et recherche utilisent un unique RPC `security invoker`, un DTO carte plat et un curseur opaque lié aux filtres. La couverture est sélectionnée par jointure latérale déterministe ; les appels média par fiche et le faux `nextOffset` terminal sont supprimés.
- Le RPC reste publié uniquement même pour un propriétaire authentifié, borne ses grants à `anon`/`authenticated` et expose l'état sponsorisé calculé avec le même snapshot serveur que le classement. Le badge jaune ne dépend donc plus de l'horloge appareil pour ce parcours.
- ADR-0018 trace le choix RPC/keyset et la conservation de la pagination offset générique hors catalogue. CATALOG-002 n'ajoute volontairement aucun chargement suivant visible : la consommation du curseur, le refresh et les tris métier appartiennent à EXPLORE-002.
- Validation locale CATALOG-002 : migration appliquée, 371 assertions pgTAP sur huit fichiers, lint Supabase sans erreur, tests shared/Android, Spotless, Detekt, lint Android, `check`, APK debug et compilation Kotlin iOS Simulator verts. Le plan interne anon mesuré sur le seed exécute la requête en 2,688 ms/27 buffers ; aucun réglage JIT ni index de ranking non prouvé n'a été ajouté.
- La PR brouillon CATALOG-002 `#39` est publiée au-dessus de `#38`. Le run `30692610347` a passé `quality` en 5 min 45 s puis les XCFrameworks et les configurations iOS simulateur Debug/Staging/Release en 16 min 30 s.
- OFFLINE-001 est implémentée sur `codex/offline-001-room-foundation`, empilée sur CATALOG-002 : Room KMP v1 normalise snapshots Explore, fiches canoniques et positions sponsorisées, avec transactions, clés étrangères, éviction ciblée des corruptions, maximum de 50 fiches par snapshot et rétention bornée aux 64 snapshots les plus récents.
- DataStore KMP conserve uniquement la ville Explore, la locale et la devise d'affichage. Les erreurs I/O deviennent `LocalStorageUnavailable`, les valeurs inconnues retombent sur des défauts sûrs et aucune session, consentement, outbox ni donnée synchronisable n'y est placée.
- Room, DataStore, leurs builders et le scope DataStore sont injectés par Koin et créés seulement au premier accès. La base est fermée et le scope annulé avec l'unique composition root du processus ; les factories de builder/storage ne sont pas des ressources fermables. Le schéma exporté v1 est versionné ; `check` valide son historique et refuse en CI toute dérive ou tout JSON non suivi.
- La compatibilité est figée à Room `2.8.4`, SQLite bundled `2.6.2`, DataStore `1.2.1` et KSP `2.3.10` tant que `iosX64` reste active. Room 3/SQLite 2.7 ne sont pas adoptés car ils ne publient plus cette variante.
- Validation locale finale OFFLINE-001 : 218 tests shared et 160 tests Android verts, dont huit scénarios DAO Room réels sous Robolectric, réouverture/corruption DataStore et paresse Koin. `check` couvre Spotless, Detekt, lint, pureté du domaine et schéma Room ; les APK debug et staging minifié/R8 sont assemblés, et KSP/Kotlin compile pour `iosX64`, `iosArm64` et `iosSimulatorArm64`.
- La PR brouillon OFFLINE-001 `#40` est publiée au-dessus de CATALOG-002 `#39`. Le run `30705934250` a passé `quality` en 5 min 18 s et `iOS simulator build` en 20 min 35 s ; `launch_evidence` est correctement ignoré puisque cette tranche ne modifie ni vidéo ni surface de lancement.
- EXPLORE-002A est implémentée sur `codex/explore-002-android`, empilée sur OFFLINE-001 : cache Room rendu avant revalidation, refresh non destructif, pagination par curseur avec déduplication et préfixe persistant sûr de 40 fiches, puis erreurs initial/refresh/append distinctes.
- Room v2 persiste désormais les villes et catégories autoritatives avec AutoMigration 1→2. ADR-0019 fixe la clé canonique du mur, le single-flight supervisé, l'interdiction d'append depuis un snapshot offline et l'autorité serveur de `is_sponsored_placement`.
- Android expose une grille adaptative 2/3 colonnes, pull-to-refresh, pagination proche de la fin et sélection de ville persistée. Le GPS approximatif n'est demandé qu'après action explicite et choisit la ville connue la plus proche sans stocker les coordonnées.
- Le watermark Room couvre murs, référentiels et contenu canonique des fiches : un recul d'horloge après redémarrage ne bloque plus les écritures, les rejets obsolètes sont explicites et une éviction de corruption ne peut pas supprimer un remplacement concurrent sain. Chaque append conserve en plus la fraîcheur propre aux fiches héritées et aux référentiels, sans écraser un contenu canonique plus récent issu d'une autre clé. Les coordonnées `BJ` réseau/Room sont validées par le polygone du Bénin.
- Les changements de compte ou déconnexions invalident les interactions Explore en vol, purgent les états privés like/favori et rechargent le nouveau viewer. Le premier rendu est synchroniquement en chargement ; les états asynchrones sont annoncés par une région live TalkBack unique et les skeletons restent hors sémantique.
- Validation locale finale EXPLORE-002A : 288 tests shared et 171 tests Android sans échec. Le gate `check` couvre Spotless, Detekt, lint, pureté du domaine et schéma Room ; les APK debug (41,77 Mo) et staging minifié/R8 (13,74 Mo) sont assemblés, et Kotlin compile pour `iosX64`, `iosArm64` et `iosSimulatorArm64`. Le run complet est vert en 30 min 51 s ; les scénarios couvrent notamment migration Room 1→2, recul d'horloge, fraîcheur multi-clé, récupération atomique, single-flight annulé, sessions viewer et accessibilité.
- La PR brouillon EXPLORE-002A `#41` est publiée au-dessus d'OFFLINE-001 `#40`. Le run GitHub Actions `30723036248` est entièrement vert : `quality` en 6 min 39 s, build simulateur iOS en 20 min 25 s, preuves de lancement Android API 30/31/36 et gate agrégé validés.
- EXPLORE-002B1 ajoute la fondation relationnelle `event_details` sans modifier le RPC catalogue : dates `timestamptz`, lieu rattaché ou adresse/GPS, organisateur, billetterie, capacité, enum fermée, indexes ciblés, grants Data API explicites et RLS par rôle.
- ADR-0020 verrouille les invariants parent/enfant et la borne d'écriture actuelle : onboarding terminé, gestionnaire autorisé et fiche `brouillon`/`en_attente`, sauf Admin vérifié pour l'insertion/mise à jour. Un événement ne peut entrer en revue ou être publié sans détails ni avec un lieu non publié ; ceux d'un parent en attente/publié ne peuvent être supprimés directement, même par Admin, et leur lieu ne peut être dépublié. La migration échoue si des événements actifs historiques sont incomplets et les verrous parent/lieu sérialisent soumission, suppression, localisation et conversion concurrentes. Les gardes trigger privilégiées restent dans `app_private`, avec `search_path` vide, contrôle explicite de l'acteur et exécution publique révoquée.
- Validation locale EXPLORE-002B1 : reset Supabase complet, 57 assertions événementielles et 428 assertions pgTAP standard vertes sur neuf fichiers. Le harnais multi-connexion, retiré de la suite distante et borné par un runner localhost explicite, ajoute 12 assertions concurrentes vertes. La couverture prouve notamment la transition vers modération, la suppression Admin, les courses parent/enfant/lieu, la confidentialité et la publication obligatoire des lieux actifs, l'intégrité privilégiée, le cascade parent, HTTPS, capacité, normalisation et localisation. Le lint `public`/`app_private`, la requête directe du seed, l'historique local des migrations, l'intégrité du dépôt et la porte Gradle `spotlessCheck detekt check` sont verts. Le seul projet Supabase visible par le compte connecté n'est pas un environnement Kwabor, donc ses advisors ne constituent pas une preuve de ce schéma local.
- La PR brouillon EXPLORE-002B1 `#42` est publiée au-dessus d'EXPLORE-002A `#41`. Son run `30729830885` est entièrement vert ; sa revue humaine reste requise avant fusion.
- INTRO-STORE-001 retire le canal média distant Android/iOS/shared, conserve Remote Config générique, migre l'ancien état vers une baseline fixe `1` et verrouille en CI l'égalité des assets ainsi que le couplage octets/révision. La validation locale complète et deux revues indépendantes sont vertes.
- La PR brouillon INTRO-STORE-001 `#43` est publiée au-dessus d'EXPLORE-002B1 `#42` ; le run GitHub Actions `30733200076` est entièrement vert.
- EXPLORE-IOS-001 est implémentée localement sur `codex/explore-ios-001-parity`, empilée sur INTRO-STORE-001 : le runtime Kotlin commun porte désormais les intents, l'état, les effets, la concurrence et le cycle de vie, tandis que les adaptateurs Android et iOS restent minces.
- L'écran SwiftUI natif expose la grille adaptative, les états chargement/vide/offline/erreur, le refresh, la pagination, la ville persistée/GPS approximatif, les interactions Like/Favori avec soft wall d'authentification et un pipeline image HTTPS borné/dédupliqué/downsamplé. Recherche, filtres, assistant et navigation détail ne sont pas affichés tant que leurs contrats V1 ne sont pas livrés.
- La validation locale EXPLORE-IOS-001 passe les vérificateurs dépôt/média/marque et la porte `spotlessCheck detekt check` ; les rapports courants comptent 298 tests shared et 142 tests Android sans échec. Trois revues indépendantes ont fait corriger les courses de sélection, les limites/annulations du pipeline image, Dynamic Type, les bandeaux, le formatage XOF partagé et le timeout simulateur, puis ne relèvent plus aucun P0/P1/P2. Le contrôleur iOS et ses politiques Swift sont couverts ; le smoke test macOS rouvre réellement Room et DataStore via des chemins temporaires.
- La PR brouillon EXPLORE-IOS-001 `#44` est publiée au-dessus d'INTRO-STORE-001 `#43`. Les premiers runs ont exposé puis fait corriger l'isolation Swift 6 des providers, l'import fragile d'un cas `ExploreTab` et un timeout transitoire de capture HOME Android. Le run exact-head `30741677132` est entièrement vert : `quality`, Supabase, smoke test iOS, XCFrameworks, configurations Xcode simulateur Debug/Staging/Release et preuves Android API 30/31/36.
- DETAIL-001A introduit un read model atomique `get_catalog_detail_v1` en `security invoker`, limité aux fiches publiées et versionné par `schema_version`. Les six variantes fermées, médias officiels, horaires, services, chambres, billetterie, métriques et statut de claim sont projetés vers des DTO séparés puis un domaine Kotlin pur.
- La migration DETAIL-001A verrouille les invariants parent/enfant, les collections ordonnées, les droits Data API par colonne et le même sous-ensemble URL/temps/Unicode que le mapper mobile. Deux revues SQL indépendantes ne relèvent aucun P0/P1/P2 ; les plans statiques comptent 202 assertions détail et 57 assertions curseur.
- Android ne montre plus Recherche, filtres, assistant ou FAB factices. Les images Explore passent par une politique HTTPS injectée et fail-closed, sans dépendance Supabase dans l'UI ; DETAIL-001B livre l'ouverture de fiche réelle sur Android et DETAIL-IOS-001 l'ouverture native SwiftUI depuis Explore. Les actions métier réelles restent à livrer.
- La porte locale DETAIL-001A `spotlessCheck detekt check` est verte en 13 min 24 s : 311 tests shared et 147 tests Android sans échec, lint, pureté du domaine, schémas Room et compilations Kotlin iOS sous Windows inclus. Les vérificateurs dépôt, onboarding Store-only et marque sont également verts.
- La PR brouillon DETAIL-001A `#45` est publiée au-dessus de `#44`. Le run exact-head `30759824206` est entièrement vert : Gradle, Android API 30/31/36, iOS, 632 assertions PostgreSQL standard et les 12 assertions multi-connexion. Les runs intermédiaires ont permis de borner les ACL du rôle `dblink`, d'exercer réellement les validateurs `service_role` et PostGIS, puis d'aligner les fixtures historiques sur les invariants de détail en limitant les rôles runtime aux validateurs ciblés et sans leur transférer les ACL supplémentaires du harnais local.
- DETAIL-001B connecte Explore Android à un `DetailSheet` Compose global : hero et galerie d'images officielles, six variantes typées, métriques, description extensible, prix XOF, horaires, services, localisation textuelle et états chargement/introuvable/offline/erreur. Les seules actions exposées sont celles réellement livrées : fermer, réessayer, choisir une image et développer la description.
- Le runtime partagé annule et ignore les réponses obsolètes, conserve la source seulement pendant l'ouverture et recalcule chaque minute les statuts Ouvert/Fermé et Événement terminé sans nouvel appel réseau. Les lieux liés des événements sont conservés et la borne de fin est inclusive.
- Les libellés courts du read model sont désormais bornés de façon identique en SQL et Kotlin : tags `10 × 24`, tableaux typés `20 × 80`, et au plus 20 chambres ou paliers de 80 caractères Unicode, sans contrôles. Les projections dédupliquent les libellés traduits. Le plan détail compte maintenant 211 assertions et son exécution Supabase est validée dans la CI exacte de la PR empilée.
- La porte locale complète DETAIL-001B est verte en 9 min 56 s : `spotlessCheck`, `detekt`, `check`, lint, APK Android, compilations iOS, 330 tests shared et 156 tests Android sans échec. Les vérificateurs dépôt, onboarding Store-only et marque sont verts. Trois revues indépendantes ont fait corriger robustesse, temporalité, petite hauteur, TalkBack, Unicode, contraste, clés Compose et couleurs métier ; leurs passes finales ne relèvent aucun P0/P1/P2.
- La PR brouillon DETAIL-001B `#46` est publiée au-dessus de DETAIL-001A `#45`. Le run exact-head `30775732082` est entièrement vert : Gradle, Android API 30/31/36, iOS, 641 assertions PostgreSQL standard et 12 assertions concurrentes sur la stack isolée.
- DETAIL-IOS-001 livre une sheet SwiftUI globale et adaptative ouverte depuis Explore : six variantes typées, états chargement/introuvable/offline/erreur, galerie HTTPS bornée, description, prix XOF, horaires, services, localisation, Dynamic Type et VoiceOver. Aucun CTA factice n'est exposé.
- La porte locale DETAIL-IOS-001 passe `check`, Spotless, Detekt, lint, pureté du domaine, schémas Room, compilations Kotlin/Native iOS et Android, avec 330 tests shared et 156 tests Android sans échec. Trois revues indépendantes ne relèvent plus aucun P0/P1/P2.
- La PR brouillon DETAIL-IOS-001 `#47` est publiée au-dessus de DETAIL-001B `#46`. Son run exact-head `30780564021` est entièrement vert : qualité Gradle et Supabase, preuves Android API 30/31/36, tests Swift, runtime iOS, XCFrameworks et configurations Xcode simulateur Debug/Staging/Release.
- ACTIONS-001A livre sur Android et iOS les actions externes réellement disponibles dans le détail :
  itinéraire, téléphone, WhatsApp, site, email, menu et billetterie. Les événements terminés sont
  explicitement signalés et leur billetterie est désactivée. La PR brouillon empilée `#48` porte le
  commit `300ff9b` ; Android API 30/31 et iOS sont verts. API 36 lance bien l'application mais la
  capture brute du harnais a échoué deux fois, sans preuve d'une régression applicative.
- GUIDE-001B est implémentée localement sur `codex/actions-001b-guide-discovery`, empilée sur
  ACTIONS-001A. Un contrat public versionné et publié-only fournit les référentiels et guides par
  destination couverte, langue et spécialité, avec pagination opaque et ordre organique stable.
  Les tableaux historiques restent la surface d'écriture de transition ; des synchroniseurs privés,
  contraintes différées, ACL/RLS et gardes de référentiels empêchent toute divergence.
- Android Compose et iOS SwiftUI exposent « Trouver un guide » comme écran enfant de l'Accueil,
  sans sixième destination racine. Les écrans couvrent chargement, erreur, vide, refresh, pagination,
  filtres, cartes photo-first, prix indicatif XOF, accessibilité et ouverture du détail catalogue.
  Le premier appel réseau ne part qu'à l'ouverture explicite de l'écran.
- Validation locale GUIDE-001B : `spotlessCheck`, `detekt` et `check` verts en 15 min 14 s, Android
  lint/pureté du domaine/compilations Kotlin iOS verts, 369 tests shared et 178 tests Android sans
  échec. La migration et son plan `77/77` passent `pglast`; les vérificateurs dépôt, marque et intro
  Store-only sont verts. Les revues finales shared, Android, iOS et SQL ne relèvent plus de P1/P2.
- ACTIONS-001C1 est implémentée localement sur `codex/actions-001c-detail-deeplink`, empilée sur
  GUIDE-001B : la route interne stricte `kwabor://listing/<uuid>` ouvre une fiche depuis Android ou
  iOS sans être exposée comme lien de partage public. Le domaine HTTPS, les App Links/Universal Links
  et le signalement restent explicitement hors de ce sous-lot.
- Android conserve le dernier lien valide dans un `SavedStateHandle`, coalesce un doublon en attente,
  protège l'acquittement par identifiant et restaure la demande après recréation. Android et iOS
  attendent l'intro, la restauration et le choix E3 explicite, puis ordonnent Accueil → ouverture →
  acquittement ; une déconnexion ou suppression de compte invalide immédiatement la demande.
- Validation locale ACTIONS-001C1 : `check`, Spotless, Detekt, lint, pureté du domaine, schémas Room,
  APK debug et compilation Kotlin iOS Simulator verts en 12 min 31 s ; 379 tests shared et 190 tests
  Android sans échec. L'APK contient l'hôte `listing`, les vérificateurs dépôt et intro Store-only sont
  verts, et deux contre-revues ne relèvent plus de P1/P2.
- Preuve Android installée ACTIONS-001C1 : l'APK `0.1.0-debug` est installé sur l'AVD API 30,
  `cmd package resolve-activity` associe la route valide à `com.kwabor.android/.MainActivity`, le
  lancement à froid crée et reprend cette activité, puis un second UUID est livré à chaud au même
  processus. Aucun crash ni ANR KWABOR n'est observé ; la preuve visuelle de la fiche connectée reste
  hors de portée sans configuration Supabase locale et sur cet AVD dont les processus système
  `Pixel Launcher`/`Interface` déclenchent des ANR.
- DOC-001 livre un système documentaire vérifié : `README.md` sert de porte d'entrée courte,
  `docs/index.md` route vers les sources canoniques, et les guides setup, architecture, données,
  tests, environnements, déploiement et contribution distinguent état actuel, cible et dépendances
  propriétaire. Les commandes, chemins, liens locaux et règles mobile-only/vidéo Store-only ont été
  contrôlés contre le dépôt ; aucun code applicatif, workflow, secret ou environnement distant n'a
  été modifié.
- SETTINGS-001A sépare désormais Profil et Paramètres sur Android Compose et iOS SwiftUI. Profil
  reste minimal ; Paramètres affiche l'adresse e-mail, la méthode de connexion et une Danger Zone
  réelle regroupant déconnexion et suppression de compte avec confirmation et réauthentification.
- Les libellés et fallbacks sont partagés en Kotlin. Une fin de session purge les piles Android
  sauvegardées et recrée la pile Profil iOS lorsque l'identité change, afin d'éviter de restaurer
  l'état ou les données d'un compte précédent.
- Validation locale SETTINGS-001A : tests shared/Android, compilation Android et Kotlin/Native iOS,
  Spotless, Detekt, lint, pureté du domaine, schémas Room, `check` et APK debug verts. Les deux
  contre-revues finales Android/iOS ne relèvent plus aucun P1/P2.
- OPS-001A livre un runbook Auth/session/suppression relié aux guides d'environnement et de
  déploiement. Il couvre les états réellement implémentés, les diagnostics PostgreSQL en lecture
  seule, le seuil cron de 26 heures, les limites de rétention et les mutations interdites.
- Deux revues indépendantes ont corrigé la reprise d'un tombstone `prepared`, les capacités
  organisation/Storage non livrées, la session conservée après un échec de lecture profil et le
  nettoyage Keychain du premier lancement iOS.
- SEC-001F est implémentée localement : chaque tentative utilise un client Supabase Auth/Functions
  éphémère, isolé par `MemorySessionManager`, sans sauvegarde, auto-refresh ni callbacks de cycle de
  vie, avec `LogLevel.NONE`.
  Mot de passe, ID token et nonce vont uniquement à Supabase Auth ; le body `account-delete` contient
  exactement `idempotency_key`, puis la session temporaire est nettoyée dans un contexte non annulable.
- L'Edge Function exige cumulativement `userClaims.id = jwtClaims.sub`, un `session_id` UUID, une AMR
  finale `password`/`oauth` vieille d'au plus 300 secondes avec 30 secondes de tolérance future, et le
  même utilisateur retourné par `getUser()`. La première mutation passe par le RPC privilégié atomique
  `prepare_account_deletion_with_session`, qui verrouille la session Auth vivante jusqu'au commit.
- Un tombstone `prepared` avec utilisateur Auth présent est désormais reprenable après redémarrage :
  le profil est réduit à une sentinelle pseudonymisée, privée et non modifiable, puis la reconnexion au
  même compte permet une nouvelle ré-authentification éphémère depuis la Danger Zone. Si l'utilisateur
  Auth a déjà disparu, seule la réconciliation serveur idempotente peut marquer `completed` ; aucune
  suppression DBA n'est autorisée.
- Validation locale SEC-001F : 389 tests partagés Android host et 196 tests Android app sans échec,
  compilation des tests Kotlin/Native iOS X64, `spotlessCheck`, `detekt` et `check` verts ; format/check
  Deno et 20/20 tests Edge verts ; reset Supabase, lint `public`/`app_private` et 753 assertions pgTAP
  verts. Ce reset a aussi intégré les correctifs SQL
  préexistants de GUIDE-001B (transaction de migration, alias historiques et assertions typées).
- STAB-002A retire les formulations internes visibles sur le nettoyage de session et l'annulation
  d'invitation Android. Les consentements partagés décrivent désormais les statistiques, pannes,
  lenteurs et réglages distants sans revendiquer une anonymisation non prouvée ; Android et iOS
  indiquent tous deux que ces choix sont facultatifs, désactivés par défaut et modifiables.
- Validation locale STAB-002A : tests shared et Android sans échec, traitement des ressources Android,
  compilation des tests Kotlin/Native iOS X64, `spotlessCheck`, `detekt`, `check`, lint et APK debug
  verts. Le build SwiftUI/Xcode natif reste à exécuter sur macOS avant fusion.
- IOS-PRIVACY-001A déclare dans le manifest embarqué l'accès direct à `UserDefaults` avec la raison
  Apple `CA92.1`. Le vérificateur d'intégrité impose exactement cette déclaration auditée et le runbook
  ne présente plus le manifest actuel comme une preuve Store complète.
- Validation locale IOS-PRIVACY-001A : syntaxe Python, parsing plist, intégrité du dépôt, contrat vidéo
  Store-only, révision Android/iOS et assets de marque verts. Le Privacy Report Xcode reste à générer
  sur macOS dans IOS-PRIVACY-001B.
- IOS-PRIVACY-001B1 inventorie les traitements de l'hôte SwiftUI, du framework KMP et des SDK iOS.
  Le manifest hôte déclare désormais nom, e-mail, identifiant utilisateur, ville approximative et
  interactions produit comme liés au compte ; le vérificateur d'intégrité verrouille exactement ce
  socle sans tracking. L'audit sépare les faits locaux des collectes SDK et décisions fournisseur qui
  exigent l'archive Release et le Privacy Report Xcode.
- SETTINGS-001B rend les trois consentements observabilité consultables et révocables depuis les
  Paramètres Android et iOS. Les deux clients coupent les capacités avant de persister ; un échec
  laisse donc l'observabilité désactivée et affiche une erreur lisible avec retry. Android retire
  `FirebaseInitProvider`, construit son backend sans initialiser Firebase et ne le configure qu'après
  liaison d'un compte consentant ou pour reprendre une maintenance durable. Ses écritures synchrones
  en deux phases utilisent des retries bornés et restaurent explicitement l'historique antérieur sous
  l'état fail-closed si le choix final échoue. Le bouton « Réessayer » rejoue le choix seulement pour
  son compte et reste visible si le nouvel essai échoue ; un changement de session transforme une
  mise à jour abandonnée en révocation sûre et prioritaire. Purges Crashlytics et suppressions FID portent un
  état/requestId durable ; un rebind, un échec réseau ou un callback obsolète ne peut pas réactiver ni
  acquitter l'ancien état. Si le stockage refuse toutes les écritures synchrones, l'opération échoue
  honnêtement et le runtime courant reste fermé, sans prétendre garantir le prochain processus.
  Crashlytics reste en mode manuel et Performance automatique reste désactivé. iOS diffère aussi le démarrage
  Firebase jusqu'à la validation du compte et stocke le choix dans le Keychain avec une empreinte du
  propriétaire. Une session momentanément absente suspend les capacités sans effacer le choix ; une
  déconnexion, une annulation ou une suppression le révoque pour éviter tout héritage inter-compte.
  Crashlytics iOS reste en envoi manuel : seul un consentement diagnostics restauré pour le même
  compte envoie les rapports au lancement suivant et seulement sans purge attendue. Nouvel accord et
  révocation utilisent un ordre de persistance crash-safe différent ; le check unique fournit toujours
  l'action de suppression et toute seconde purge du même processus attend le prochain lancement. Cette
  attente bloque seulement les diagnostics, tandis qu'une transaction Firebase Installations bloque
  toutes les collectes, conserve l'intention finale, la réconcilie avant l'appel réseau et empêche un
  ancien callback d'acquitter une demande plus récente. Un override absent/corrompu déclenche purge et
  suppression FID ; si Firebase est déjà configuré, `awaitingRestart` est persisté atomiquement après
  coupure des SDK. L'instrumentation Performance automatique iOS reste désactivée avant configuration
  et au runtime dans la lignée supportée.
- Android retire aussi `AD_ID`, les deux permissions AdServices, Install Referrer et la bibliothèque
  AdServices. Une tâche Gradle rattachée à `check` analyse les manifestes fusionnés debug, staging et
  release, en plus du contrôle Python des sources et variantes.
- Validation locale SETTINGS-001B : 39 tests Android d'observabilité, 119 tests
  structurels/adversariaux du vérificateur d'intégrité, 232 tests Android app et 390 tests shared sans
  échec. La gate `spotlessCheck detekt check :androidApp:assembleDebug` est verte en 8 min 26 s ; elle
  inclut lint, compilations Kotlin iOS disponibles sous Windows et les manifestes fusionnés debug,
  staging et release sans `FirebaseInitProvider`, permission d'attribution ni AdServices, avec six
  defaults de collecte exactement à `false`. L'APK debug de 44 270 529 octets a été produit avec le
  SHA-256 `495ba46e4b641da19e50359a4fd95517e5a14d4a5fe2de99db5cb6e14b90fd2f`. Les
  sources critiques Android/iOS et toute la configuration Gradle sont verrouillées par empreintes
  d'audit ; les accès Firebase hors adaptateur privé et les dépendances Firebase hors `androidApp`
  sont interdits. Swift/Xcode reste indisponible sur
  ce poste Windows.
- AUTH-UX-001 est terminée localement sur la ligne avancée : intro interactive, quatre écrans email
  maximum, profil final compact, softwall contextuelle avec reprise unique de l'action protégée et
  aucune permission ni nouveau consentement avant l'accueil. Le DTO/RPC/RLS Supabase reste inchangé
  et l'ADR-0026 trace la décision sans réintroduire de média distant.
- Validation locale AUTH-UX-001 : 399 tests shared et 224 tests Android sans échec ;
  `spotlessCheck`, `detekt`, `check`, lint, compilation Kotlin/Native iOS disponible sous Windows et
  `:androidApp:assembleDebug` sont verts. L'APK debug de 44 235 992 octets porte le SHA-256
  `E7B8B3E4F495F625AEBF31668ED59BE8110C961D6834C4580C8FB076C3F777A9`. La compilation SwiftUI/Xcode
  native reste à exécuter sur macOS avant publication de la branche.
- OFFLINE-002 est implémentée localement : Room Android vit dans `noBackupFilesDir` en plus des
  exclusions cloud/D2D ; Room iOS utilise un dossier dédié non sauvegardé avec protection
  `CompleteUntilFirstUserAuthentication`. Les deux plateformes invalident l’ancien cache et passent
  en mémoire si leur politique disque ne peut pas être appliquée. ADR-0027 trace la séparation entre
  copie locale et autorité serveur durable sans présenter les règles plateforme comme une preuve
  cryptographique absolue.
- Validation locale OFFLINE-002 : 146 tests structurels/adversariaux et 402 tests Android hôte verts,
  manifestes fusionnés et ressources de sauvegarde empaquetées debug/staging/release conformes,
  compilations production et tests Kotlin/Native iOS X64 verts. La gate finale de 130 tâches
  (`spotlessCheck`, `detekt`, `check`, lint et APK debug) est verte en 4 min 56 s. Sur émulateur
  Android 11/API 30, l’APK cible 36 ne porte pas le flag runtime `ALLOW_BACKUP` et le transport local
  `bmgr backupnow` répond `Backup is not allowed` ; aucun jeu n’ayant été créé, un cycle de
  désinstallation/restauration n’aurait rien prouvé et n’a pas été exécuté. Les API 31/36.1, le
  transfert OEM/croisé et le runtime filesystem iOS restent à qualifier.
- La décision produit du 4 août conserve l’historique de recherche d’un compte pour Search, le futur
  Assistant IA et le fil organique. HISTORY-001 porte désormais l’autorité Supabase/RLS, la
  synchronisation multi-appareil et les contrôles de personnalisation ; aucun texte libre ne rejoint
  les analytics ou les logs. PRD et DESIGN reflètent ce contrat ; rétention, plafonds et valeur par
  défaut du contrôle de personnalisation restent à valider avant toute migration serveur.
- SEARCH-001A est implémentée localement : RPC lexical `security invoker` publié-only, curseur
  autoportant borné, mots exacts avec ponctuation/diacritiques alignés, runtime KMP, UI Compose et
  SwiftUI, ouverture de fiche et repli sur les 3 200 candidats maximum du cache Room Explore. Le
  cache ne contient toujours pas les tags et l’UI signale donc honnêtement ce résultat partiel.
- La soumission est explicite et n’envoie dans Analytics que l’événement `search_query` et la devise
  d’affichage capturée atomiquement ; aucune frappe ni requête brute n’est persistée ou journalisée.
  HISTORY-001 reste séparée et doit conserver uniquement les requêtes réellement soumises.
- Les validations ciblées ont passé 11 tests repository Search et 7 tests runtime avant le dernier
  durcissement d’effet, puis 6 tests Android Search après ce raccord. Detekt Android est vert et les 13 écarts de complexité ont été
  corrigés sans `Suppress` ni baseline. Le plan pgTAP final compte 61 assertions ; son exécution et
  la compilation Xcode du commit exact restent confiées à GitHub.
- La CI est préparée pour paralléliser intégrité/médias, Gradle, Edge Function, Supabase et iOS. Les
  trois configurations Xcode consomment un XCFramework partagé, les advisors sécurité sont bloquants,
  les versions Swift restent verrouillées et les noms des checks protégés sont conservés. Actionlint
  et les 146 tests d’intégrité du dépôt sont verts localement.

## Tâche en cours

SEC-001F, STAB-002A, IOS-PRIVACY-001A, IOS-PRIVACY-001B1 et SETTINGS-001B sont terminés localement sur
`codex/sec-001f-account-delete-step-up`, empilés sur OPS-001A. AUTH-UX-001 et OFFLINE-002 sont intégrés
sur `codex/auth-onboarding-ux-integration`; SEARCH-001A et la CI parallèle y sont en qualification
exact-head avant publication puis fusion. STAB-002B reste suspendu à la décision sur les cinq racines V1.
OPS-001B dépend du provisionnement propriétaire et des gates d'observabilité ci-dessous ;
SETTINGS-001 reste ouvert et ACTIONS-001C2 reste suspendu aux cinq décisions produit de son audit.

## Blocages / limites

- Le périmètre PRD nommé V1 dépasse largement une version minimale livrable. La réduction proposée dans l'audit exige une validation propriétaire et un ADR avant de masquer ou reporter Social, `+`, Notifications, B2B, paiement et IA.
- La navigation globale complète, les actions réelles du détail, l'administration opérateur, les contenus réels, le pipeline média et les validations natives/appareils bloquent encore une V1 commercialement exploitable.
- IOS-PRIVACY-001B2 doit encore valider les finalités, la liaison, la rétention et les réglages
  fournisseurs avec le propriétaire, puis rapprocher l'archive exacte du Privacy Report Xcode et
  d'App Store Connect. L'inventaire local IOS-PRIVACY-001B1 ne constitue donc pas une preuve de
  conformité Store complète. L'URL publique approuvée de politique de confidentialité reste aussi à
  fournir avant de pouvoir l'exposer dans Paramètres sans inventer de destination.
- SEC-001A n'est pas protectrice pour staging/production tant que sa PR n'est pas relue, fusionnée puis déployée.
- ARCH-004 est empilée sur SEC-001A et n'atteindra `main` qu'après la fusion de `#35`, le retarget éventuel de `#36` et une CI toujours verte.
- STAB-003 est empilée sur ARCH-004 et n'atteindra `main` qu'après `#35`, `#36`, le retarget de `#37` et une CI toujours verte.
- Les ACL forward-only ne prouvent pas la légitimité d’anciennes décisions ou adhésions ; la préflight et une éventuelle quarantaine approuvée sont obligatoires avant déploiement sur une base persistante.
- La compilation Xcode complète ne peut pas être exécutée sur ce poste Windows ; les configurations simulateur Debug/Staging/Release, les tests Swift, le runtime iOS et les XCFrameworks de DETAIL-IOS-001 sont confirmés par le run GitHub Actions macOS exact-head `30780564021` sous Xcode 16.4.
- OFFLINE-002 compile pour iOS X64 sous Windows et ouvre Room sous Robolectric dans le chemin Android
  `noBackup`, mais l’émulateur API 30 a subi des ANR du système/Pixel Launcher avant l’ouverture
  observable de Room par l’application. L’exclusion/protection iOS doit encore être exécutée sur
  simulateur macOS et appareil réel. Android exige encore `bmgr` API 31/36.1 et un transfert OEM ; les
  règles de sauvegarde plateforme ne sont pas une preuve cryptographique de liaison à l’appareil.
- La stack Supabase locale Kwabor a été recréée puis arrêtée proprement après reset, lint applicatif
  et 58 assertions SEARCH-001A vertes. Les trois durcissements finaux portent le plan à 61 ; la suite
  complète, les advisors et le harnais concurrent doivent maintenant être exécutés sur GitHub.
- Les 77 assertions GUIDE-001B ont été vérifiées statiquement mais pas exécutées contre PostgreSQL :
  Docker/Supabase local n'a volontairement pas été redémarré. Une stack isolée est obligatoire avant
  publication ou déploiement de la migration.
- Les sources SwiftUI GUIDE-001B et leur intégration Xcode ont été revues statiquement, mais Xcode
  n'est pas disponible sur ce poste Windows. Compilation, tests Swift et preuve VoiceOver restent à
  exécuter sur macOS avant publication.
- Les PolicyTests et sources SwiftUI ACTIONS-001C1 ont été revus statiquement, mais Swift/Xcode sont
  absents de ce poste Windows. Leur compilation native et le parcours `onOpenURL` restent à prouver
  sur macOS avant publication de la branche.
- Les sources SwiftUI SETTINGS-001A ont été revues statiquement, mais Swift/Xcode sont absents de ce
  poste Windows. Leur compilation native et les parcours VoiceOver restent à prouver sur macOS ; le
  rendu et TalkBack Android doivent aussi être vérifiés sur appareils configurés avant publication.
  La même gate couvre les nouveaux toggles de confidentialité SETTINGS-001B.
- La première distribution Android/iOS avec Firebase réel exige une installation staging propre et la preuve
  qu'aucune ancienne build Firebase automatique n'a été diffusée. Une ancienne collecte Crashlytics automatique
  ne permet pas de garantir l'annulation à chaud d'un upload déjà commencé ; toute population legacy
  découverte bloque la release jusqu'à un plan de migration dédié.
- `kwabor://listing/<uuid>` est volontairement interne et sans fallback. Un domaine HTTPS officiel,
  ses fichiers d'association, les fallbacks Store/serveur et le contrat de signalement sont requis
  avant de pouvoir livrer le partage public demandé par ACTIONS-001C.
- Le job Android API 36 de la PR `#48` échoue au stade de capture PNG brute malgré un cold start
  réussi. Le correctif de harnais proposé reste limité à quatre cœurs et `swiftshader`, sans changer
  les seuils de preuve ; aucune modification ni nouvelle exécution n'est autorisée sans accord explicite.
- Le mécanisme de signature/archivage iOS est prêt, mais aucun archive réelle ne peut être produite tant que le propriétaire n'a pas activé APNs/Sign in with Apple sur l'App ID et fourni certificat, profil et secrets GitHub.
- Les budgets publicitaires d'équipe ne sont pas encore reliés à la création/consommation réelle de campagnes ; cette intégration appartient à une tranche Promotion dédiée.
- L'envoi email/SMS d'invitations n'est pas encore implémenté ; le RPC génère un hash serveur et prépare le flux sécurisé.
- Le RPC catalogue est mesuré uniquement sur le seed local de quatre fiches. Le choix d'un éventuel index de classement exige un corpus staging représentatif et un nouveau plan `EXPLAIN (ANALYZE, BUFFERS)`.
- Explore Android consomme désormais le cache Room, le refresh et les pages suivantes, ouvre le
  DetailSheet connecté et affiche la recherche lexicale SEARCH-001A ; iOS possède la parité SwiftUI.
  Les récents durables, l’autocomplétion, les filtres avancés, l’Assistant IA, les actions réelles, la
  carte, les avis, les tris métier et les plafonds sponsorisés restent à livrer.
- La fiche SwiftUI compile sur simulateur dans les trois configurations, mais sa preuve VoiceOver sur appareil physique reste obligatoire. Le thème sombre complet appartient à SETTINGS-001 ; la fiche conserve temporairement la palette claire cohérente pour éviter des contrastes partiels.
- Aucun secret Supabase n'est commité ; sans configuration locale, Explore reste sur l'état vide initial.
- L'AVD API 30 local prouve l'installation, la résolution et les intents ACTIONS-001C1 à froid/chaud,
  mais pas l'affichage connecté de la fiche : l'APK n'a pas de configuration Supabase et les ANR
  observés concernent les processus système de l'AVD, pas KWABOR.
- L'écran Explore iOS SwiftUI natif compile dans les trois configurations simulateur et son smoke test de persistance est vert ; la validation VoiceOver/appareil physique reste à prouver avant fusion.
- La queue offline Like/Favori est préparée en mémoire uniquement ; persistance locale, drain/retry automatique et reprise après login restent à livrer dans une tranche dédiée.
- AUTH-005 est validée localement et par la CI macOS native ; les preuves fournisseur réelles restent dépendantes du provisionnement propriétaire décrit ci-dessous.
- Google/Apple restent inopérants hors tests tant que le propriétaire n'a pas créé les clients OAuth par tier, activé les fournisseurs dans les deux projets Supabase, activé Sign in with Apple sur l'App ID et régénéré les profils signés.
- Le correctif SEC-001F retire les credentials du body, mais l'ouverture d'`account-delete` aux
  utilisateurs staging et production reste interdite jusqu'à preuve réelle des claims AMR
  email/mot de passe, Google et Apple, et jusqu'à validation de la politique de
  rétention/expurgation des en-têtes d'invocation, des accès aux logs et des éventuels Log Drains.
  L'en-tête `Authorization` reste un secret ; seuls des comptes synthétiques peuvent servir à lever
  ces gates sur staging.
- Un tombstone `prepared` avec utilisateur Auth présent se reprend après reconnexion au même compte,
  grâce à une sentinelle de profil pseudonymisée puis une nouvelle session éphémère fraîche. Après
  suppression Auth, seule la réconciliation serveur peut terminer l'opération ; toute suppression
  manuelle DBA d'un utilisateur encore présent reste interdite.
- La suppression de compte exige avant release un seuil d'alerte pour les tombstones `prepared`, la preuve d'exécution du job quotidien et la validation juridique de la rétention technique de 30 jours.
- Les templates OTP d'inscription et Recovery exigent un plan Supabase compatible ou un SMTP personnalisé vérifié sur staging/production ; cette configuration propriétaire doit être prouvée avant toute bêta.
- Le réagrandissement destructeur du monogramme Android est corrigé dans BRAND-002 et la matrice KVM `30661731938` est techniquement et perceptuellement recevable sur ses neuf cellules. La revue Pixel/Samsung/iOS physique et la confirmation du master officiel restent obligatoires.
- La vidéo d'intro ne dépend plus d'ENV-001B/OBS-001B, d'un CDN ou de Firebase. Chaque nouvelle révision exige toutefois provenance, droits de diffusion, approbation éditoriale, preuves Android/iOS, builds signés et publication Store.
- ENV-001B dépend du propriétaire : le compte Supabase CLI visible ne contient aucune organisation Kwabor et la création de deux projets engage le choix de l'organisation/du plan ; l'authentification Firebase CLI existante est expirée et exige `firebase login --reauth` avant création des deux projets.
- OBS-001B dépend du propriétaire : les configurations Firebase réelles staging/production et la vérification sur appareils d'Analytics, Crashlytics, Performance et Remote Config générique ne peuvent commencer qu'après cette réauthentification et le provisionnement des deux projets.
- La clé d'upload Android, ses secrets GitHub production et l'inscription Play App Signing doivent être créés et conservés par le propriétaire avant le premier AAB de distribution ; le projet échoue volontairement en leur absence.
- Les projets Supabase/Firebase staging et production, le compte FedaPay, les comptes stores, le KYC, les certificats et les secrets fournisseurs nécessitent l'intervention du propriétaire pendant les tranches concernées.
- La validation juridique des CGU, de la politique de confidentialité et de la licence UGC reste une gate propriétaire avant release candidate.

## Prochaine tâche logique

Publier SEARCH-001A, exécuter sa CI parallèle exact-head puis fusionner uniquement si Android,
Supabase et Xcode Debug/Staging/Release sont verts. Ensuite, faire valider les trois paramètres de
rétention de HISTORY-001 et livrer les récents durables. Préparer OPS-001B seulement après
provisionnement staging ; avant d'activer
`account-delete`, prouver les AMR réelles email, Google et Apple et faire approuver/tester la
politique des en-têtes et journaux d'invocation. La vidéo d'intro reste embarquée : tout changement
d'octets exige une nouvelle release Android/iOS dans les Stores.
