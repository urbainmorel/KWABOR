# iosApp

L'hôte iOS natif est un projet SwiftUI minimal relié au framework KMP `Shared`.

Décision actuelle :

- le domaine, la data, les contrats, les use cases et les modèles d'état partagés vivent dans `shared`;
- l'interface iOS est native SwiftUI;
- l'hôte iOS reste mince et ne contient pas de logique métier dupliquée;
- le projet Xcode importe `Shared` via `KwaborSharedBridge`;
- le framework `Shared.xcframework` debug ou release est généré avant Xcode selon la configuration;
- la compilation iOS complète n'est pas exécutée sur ce poste Windows et doit être confirmée par GitHub Actions macOS.

Commandes macOS avant ouverture/build Xcode :

```bash
./gradlew :shared:assembleSharedDebugXCFramework
./gradlew :shared:assembleSharedReleaseXCFramework
```

Configuration locale Supabase, sans secret commité :

- `KWABOR_SUPABASE_URL`
- `KWABOR_SUPABASE_PUBLISHABLE_KEY`

Copier `Kwabor/Config/Local.xcconfig.example` vers `Kwabor/Config/Local.xcconfig`, puis renseigner les valeurs qualifiées par tier. Le fichier local est ignoré par Git. Debug utilise development et le XCFramework debug ; Staging/Release utilisent respectivement staging/production et le XCFramework release.

Le contrat complet et les runbooks sont décrits dans [`docs/environment-configuration.md`](../docs/environment-configuration.md) et [`docs/ios-release.md`](../docs/ios-release.md).

Suite logique :

1. vérifier le job `iOS simulator build`;
2. corriger le projet Xcode si la validation macOS remonte un nouvel écart;
3. injecter les certificats et profils du propriétaire via le workflow d'archive sans les versionner.
