# PROJECT_STATE.md — Kwabor

## Phase actuelle

Fondations techniques et organisation staff senior.

## Dernière tâche terminée

- PR fondation `#1` mergée dans `main`.
- PR FND-004 `#2` mergée dans `main` avec CI `quality` verte.
- PR FND-005 `#4` vérifiée avec CI `quality` verte.
- Contrats domaine ajoutés pour catalogue, auth, profil, social, promotion et notifications.
- Contrats sensibles durcis après revue QA : création campagne via demande/devis, onboarding auth avec acceptations obligatoires.
- Migrations Supabase initiales ajoutées : référentiels, profils/rôles, fiches, médias, social, favoris, likes, notifications, claims, signalements, campagnes et paiements.
- RLS initiale validée par pgTAP : lecture publique limitée aux fiches publiées, écriture `listings` par rôle vérifié × `listing_class`, UGC rattaché obligatoire, claims patrimoniaux bloqués, paiements/campagnes non insérables par client.
- Seeds Bénin minimaux ajoutés : villes, catégories et fiches publiées de test.
- Scaffold KMP minimal créé avec `shared`, `androidApp`, `webApp` et documentation `iosApp`.
- ADR fondateurs normalisés sous `docs/adr/`.
- Shell Compose partagé, primitives domaine, tokens design et i18n FR minimale ajoutés.
- Cadrage mobile-only validé : Android/iOS uniquement, Android Compose Multiplatform, iOS SwiftUI, Web/PWA hors scope.
- Modèle d'équipe vérifiée cadré : Propriétaire > Gestionnaire > Éditeur > Modérateur, droits cumulatifs et budgets contrôlés côté serveur/RLS.
- Module `webApp` supprimé du dépôt et du build Gradle ; cible Kotlin/Wasm retirée de `shared`.
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

## Tâche en cours

Aucune tâche active après AUTH-FOUNDATION-001.

## Blocages / limites

- La protection de branche GitHub `main` a été refusée sur dépôt privé sans GitHub Pro ou dépôt public.
- Le service Supabase Storage local complet a échoué une fois sur Windows ; la validation FND-005 utilise `supabase db start`, `supabase db reset` et `supabase test db`.
- La compilation iOS complète ne peut pas être exécutée sur ce poste Windows ; elle doit être confirmée par GitHub Actions macOS.
- La signature TestFlight/App Store reste hors scope jusqu'à disponibilité du compte Apple Developer, certificats, profils et secrets GitHub.
- Les budgets publicitaires d'équipe ne sont pas encore reliés à la création/consommation réelle de campagnes ; cette intégration appartient à une tranche Promotion dédiée.
- L'envoi email/SMS d'invitations n'est pas encore implémenté ; le RPC génère un hash serveur et prépare le flux sécurisé.
- Les couvertures de fiches catalogue sont récupérées par requête média dédiée par fiche ; une vue/RPC de listing summary sera à envisager avant optimisation forte du mur.
- L'activation promoteur par invite reste bloquée côté client tant que le RPC serveur dédié n'existe pas.
- Les actions Like/Favori catalogue ne sont pas encore exposées par contrat domaine ; elles peuvent maintenant démarrer en consommant la session auth partagée.

## Prochaine tâche logique

Lancer DATA-CATALOG-002 : ajouter les contrats et la data Supabase pour Like/Favori catalogue en consommant la session auth partagée.
