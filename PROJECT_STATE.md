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

## Tâche en cours

Aucune tâche de code ouverte après merge FND-005.

## Blocages / limites

- La protection de branche GitHub `main` a été refusée sur dépôt privé sans GitHub Pro ou dépôt public.
- Les DTO Supabase et les implémentations repository `data` ne sont pas encore présents.
- Le service Supabase Storage local complet a échoué une fois sur Windows ; la validation FND-005 utilise `supabase db start`, `supabase db reset` et `supabase test db`.
- L'hôte iOS complet doit être finalisé sur macOS avec Xcode.

## Prochaine tâche logique

Démarrer FND-006 : previews UI et tests du design system, sans feature métier complète.
