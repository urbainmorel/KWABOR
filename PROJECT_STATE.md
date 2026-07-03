# PROJECT_STATE.md — Kwabor

## Phase actuelle

Fondations techniques et organisation staff senior.

## Dernière tâche terminée

- PR fondation `#1` mergée dans `main`.
- Contrats domaine ajoutés pour catalogue, auth, profil, social, promotion et notifications.
- Scaffold KMP minimal créé avec `shared`, `androidApp`, `webApp` et documentation `iosApp`.
- ADR fondateurs normalisés sous `docs/adr/`.
- Shell Compose partagé, primitives domaine, tokens design et i18n FR minimale ajoutés.

## Tâche en cours

Préparer la PR `foundation/catalog-repository-contracts`.

## Blocages / limites

- La protection de branche GitHub `main` a été refusée sur dépôt privé sans GitHub Pro ou dépôt public.
- L'hôte iOS complet doit être finalisé sur macOS avec Xcode.

## Prochaine tâche logique

Ouvrir la PR `foundation/catalog-repository-contracts`, puis démarrer FND-005 : migrations Supabase initiales avec RLS.
