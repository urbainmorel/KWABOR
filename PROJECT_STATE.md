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

## Tâche en cours

Aucune tâche de code ouverte après IOS-001/CI-001 local.

## Blocages / limites

- La protection de branche GitHub `main` a été refusée sur dépôt privé sans GitHub Pro ou dépôt public.
- Les DTO Supabase et les implémentations repository `data` ne sont pas encore présents.
- Le service Supabase Storage local complet a échoué une fois sur Windows ; la validation FND-005 utilise `supabase db start`, `supabase db reset` et `supabase test db`.
- La compilation iOS complète ne peut pas être exécutée sur ce poste Windows ; elle doit être confirmée par GitHub Actions macOS.
- La signature TestFlight/App Store reste hors scope jusqu'à disponibilité du compte Apple Developer, certificats, profils et secrets GitHub.

## Prochaine tâche logique

Pousser la branche et vérifier le job GitHub Actions macOS. Si CI verte, démarrer DATA-TEAM-001 : migrations Supabase équipes, membres, invitations, budgets et tests RLS.
