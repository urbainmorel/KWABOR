# iosApp

L'hôte iOS natif est un projet SwiftUI minimal relié au framework KMP `Shared`.

Décision actuelle :

- le domaine, la data, les contrats, les use cases et les modèles d'état partagés vivent dans `shared`;
- l'interface iOS est native SwiftUI;
- l'hôte iOS reste mince et ne contient pas de logique métier dupliquée;
- le projet Xcode importe `Shared` via `KwaborSharedBridge`;
- le framework `Shared.xcframework` debug ou release est généré avant Xcode selon la configuration;
- la compilation Xcode complète n'est pas exécutée sur Windows; la CI macOS compile les configurations
  simulateur `Debug`, `Staging` et `Release` sans signature.

## Configuration locale iOS

Copier `Kwabor/Config/Local.xcconfig.example` vers `Kwabor/Config/Local.xcconfig`. Ce fichier est
ignoré par Git. Les valeurs communes sont :

- `KWABOR_MARKETING_VERSION`;
- `KWABOR_CURRENT_PROJECT_VERSION`;
- `KWABOR_DEVELOPMENT_TEAM`;
- `KWABOR_PROVISIONING_PROFILE_SPECIFIER`.

Pour chaque tier utilisé, renseigner les familles suivantes, où `<TIER>` vaut `DEVELOPMENT`,
`STAGING` ou `PRODUCTION` :

- `KWABOR_SUPABASE_URL_<TIER>`;
- `KWABOR_SUPABASE_PUBLISHABLE_KEY_<TIER>`;
- `KWABOR_FIREBASE_IOS_CONFIG_PATH_<TIER>`;
- `KWABOR_GOOGLE_IOS_CLIENT_ID_<TIER>`;
- `KWABOR_GOOGLE_SERVER_CLIENT_ID_<TIER>`;
- `KWABOR_GOOGLE_REVERSED_CLIENT_ID_<TIER>`.

Le client Google iOS et le client Web/serveur doivent être distincts. Le reversed client ID doit
correspondre exactement au client iOS. Le plist Firebase reste hors du dépôt; seul son chemin absolu
est inscrit dans `Local.xcconfig`. Son absence n'empêche pas un build simulateur non signé, mais
désactive Firebase et ne constitue pas une preuve de configuration fournisseur.

`Debug` sélectionne `DEVELOPMENT` et le XCFramework debug. `Staging` et `Release` sélectionnent
respectivement `STAGING` et `PRODUCTION`, avec le XCFramework release. Le fichier `.env.example`
est un inventaire de référence et n'est pas chargé par Xcode.

## Validation macOS

Générer les deux XCFrameworks avant le build Xcode :

```bash
./gradlew :shared:assembleSharedDebugXCFramework
./gradlew :shared:assembleSharedReleaseXCFramework
```

Les commandes Xcode exactes, la signature et l'archive sont décrites dans
[`docs/ios-release.md`](../docs/ios-release.md). Le contrat de configuration commun est dans
[`docs/environment-configuration.md`](../docs/environment-configuration.md).
