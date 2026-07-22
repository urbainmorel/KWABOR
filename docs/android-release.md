# Release Android Kwabor

Ce runbook décrit la production reproductible des artefacts Android. Il ne remplace ni l'approbation du GitHub Environment `production`, ni la validation Play Console.

## Matrice des variants

| Variant | Tier distant | Identité visible | Signature | Artefact |
|---|---|---|---|---|
| `debug` | `development` | Kwabor Dev, version `-debug` | certificat debug local | APK de développement |
| `staging` | `staging` | Kwabor Staging, version `-staging` | certificat debug, distribution interne uniquement | APK minifié |
| `release` | `production` | Kwabor | clé d'upload propriétaire injectée | AAB minifié pour Play |

Les trois variants gardent l'application ID `com.kwabor.android`. La séparation des données repose sur des projets fournisseurs distincts et sur les valeurs injectées pour chaque tier.

R8 réduit les ressources, minifie et obfusque `staging` et `release`. Le fichier `mapping.txt` est conservé avec chaque artefact afin de désobfusquer les crashs. Le workflow produit aussi un SHA-256 de l'APK ou de l'AAB.

## Versionnement

- `KWABOR_VERSION_CODE` ou `kwabor.versionCode` : entier strictement positif et croissant pour chaque upload Play.
- `KWABOR_VERSION_NAME` ou `kwabor.versionName` : version sémantique, par exemple `1.0.0` ou `1.0.0-rc.1`.
- Valeurs locales par défaut hors distribution : code `1`, nom `0.1.0`.

Le build rejette un code invalide ou un nom hors contrat avant compilation.

## Identité visuelle et lancement

La source canonique du symbole carré est `kwabor_icone_app.png` à la racine du dépôt. Elle est réservée à l'icône de l'application et au splash système Android. Le symbole n'est jamais redessiné, détouré ou recoloré : le script versionné redimensionne le bitmap officiel opaque en conservant sa silhouette, sa courbe intérieure, ses nuances et sa texture.

Le splash Android 12+ et le foreground de l'icône adaptative utilisent deux assets distincts, chacun contenant le symbole intégral, au ratio inchangé, centré à 75 % sur le même fond ink `#0E0E0D`. Ce padding est nécessaire pour garder toute la silhouette dans le masque circulaire imposé au lancement par Android.

Le logo horizontal complet a un autre master canonique : `kwabor_2.png`. Il est embarqué dans `res/drawable-nodpi/kwabor_launch_wordmark.png` comme copie binaire exacte, en 2172 × 724 au ratio 3:1. Dès que le splash système rend la main, Compose l'affiche centré avec `ContentScale.Fit`, sur le fond `#080707` prélevé aux bords du master, jusqu'à la première frame réellement rendue par le lecteur vidéo. Aucun crop, padding raster, recolorisation ou redessin ne sépare donc le fichier officiel du rendu applicatif.

Les PNG Android/iOS sont régénérables sur Windows avec :

```powershell
.\tools\generate-brand-assets.ps1
```

La CI verrouille les trois masters, chaque dérivé d'icône et les références plateforme. Le contrôle peut aussi être lancé localement, sans dépendance Python tierce :

```powershell
python -B tools/verify-brand-assets.py
```

## Clé d'upload production

Le propriétaire génère et sauvegarde une clé d'upload dédiée hors du dépôt. Exemple à exécuter dans un emplacement privé et sauvegardé :

```powershell
keytool -genkeypair -v -keystore kwabor-upload.jks -alias kwabor-upload -keyalg RSA -keysize 2048 -validity 10000
```

La protection finale de l'application doit utiliser Play App Signing. La clé d'upload sert à authentifier les nouveaux bundles ; sa perte ou sa compromission doit être traitée selon la procédure Play de réinitialisation de clé d'upload.

Pour un build local production, fournir ensemble :

- `KWABOR_ANDROID_KEYSTORE_PATH` ou `kwabor.android.signing.storePath` ;
- `KWABOR_ANDROID_KEYSTORE_PASSWORD` ou `kwabor.android.signing.storePassword` ;
- `KWABOR_ANDROID_KEY_ALIAS` ou `kwabor.android.signing.keyAlias` ;
- `KWABOR_ANDROID_KEY_PASSWORD` ou `kwabor.android.signing.keyPassword`.

Une configuration partielle, un fichier absent ou une demande directe d'artefact release sans ces quatre valeurs fait échouer le build. Aucun certificat production factice n'est créé par le projet.

## Secrets GitHub production

Dans le GitHub Environment `production`, créer les secrets suivants :

| Secret | Contenu |
|---|---|
| `KWABOR_ANDROID_KEYSTORE_BASE64` | keystore encodé en Base64 sur une seule valeur |
| `KWABOR_ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `KWABOR_ANDROID_KEY_ALIAS` | alias de la clé d'upload |
| `KWABOR_ANDROID_KEY_PASSWORD` | mot de passe de la clé |

Le workflow décode le keystore dans le répertoire temporaire du runner, masque les valeurs sensibles et ne publie que l'AAB, le mapping R8 et le checksum. `staging` n'a accès à aucun de ces secrets.

## Builds locaux

Après avoir configuré le tier ciblé dans `local.properties` :

```powershell
.\gradlew.bat check :androidApp:assembleDebug
.\gradlew.bat check :androidApp:assembleStaging
.\gradlew.bat check :androidApp:bundleRelease
```

La troisième commande exige la clé d'upload injectée. Les sorties attendues sont :

- `androidApp/build/outputs/apk/debug/androidApp-debug.apk` ;
- `androidApp/build/outputs/apk/staging/androidApp-staging.apk` ;
- `androidApp/build/outputs/bundle/release/androidApp-release.aab` ;
- `androidApp/build/outputs/mapping/<variant>/mapping.txt` pour les variants minifiés.

## Workflow manuel

Le workflow GitHub Actions `Android release artifact` accepte uniquement `staging` ou `production`, un `version_code` et un `version_name`. Il doit être lancé depuis `main` :

1. choisir `staging` pour l'APK interne ou `production` pour l'AAB Play ;
2. vérifier que les variables Supabase du GitHub Environment ciblé existent ;
3. pour production, faire approuver le déploiement par le propriétaire ;
4. télécharger l'artefact Actions et vérifier son SHA-256 ;
5. archiver le mapping R8 avec la release correspondante.

Le workflow exécute la gate `check` avant de publier. Toute configuration distante ou signature manquante arrête la génération.

## Contrôles avant Play Console

- `versionCode` supérieur au dernier bundle téléversé ;
- environnement `production` et project ref Supabase production vérifiés ;
- signature de l'AAB issue de la clé d'upload attendue ;
- mapping R8 et SHA-256 archivés ;
- aucun fichier de configuration ou secret ajouté à Git ;
- CI de la révision `main` verte ;
- formulaire Data safety, politique de confidentialité et consentements validés par le propriétaire.
