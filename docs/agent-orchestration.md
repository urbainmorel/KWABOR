# Orchestration des agents IA Kwabor

## Principe

L'orchestrateur garde le contexte global et confie une seule tâche atomique à un agent spécialisé.
Chaque agent travaille sur un périmètre disjoint et ne revient jamais sur les changements d'un autre agent sans instruction explicite.

## Rôles

- **Orchestrateur** : séquence, vérifie, annonce la suite logique.
- **Architecture** : ADR et décisions structurantes.
- **Build/Tooling** : Gradle, modules, CI, commandes locales.
- **Domain** : modèles et règles métier pures Kotlin.
- **Data/Supabase** : DTO, repositories, migrations, RLS.
- **UI/Design System** : tokens, thème, composants et previews.
- **QA** : tests, `spotlessCheck`, `detekt`, `check`, matrice d'acceptation.

## Format de sortie obligatoire

Chaque agent termine avec :

- fichiers touchés;
- validation exécutée;
- risque restant;
- prochaine tâche logique.
