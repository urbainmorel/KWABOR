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
- Offline/synchronisation.
- Upload média.
- IA et clés provider.

Ces zones demandent un plan dédié avant implémentation.
