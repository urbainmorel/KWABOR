# iosApp

L'hôte iOS natif sera créé depuis Xcode sur macOS après validation du socle `shared`.

Décision actuelle :

- le domaine et la présentation partagée vivent dans `shared`;
- l'hôte iOS reste mince et ne contient pas de logique métier;
- la compilation iOS complète n'est pas exécutée sur ce poste Windows.

Suite logique : ajouter le projet Xcode et le framework partagé dans une tâche dédiée exécutée sur macOS.
