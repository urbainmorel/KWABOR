# iosApp

L'hôte iOS natif sera un projet SwiftUI créé et validé sur macOS après validation du socle `shared`.

Décision actuelle :

- le domaine, la data, les contrats, les use cases et les modèles d'état partagés vivent dans `shared`;
- l'interface iOS est native SwiftUI;
- l'hôte iOS reste mince et ne contient pas de logique métier dupliquée;
- la compilation iOS complète n'est pas exécutée sur ce poste Windows.

Suite logique :

1. ajouter le projet Xcode SwiftUI et le framework partagé dans une tâche dédiée;
2. créer un job GitHub Actions macOS qui compile une cible simulateur avec `CODE_SIGNING_ALLOWED=NO`;
3. réserver la signature TestFlight/App Store à une tranche release avec compte Apple Developer, certificats, provisioning profiles et secrets GitHub.
