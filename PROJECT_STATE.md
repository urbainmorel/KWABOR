# PROJECT_STATE.md — Kwabor

## Phase actuelle

Livraison V1 production — socle production livré, verticales produit actives.

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
- Icône adaptive, splash Android 12+ et identité visible par variant ajoutés conformément au design Kwabor ; les assets distincts utilisent le master opaque `kwabor_icone_app.png` sans détourage ni recoloration, centré à 75 % pour préserver toute sa silhouette dans les zones sûres et le masque circulaire de lancement Android.
- Workflow manuel `Android release artifact` ajouté : exécution depuis `main`, GitHub Environment ciblé, approbation production, validation de la configuration distante, injection temporaire du keystore, gate `check`, checksum et artefacts bornés.
- Runbook `docs/android-release.md` ajouté avec versionnement, Play App Signing, secrets, commandes et contrôles avant téléversement.
- Validation ciblée ANDROID-REL-001 : APK staging minifié produit en 7 min 27 s, environnement `staging`, version `0.1.0-staging`, label attendu, signature debug vérifiée et mapping R8 présent ; release sans signature et signature partielle correctement rejetées.
- Validation globale ANDROID-REL-001 : `check`, APK debug, APK staging R8 et compilation Kotlin iOS simulateur verts en 10 min ; 100 tests partagés et 16 tests JVM Android sans échec, Detekt/Spotless/lint verts. Une configuration Supabase générique production reste absente du BuildConfig staging et n'alimente que release.
- PR ANDROID-REL-001 `#26` mergée dans `main`, avec `quality`/pgTAP verts en 3 min 46 s et `iOS simulator build` vert en 3 min 04 s.
- IOS-REL-001 implémentée sur branche : configurations Xcode `Debug`, `Staging` et `Release` reliées respectivement aux tiers development/staging/production et aux XCFrameworks KMP debug/release attendus.
- Version, Team ID, profil et paramètres Supabase iOS sont injectés via xcconfig ou build settings ; aucune valeur fournisseur réelle ni certificat n'est versionné.
- Entitlements APNs et Sign in with Apple ajoutés avec valeurs development/production par configuration ; leur validité finale reste contrôlée par l'App ID et le provisioning profile Apple du propriétaire.
- Privacy Manifest initial ajouté comme ressource de cible, sans tracking, collecte propre à l'hôte ni Required Reason API déclarée à ce stade ; réaudit obligatoire à chaque SDK/feature collectrice.
- Icône iOS 1024 opaque et launch screen natif ink/mark ajoutés ; les PNG sont des redimensionnements opaques intégraux générés de façon déterministe depuis `kwabor_icone_app.png`, sans détourage, recoloration ni redessin, par un script Windows versionné.
- Workflow manuel `iOS archive artifact` ajouté : protection `main`, GitHub Environment, version Apple stricte, keychain/profil temporaires, validation équipe/bundle/capacités, archive signée, dSYM, manifest, signature, checksum et nettoyage après échec.
- La CI macOS construit désormais les XCFrameworks debug/release puis les configurations simulateur Debug/Staging/Release sans signature.
- Validation locale IOS-REL-001 : `check`, APK debug et compilation Kotlin iOS simulateur verts en 59 s ; Detekt/Spotless/lint verts. JSON assets, XML/plists, PNG/dimensions/opacité, déterminisme et unicité des objets PBX validés ; workflows conformes au parseur Prettier YAML.
- PR IOS-REL-001 `#27` mergée dans `main` après `quality`/pgTAP et compilation macOS des trois configurations simulateur vertes.
- OBS-001A livrée : Firebase Analytics, Crashlytics, Performance et Remote Config sont intégrés nativement sur Android et iOS, sans imposer de SDK Firebase au domaine partagé.
- La collecte Analytics/Crashlytics/Performance et le fetch Remote Config sont refusés par défaut sur les deux plateformes ; accorder ou révoquer les trois consentements applique immédiatement les états SDK et réinitialise les données/configurations concernées.
- Le contrat partagé expose le catalogue fermé des événements PRD, des diagnostics et des traces ; le contexte analytique refuse les identifiants non opaques afin d'éviter noms, emails, texte libre et autres PII.
- Android active Google Services uniquement en présence du fichier injecté et les workflows release valident projet/bundle avant tout build ; iOS sélectionne uniquement un plist au bundle ID exact et verrouille Firebase 12.16.0 avec ses dépendances SwiftPM.
- Le cache Remote Config de l'intro ne retient qu'une URL HTTPS sûre, un SHA-256 valide et une révision positive ; toute configuration absente, invalide ou révoquée revient aux valeurs embarquées sûres.
- Privacy Manifest, Info.plist, AndroidManifest et runbook `docs/observability.md` documentent les collectes, le consentement, l'injection, la vérification appareil et les responsabilités propriétaire.
- Validation locale OBS-001A : tests communs et Android ciblés, Detekt/Spotless, `check`, lint, APK debug, compilation Kotlin iOS simulateur, chemin Android configuré et `git diff --check` verts ; XML/plists, YAML et verrou SwiftPM validés.
- PR OBS-001A `#28` mergée dans `main` après `quality`/pgTAP verts en 4 min 00 s et compilation macOS des configurations simulateur Debug/Staging/Release verte en 19 min 41 s.
- AUTH-002 implémentée sur branche : intro native Android Compose et iOS SwiftUI avec MP4 portrait H.264 embarqué, silencieux et disponible hors connexion dès la première installation, reduced-motion, passage manuel et poursuite en invité.
- Une révision distante validée peut remplacer l'intro sans recompilation : écoute Firebase Remote Config temps réel après consentement, téléchargement en attente sans interrompre la session, puis lecture unique au lancement suivant. La révocation purge le média distant et le fallback embarqué reste toujours disponible.
- Le cache d'intro refuse les redirections, protocoles non HTTPS, SHA-256 incohérents, médias de plus de 3 Mio, codecs non H.264, dimensions non portrait, durées hors bornes et toute piste audio ; Android et iOS figent la source choisie pendant une lecture.
- La navigation invitée native protège les destinations authentifiées par un mur souple sans créer de session persistante ; iOS dispose désormais du flux OTP minimal équivalent pour s'authentifier depuis une destination protégée.
- ADR-0016, `PRD.md`, `DESIGN.md` et les runbooks onboarding/observabilité documentent le premier lancement embarqué, les révisions distantes, le consentement, la désactivation et le rollback super-admin.
- Validation locale AUTH-002 : validateur média/ffprobe, tests Android et partagés, Spotless, Detekt, lint, `check`, APK debug, contenu MP4 de l'APK, `git diff --check` et 66 tests pgTAP verts.
- La première CI complète de la PR `#29` a passé la vérification média, `quality` et pgTAP en 4 min 35 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release en 16 min 26 s.
- PR AUTH-002 `#29` mergée dans `main` au commit `aac92ba` après une seconde CI verte sur le commit de clôture : `quality`/pgTAP en 5 min 04 s et les trois configurations iOS simulateur en 16 min 02 s.
- AUTH-003 implémentée sur branche : email OTP, mot de passe initial, identité, ville manuelle/GPS local, devise, révisions juridiques, consentements observabilité et primer notifications équivalents Android Compose/iOS SwiftUI, avec reprise des sessions incomplètes et secrets absents des états/logs.
- La finalisation serveur atomique crée le profil, le rôle utilisateur et les trois preuves juridiques ; 28 policies RLS et 8 RPC mutantes refusent désormais toute écriture produit tant que `onboarding_completed_at` est absent, y compris pour une session OTP dotée d'anciens rôles privilégiés.
- Le primer notifications est persistant par installation et repris après un arrêt entre la RPC et l'accueil ; consentements, annulation post-OTP et demande système sont protégés contre les doubles actions et les échecs de persistance locale.
- Le logo de lancement Android/iOS utilise le master opaque `kwabor_icone_app.png` sans recoloration. La zone sûre Android a été ajustée après capture sur émulateur afin de conserver la silhouette complète malgré le masque circulaire natif ; 14 assets sont déterministes.
- La revue croisée AUTH-003 garantit aussi le primer après une session OTP déjà complète, rend la demande de localisation Android single-flight, masque les secrets des requêtes sociales/promoteur, distingue un OTP expiré et remplace le rectangle GPS par un polygone local du Bénin excluant notamment Lomé et Lagos.
- Validation locale AUTH-003 : 60 tests Android et 129 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug et compilation Kotlin iOS Simulator verts en 8 min 13 s ; vidéo embarquée H.264 portrait silencieuse/faststart validée et 167 assertions pgTAP vertes.
- La CI du commit `8f10fc9` a passé `quality` et pgTAP en 4 min 01 s, puis les XCFrameworks et les configurations simulateur iOS Debug/Staging/Release sous Xcode 16.4 en 20 min 14 s. Les ponts Swift/KMP de session, d'état et de consentement juridique sont ainsi validés nativement.
- AUTH-004 implémentée sur branche : connexion email puis mot de passe, récupération email → OTP Recovery → nouveau mot de passe, annulation sûre et déconnexion depuis Profil sont disponibles en Android Compose et iOS SwiftUI natif.
- Une session Supabase créée par un OTP Recovery reste explicitement non authentifiée. Les phases de mise à jour et de nettoyage sont persistées, reprises hors ligne et fermées en mode fail-closed après annulation, crash ou échec de suppression locale ; aucun OTP ou mot de passe n'est conservé dans un état UI persistant.
- La vérification d'inscription OTP d'un compte déjà complet ne peut plus contourner la connexion par mot de passe. Android et iOS conservent un marqueur non secret jusqu'à déconnexion confirmée ; iOS neutralise aussi une ancienne session Keychain lors d'une nouvelle installation avant d'autoriser l'accueil.
- Le template Supabase Recovery français utilise uniquement `{{ .Token }}`. Validation locale AUTH-004 : 64 tests Android et 153 tests partagés sans échec, Spotless, Detekt, lint, `check`, APK debug, compilation Kotlin iOS simulateur, intégrité du logo canonique et absence de modification des assets logo/intro verts en 10 min 44 s.

## Tâche en cours

Ouvrir la PR AUTH-004 depuis `codex/auth-004-session-recovery`, faire passer `quality`, pgTAP
et les trois configurations iOS simulateur sur macOS, puis fusionner atomiquement.

## Blocages / limites

- La compilation Xcode complète ne peut pas être exécutée sur ce poste Windows ; les configurations simulateur Debug/Staging/Release d'AUTH-003 sont confirmées par GitHub Actions macOS sous Xcode 16.4.
- Le mécanisme de signature/archivage iOS est prêt, mais aucun archive réelle ne peut être produite tant que le propriétaire n'a pas activé APNs/Sign in with Apple sur l'App ID et fourni certificat, profil et secrets GitHub.
- Les budgets publicitaires d'équipe ne sont pas encore reliés à la création/consommation réelle de campagnes ; cette intégration appartient à une tranche Promotion dédiée.
- L'envoi email/SMS d'invitations n'est pas encore implémenté ; le RPC génère un hash serveur et prépare le flux sécurisé.
- Les couvertures de fiches catalogue sont récupérées par requête média dédiée par fiche ; une vue/RPC de listing summary sera à envisager avant optimisation forte du mur.
- L'activation promoteur par invite reste bloquée côté client tant que le RPC serveur dédié n'existe pas.
- Aucun secret Supabase n'est commité ; sans configuration locale, Explore reste sur l'état vide initial.
- L'écran Explore iOS SwiftUI natif n'est pas encore implémenté ; l'ancien placeholder Compose iOS a été supprimé et la parité devra être livrée directement en SwiftUI.
- La queue offline Like/Favori est préparée en mémoire uniquement ; persistance locale, drain/retry automatique et reprise après login restent à livrer dans une tranche dédiée.
- Le parcours de création OTP et AUTH-004 sont livrés sur Android/iOS ; Google Android/iOS, Sign in with Apple iOS, activation Promoteur, ré-authentification et suppression de compte restent à livrer dans AUTH-005.
- Les templates OTP d'inscription et Recovery exigent un plan Supabase compatible ou un SMTP personnalisé vérifié sur staging/production ; cette configuration propriétaire doit être prouvée avant toute bêta.
- La capture sur émulateur Android confirme la fidélité du logo natif ; le projet ne possède pas encore de cible XCTest et la validation perceptuelle de l'autoplay silencieux, reduced-motion, lifecycle/fallback et Remote Config réel sur appareils physiques demeure obligatoire avant bêta.
- Le remplacement distant de l'intro ne devient opérationnel qu'après le provisionnement Firebase ENV-001B/OBS-001B et l'activation de Firebase Remote Config Realtime API ; le consentement observabilité est désormais branché dans AUTH-003.
- ENV-001B dépend du propriétaire : le compte Supabase CLI visible ne contient aucune organisation Kwabor et la création de deux projets engage le choix de l'organisation/du plan ; l'authentification Firebase CLI existante est expirée et exige `firebase login --reauth` avant création des deux projets.
- OBS-001B dépend du propriétaire : les configurations Firebase réelles staging/production et la vérification sur appareils ne peuvent commencer qu'après cette réauthentification et le provisionnement des deux projets.
- La clé d'upload Android, ses secrets GitHub production et l'inscription Play App Signing doivent être créés et conservés par le propriétaire avant le premier AAB de distribution ; le projet échoue volontairement en leur absence.
- Les projets Supabase/Firebase staging et production, le compte FedaPay, les comptes stores, le KYC, les certificats et les secrets fournisseurs nécessitent l'intervention du propriétaire pendant les tranches concernées.
- La validation juridique des CGU, de la politique de confidentialité et de la licence UGC reste une gate propriétaire avant release candidate.

## Prochaine tâche logique

Après fusion vérifiée d'AUTH-004, démarrer `AUTH-005` : Google Android/iOS, Sign in with Apple iOS, activation Promoteur, ré-authentification et Edge Function `account-delete` ; finaliser ENV-001B/OBS-001B dès que le propriétaire a choisi l'organisation Supabase, réauthentifié Firebase et provisionné les environnements distants.
