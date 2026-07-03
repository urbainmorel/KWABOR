# iosApp

L'hôte iOS natif est un projet SwiftUI minimal relié au framework KMP `Shared`.

Décision actuelle :

- le domaine, la data, les contrats, les use cases et les modèles d'état partagés vivent dans `shared`;
- l'interface iOS est native SwiftUI;
- l'hôte iOS reste mince et ne contient pas de logique métier dupliquée;
- le projet Xcode importe `Shared` via `KwaborSharedBridge`;
- la compilation iOS complète n'est pas exécutée sur ce poste Windows et doit être confirmée par GitHub Actions macOS.

Suite logique :

1. pousser la branche et vérifier le job `iOS simulator build`;
2. corriger le projet Xcode si la validation macOS remonte un écart;
3. réserver la signature TestFlight/App Store à une tranche release avec compte Apple Developer, certificats, provisioning profiles et secrets GitHub.
