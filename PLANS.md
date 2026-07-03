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
