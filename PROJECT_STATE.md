# PROJECT_STATE.md — Kwabor

## Phase actuelle

Livraison V1 production — gouvernance et architecture avant verticales produit.

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
- Icône adaptive monochrome, splash Android 12+ et identité visible par variant ajoutés conformément au design Kwabor.
- Workflow manuel `Android release artifact` ajouté : exécution depuis `main`, GitHub Environment ciblé, approbation production, validation de la configuration distante, injection temporaire du keystore, gate `check`, checksum et artefacts bornés.
- Runbook `docs/android-release.md` ajouté avec versionnement, Play App Signing, secrets, commandes et contrôles avant téléversement.
- Validation ciblée ANDROID-REL-001 : APK staging minifié produit en 7 min 27 s, environnement `staging`, version `0.1.0-staging`, label attendu, signature debug vérifiée et mapping R8 présent ; release sans signature et signature partielle correctement rejetées.
- Validation globale ANDROID-REL-001 : `check`, APK debug, APK staging R8 et compilation Kotlin iOS simulateur verts en 10 min ; 100 tests partagés et 16 tests JVM Android sans échec, Detekt/Spotless/lint verts. Une configuration Supabase générique production reste absente du BuildConfig staging et n'alimente que release.

## Tâche en cours

PR-ANDROID-REL-001 — terminer la gate globale, ouvrir la PR puis attendre `quality`, pgTAP et le build iOS distant.

## Blocages / limites

- Le service Supabase Storage local complet a échoué une fois sur Windows ; la validation FND-005 utilise `supabase db start`, `supabase db reset` et `supabase test db`.
- La compilation iOS complète ne peut pas être exécutée sur ce poste Windows ; elle doit être confirmée par GitHub Actions macOS.
- La signature TestFlight/App Store reste hors scope jusqu'à disponibilité du compte Apple Developer, certificats, profils et secrets GitHub.
- Les budgets publicitaires d'équipe ne sont pas encore reliés à la création/consommation réelle de campagnes ; cette intégration appartient à une tranche Promotion dédiée.
- L'envoi email/SMS d'invitations n'est pas encore implémenté ; le RPC génère un hash serveur et prépare le flux sécurisé.
- Les couvertures de fiches catalogue sont récupérées par requête média dédiée par fiche ; une vue/RPC de listing summary sera à envisager avant optimisation forte du mur.
- L'activation promoteur par invite reste bloquée côté client tant que le RPC serveur dédié n'existe pas.
- Aucun secret Supabase n'est commité ; sans configuration locale, Explore reste sur l'état vide initial.
- L'écran Explore iOS SwiftUI natif n'est pas encore implémenté ; l'ancien placeholder Compose iOS a été supprimé et la parité devra être livrée directement en SwiftUI.
- La queue offline Like/Favori est préparée en mémoire uniquement ; persistance locale, drain/retry automatique et reprise après login restent à livrer dans une tranche dédiée.
- Le flux email OTP Android s'appuie sur les états/presenters partagés et l'UI Compose propre à `androidApp`, mais les acquisitions Google/Apple natives, l'écran Auth SwiftUI iOS et la persistance/retry offline complète restent à livrer dans les tranches Auth suivantes.
- ENV-001B dépend du propriétaire : le compte Supabase CLI visible ne contient aucune organisation Kwabor et la création de deux projets engage le choix de l'organisation/du plan ; l'authentification Firebase CLI existante est expirée et exige `firebase login --reauth` avant création des deux projets.
- La clé d'upload Android, ses secrets GitHub production et l'inscription Play App Signing doivent être créés et conservés par le propriétaire avant le premier AAB de distribution ; le projet échoue volontairement en leur absence.
- Les projets Supabase/Firebase staging et production, le compte FedaPay, les comptes stores, le KYC, les certificats et les secrets fournisseurs nécessitent l'intervention du propriétaire pendant les tranches concernées.
- La validation juridique des CGU, de la politique de confidentialité et de la licence UGC reste une gate propriétaire avant release candidate.

## Prochaine tâche logique

Après merge de la fondation release Android et CI distante verte, poursuivre `IOS-REL-001` sans certificat factice ; finaliser ENV-001B dès que le propriétaire a choisi l'organisation Supabase et réauthentifié Firebase.
