# PROJECT_STATE.md — Kwabor

## Phase actuelle

Fondations techniques et organisation staff senior.

## Dernière tâche terminée

- PR fondation `#1` mergée dans `main`.
- PR FND-004 `#2` mergée dans `main` avec CI `quality` verte.
- Contrats domaine ajoutés pour catalogue, auth, profil, social, promotion et notifications.
- Contrats sensibles durcis après revue QA : création campagne via demande/devis, onboarding auth avec acceptations obligatoires.
- Scaffold KMP minimal créé avec `shared`, `androidApp`, `webApp` et documentation `iosApp`.
- ADR fondateurs normalisés sous `docs/adr/`.
- Shell Compose partagé, primitives domaine, tokens design et i18n FR minimale ajoutés.

## Tâche en cours

Aucune tâche de code ouverte. `main` est synchronisé avec `origin/main`.

## Blocages / limites

- La protection de branche GitHub `main` a été refusée sur dépôt privé sans GitHub Pro ou dépôt public.
- Aucun DTO Supabase, aucune migration SQL et aucune implémentation repository ne sont encore présents.
- L'hôte iOS complet doit être finalisé sur macOS avec Xcode.

## Prochaine tâche logique

Démarrer FND-005 sur `foundation/supabase-initial-migrations` : migrations Supabase initiales, RLS, seeds Bénin et tests de politiques par rôle.
