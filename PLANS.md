# PLANS.md — Exécution contrôlée Kwabor

## Règle d'exécution

Une tâche à la fois. Aucun agent ne démarre une deuxième tâche tant que son livrable n'est pas vérifié.

## Séquence de fondation

1. Verrouillage Git et branche de fondation.
2. ADR fondateurs.
3. Scaffold KMP minimal.
4. Primitives domaine.
5. Shell UI commun.
6. Validation `./gradlew check`.
7. PR de fondation.

## Zones à haut risque

- Authentification.
- RLS Supabase.
- Paiements.
- Droits d'équipe et budgets publicitaires.
- Offline/synchronisation.
- Upload média.
- IA et clés provider.
- CI release/signature iOS.

Ces zones demandent un plan dédié avant implémentation.

## Plan FND-005 — Supabase migrations initiales et RLS

**Agent responsable** : Data/Supabase.

**Objectif atomique** : créer le premier socle SQL versionné Supabase pour les référentiels, fiches, UGC, interactions, notifications, revendications, signalements, campagnes et paiements, avec RLS explicite et seeds Bénin minimaux.

**Livrables**

- Initialisation locale Supabase sans secret commité.
- Migration SQL initiale créée via `supabase migration new`.
- RLS activée sur toutes les tables `public`.
- Grants explicites `anon` / `authenticated` cohérents avec la Data API.
- Seeds minimaux Bénin pour villes, catégories et fiches publiées de test.
- Tests pgTAP ciblant les contraintes et politiques critiques.

**Règles de sécurité**

- Aucun `service_role`, secret, endpoint projet réel ou clé API dans le dépôt.
- Aucune autorisation basée sur `user_metadata`.
- Les rôles applicatifs sont lus dans `public.user_roles`, jamais depuis des claims modifiables par l'utilisateur.
- `listings.status = 'publie'` est la seule lecture publique catalogue.
- Les écritures `listings` restent limitées par rôle vérifié et `listing_class`.
- `social_posts.listing_id` reste obligatoire.
- Les claims sont impossibles sur les fiches patrimoniales.
- Les paiements restent des enregistrements serveur : pas de validation de succès côté client.

**Validation**

- `supabase db reset` si l'environnement Docker local le permet.
- `supabase test db` pour les tests pgTAP.
- `./gradlew.bat check`.
- `git diff --check`.

## Plan MOB-001 — Cadrage mobile-only, iOS SwiftUI et équipes vérifiées

**Agent responsable** : Architecture, avec revue Data/Supabase et Build/Tooling.

**Objectif atomique** : acter le nouveau périmètre Android/iOS uniquement, clarifier Android Compose Multiplatform + iOS SwiftUI, préparer la stratégie CI macOS et formaliser les rôles d'équipe vérifiée avant toute suppression de module ou migration.

**Livrables**

- ADR mobile-only acceptée.
- `AGENTS.md` aligné sur Android/iOS uniquement.
- `PRD.md` et `DESIGN.md` corrigés sur les exigences directement contradictoires Web/PWA.
- Modèle d'équipe vérifiée documenté : Propriétaire > Gestionnaire > Éditeur > Modérateur.
- `PROJECT_STATE.md` et `BACKLOG.md` mis à jour avec la suite logique.

**Règles de sécurité**

- Aucun droit d'équipe ne doit dépendre d'un masquage UI.
- Les budgets et paiements publicitaires restent contrôlés côté serveur.
- La signature iOS App Store/TestFlight utilisera uniquement des secrets GitHub chiffrés lors d'une tranche release dédiée.

**Validation**

- `git diff --check`.
- Relecture ciblée des mentions Web/PWA restantes.
- Pas de build requis pour cette tranche documentaire.

## Plan MOB-002 — Suppression de la cible Web/PWA

**Agent responsable** : Build/Tooling.

**Objectif atomique** : retirer `webApp` et la cible Kotlin/Wasm du build après acceptation d'ADR-0010, sans modifier les features métier.

**Livrables**

- `include(":webApp")` supprimé de `settings.gradle.kts`.
- Module `webApp` supprimé.
- Cible `wasmJs` supprimée de `shared`.
- Contournement Gradle spécifique Kotlin/Wasm supprimé.
- `PROJECT_STATE.md` et `BACKLOG.md` mis à jour.

**Validation**

- `git diff --check`.
- `./gradlew.bat check`.
- Recherche ciblée des références build `webApp`/`wasmJs`.

## Plan IOS-001 / CI-001 — Hôte iOS SwiftUI et CI macOS

**Agents responsables** : Build/Tooling et UI iOS.

**Objectif atomique** : créer un hôte SwiftUI minimal qui importe le framework KMP `Shared`, puis ajouter une CI macOS qui compile l'app iOS simulateur sans signature.

**Livrables**

- Cibles `iosX64`, `iosArm64`, `iosSimulatorArm64` dans `shared`.
- XCFramework `Shared` configuré.
- Bridge `KwaborSharedBridge` exposant une donnée stable à SwiftUI.
- Projet Xcode `iosApp/Kwabor.xcodeproj`.
- Scheme partagé `Kwabor`.
- Workflow GitHub Actions macOS avec `xcodebuild`.

**Validation locale**

- `./gradlew.bat :shared:tasks --all`.
- `./gradlew.bat check`.
- `git diff --check`.

**Validation distante requise**

- GitHub Actions job `iOS simulator build` sur `macos-15`.
- Le build iOS utilise `CODE_SIGNING_ALLOWED=NO`; la signature release reste une tranche séparée.

## Plan DATA-TEAM-001 — Organisations vérifiées, membres et budgets

**Agent responsable** : Data/Supabase, avec revue QA.

**Objectif atomique** : ajouter le socle SQL/RLS permettant aux organisations vérifiées de gérer une équipe et des budgets publicitaires alloués, sans encore ouvrir la création directe de campagnes ni de paiements côté client.

**Livrables**

- Types `organization_type`, `organization_role`, statuts de vérification, membre et invitation.
- Tables `organizations`, `organization_members`, `organization_invites`, `member_ad_budgets`.
- Helpers RLS privés dans `app_private` pour éviter la récursion sur `organization_members`.
- RLS et grants explicites sur toutes les nouvelles tables.
- Tests pgTAP couvrant visibilité, invitations, droits cumulés et plafonds budgétaires.

**Règles de sécurité**

- Aucune autorisation ne dépend de `user_metadata`.
- Un Éditeur ne peut pas gérer l'équipe.
- Un Gestionnaire peut inviter/attribuer seulement Éditeur ou Modérateur.
- Un Propriétaire peut inviter/attribuer Gestionnaire, Éditeur ou Modérateur.
- Un Gestionnaire ne peut allouer un budget Éditeur que dans son propre budget disponible.
- Un Modérateur n'a aucun accès financier.
- Les campagnes et paiements restent non insérables directement par les clients dans cette tranche.

**Validation**

- `supabase db reset`.
- `supabase test db`.
- `./gradlew.bat check`.
- `git diff --check`.

**Suite logique**

Après merge, lancer DOMAIN-TEAM-001 : modèles Kotlin purs et contrats repository pour organisations, membres, invitations et budgets.

## Plan DOMAIN-TEAM-001 — Domaine organisations vérifiées

**Agent responsable** : Domain, avec revue QA.

**Objectif atomique** : ajouter les modèles Kotlin purs et contrats repository pour manipuler les organisations vérifiées, membres, invitations et budgets, sans dépendance Supabase ni UI.

**Livrables**

- Enums domaine pour type/statut organisation, rôle équipe, statut membre et statut invitation.
- Helpers de droits cumulés : Propriétaire > Gestionnaire > Éditeur > Modérateur.
- Modèles `Organization`, `OrganizationMember`, `OrganizationInvite`, `MemberAdBudget`.
- Requêtes validées `OrganizationInviteRequest`, `OrganizationMemberRoleUpdate`, `MemberAdBudgetAllocationRequest`.
- Contrat `OrganizationRepository`.
- Tests `commonTest` sur hiérarchie, invitations, transfert propriétaire hors scope et budgets.

**Règles de sécurité**

- Le domaine ne contient aucun SDK Supabase, DTO SQL ni hypothèse de table.
- Les droits côté domaine servent de garde-fou UX ; la source d'autorité reste RLS/fonctions serveur.
- Le transfert de Propriétaire reste hors simple changement de rôle.
- Un Modérateur n'a aucun budget publicitaire.

**Validation**

- `./gradlew.bat :shared:check`.
- `./gradlew.bat check`.
- `git diff --check`.

**Suite logique**

Après merge, lancer DATA-TEAM-002 : DTO Supabase et implémentation `OrganizationRepository` dans `data`.

## Plan DATA-TEAM-002 — Repository data organisations vérifiées

**Agent responsable** : Data/Supabase, avec revue QA.

**Objectif atomique** : ajouter les DTO alignés sur le schéma Supabase équipes, les mappers domaine et une implémentation `OrganizationRepository` testable dans `data`, sans encore figer l'API PostgREST/RPC concrète.

**Livrables**

- DTO `organizations`, `organization_members`, `organization_invites` et `member_ad_budgets`.
- Mappers explicites DTO -> domaine pour enums, dates, montants XOF et statuts.
- Command DTO pour invitation et allocation de budget.
- Contrat `OrganizationDataSource` isolant le transport Supabase.
- Implémentation `DataOrganizationRepository`.
- Tests `commonTest` sur mapping, pagination, délégation, erreur `NotFound` et DTO invalide.

**Règles de sécurité**

- Aucun SDK Supabase dans le domaine.
- Aucun droit d'équipe confié à l'UI.
- Les erreurs de transport sont ramenées en `DomainError`.
- La création d'invitation réelle devra passer par un mécanisme serveur sûr pour le token/hash, pas par un secret client.

**Validation**

- `./gradlew.bat :shared:check`.
- `./gradlew.bat check`.
- `supabase test db`.
- `git diff --check`.

**Suite logique**

Après merge, lancer DATA-TEAM-003 : brancher `OrganizationDataSource` sur Supabase PostgREST/RPC en vérifiant l'API `supabase-kt` actuelle et sans commiter de secret.

## Plan DATA-TEAM-003 — Data source Supabase organisations

**Agent responsable** : Data/Supabase, avec revue QA.

**Objectif atomique** : brancher `OrganizationDataSource` sur Supabase PostgREST/RPC réel, en gardant Supabase hors du domaine et de l'UI.

**Livrables**

- Dépendances `postgrest-kt` et moteurs Ktor Android/iOS.
- Fabrique `createKwaborSupabaseClient` installant PostgREST sans secret commité.
- Implémentation `SupabaseOrganizationDataSource`.
- RPC SQL pour opérations sensibles : invitation, acceptation, révocation, suspension.
- Tests pgTAP des RPC et tests Kotlin des DTO RPC/patch.
- `PROJECT_STATE.md` et `BACKLOG.md` mis à jour.

**Règles de sécurité**

- Les invitations ne sont pas créées par insert client avec `token_hash` fourni par le client.
- Les RPC publics ont `search_path` fixé et `EXECUTE` limité à `authenticated`.
- L'acceptation d'invitation vérifie `auth.uid()` et l'email réel de `auth.users`.
- Les erreurs Supabase/PostgREST sont converties en `DomainError`, sans message technique brut.

**Validation**

- `supabase db reset`.
- `supabase test db`.
- `./gradlew.bat :shared:check`.
- `./gradlew.bat check`.
- `git diff --check`.

**Suite logique**

Après merge, lancer DATA-CATALOG-001 : implémenter les repositories data Supabase du catalogue avec le même client, les mêmes règles d'erreurs et des tests ciblés.

## Plan DATA-CATALOG-001 — Data source Supabase catalogue

**Agent responsable** : Data/Supabase, avec revue QA.

**Objectif atomique** : brancher `CatalogRepository` sur Supabase PostgREST pour la lecture catalogue existante, sans feature UI, sans auth réelle et sans nouvelle migration.

**Livrables**

- DTO Supabase pour `cities`, `categories`, `listings` et `listing_media`.
- Mappers explicites DTO -> domaine pour enums, dates, montants XOF, locale et médias.
- Contrat `CatalogDataSource` isolant le transport Supabase.
- Implémentation `DataCatalogRepository`.
- Implémentation `SupabaseCatalogDataSource` pour liste, recherche, détail et médias.
- Fabrique `createCatalogRepository(environment)`.
- Tests `commonTest` sur mapping, pagination, délégation, erreur `NotFound` et DTO invalide.
- `PROJECT_STATE.md` et `BACKLOG.md` mis à jour.

**Règles de sécurité**

- Supabase reste strictement dans `data`.
- Le domaine et l'UI ne reçoivent aucun SDK Supabase.
- La lecture publique reste bornée par RLS et par `ListingFilters.onlyPublished`.
- Aucune action Like/Favori n'est ajoutée sans session auth partagée.
- Aucun secret Supabase ni endpoint projet réel n'est commité.

**Validation**

- Vérifier le changelog Supabase pour changement Data API/PostgREST pertinent.
- `./gradlew.bat :shared:check`.
- `supabase test db`.
- `./gradlew.bat check`.
- `git diff --check`.

**Suite logique**

Après merge, lancer AUTH-FOUNDATION-001 : session auth partagée et stockage sécurisé des tokens, prérequis aux actions authentifiées Like/Favori et aux opérations équipes en runtime.

## Plan AUTH-FOUNDATION-001 — Session auth partagée et stockage sécurisé

**Agent responsable** : Data/Supabase, avec revue Architecture et QA.

**Objectif atomique** : installer le socle d'authentification partagé Supabase pour Android/iOS, stocker les sessions en stockage sécurisé plateforme et exposer au domaine uniquement une `AuthSession` sans token.

**Livrables**

- Dépendance `auth-kt` installée sans exposer Supabase au domaine ou à l'UI.
- `SessionManager` Kwabor utilisant un stockage de chaînes sécurisé injectable.
- Stockage Android via AndroidX Security Crypto.
- Stockage iOS via Keychain.
- `SupabaseAuthDataSource` pour session courante, OTP email, email/mot de passe, ID token Google/Apple et déconnexion.
- `DataAuthRepository` mappant les erreurs vers `DomainError` sans message technique brut.
- Factories Android/iOS pour créer le repository avec stockage sécurisé plateforme.
- Tests `commonTest` sur session manager, mapping session, validation email/mot de passe et garde d'activation promoteur.

**Règles de sécurité**

- Aucun access token, refresh token ou provider token ne sort du data layer.
- Le stockage par défaut Auth Supabase non sécurisé n'est pas utilisé pour les repositories runtime Android/iOS.
- L'activation promoteur par invite reste bloquée tant que le RPC serveur dédié n'existe pas.
- Les erreurs Supabase Auth sont converties en clés i18n stables, jamais en messages techniques.
- Android doit déclarer la permission réseau explicitement.

**Validation**

- Vérifier le changelog Supabase Auth et l'API `auth-kt` 3.6.0.
- `./gradlew.bat :shared:check`.
- `./gradlew.bat check`.
- `git diff --check`.
- CI GitHub `quality` et `ios`.

**Suite logique**

Après merge, lancer DATA-CATALOG-002 : ajouter les contrats et la data Supabase pour Like/Favori catalogue en consommant la session auth partagée.
