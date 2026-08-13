# Release Android Kwabor

Ce runbook décrit le chemin G6 de la bêta fermée Android : construire un AAB `staging` signé,
archiver sa provenance, puis, sur demande explicite, publier ce même artefact dans Google Play
Internal. Ce workflow n'utilise jamais le backend production et ne réalise aucun rollout public.

## Matrice des variants

| Variant | Tier distant | Identité visible | Signature | Artefact |
| --- | --- | --- | --- | --- |
| `debug` | `development` | Kwabor Dev, version `-debug` | certificat debug local | APK de développement |
| `staging` local | `staging` | Kwabor Staging, version `-staging` | certificat debug local | APK ou AAB non distribuable sur Play |
| `staging` G6 | `staging` | Kwabor Staging, version `-staging` | clé d'upload Play protégée | AAB minifié pour Play Internal |
| `release` | `production` | Kwabor | configuration Gradle existante, hors profil bêta | aucun artefact produit par le workflow G6 |

Les variants gardent l'application ID `com.kwabor.android`. Le workflow G6 construit uniquement
`bundleStaging`. Il vérifie que les identités Supabase et Firebase correspondent au staging protégé
et que la référence Supabase production, utilisée seulement comme garde négatif, est distincte.

R8 réduit les ressources, minifie et obfusque `staging`. Le workflow retire la signature debug du
bundle généré, le signe avec la clé d'upload protégée, puis vérifie sa signature et l'empreinte du
certificat. Il archive l'AAB, `mapping.txt`, leurs SHA-256 et la provenance exacte.

## Versionnement

- `KWABOR_VERSION_CODE` ou `kwabor.versionCode` : entier de `1` à `2 100 000 000`, strictement
  croissant pour chaque upload Play.
- `KWABOR_VERSION_NAME` ou `kwabor.versionName` : version sémantique de 64 caractères maximum,
  par exemple `1.0.0` ou `1.0.0-rc.1`.
- Valeurs locales par défaut hors distribution : code `1`, nom `0.1.0`.

Le workflow rejette les zéros initiaux, les valeurs hors bornes et les noms hors contrat avant le
checkout ou la compilation.

## Identité visuelle et lancement

La source de build verrouillée du symbole carré est `kwabor_icone_app.png` à la racine du dépôt. Elle est réservée à l'icône de l'application et au splash système Android. Le symbole n'est jamais redessiné, détouré ou recoloré : le script versionné redimensionne ce bitmap opaque en conservant sa silhouette, sa courbe intérieure, ses nuances et sa texture. Le propriétaire de marque doit encore confirmer qu'il s'agit du master haute définition officiel, ou fournir son remplacement avant la validation perceptuelle finale.

Le splash système et le foreground de l'icône adaptative utilisent deux familles d'assets distinctes. Le foreground conserve son canevas intrinsèque de 108 dp. Le splash utilise le canevas Android sans fond d'icône de 288 dp, soit des PNG de 288, 432, 576, 864 et 1152 px de `mdpi` à `xxxhdpi`. Chaque splash est produit directement depuis le master 1254 px en un seul downsampling, avec le symbole centré à 75 % sur le fond ink `#0E0E0D`. Cette géométrie maintient la silhouette claire dans le cercle sûr de 192 dp sans agrandir un dérivé basse définition.

Le logo horizontal complet a un autre master canonique : `kwabor_2.png`. Il est embarqué dans `res/drawable-nodpi/kwabor_launch_wordmark.png` comme copie binaire exacte, en 2172 × 724 au ratio 3:1. Dès que le splash système rend la main, l'interface Android l'affiche centré avec `FIT_CENTER`, sur le fond `#080707` prélevé aux bords du master, jusqu'à la première frame réellement rendue par le lecteur vidéo. Aucun crop, padding raster, recolorisation ou redessin ne sépare donc le fichier officiel du rendu applicatif.

Au premier `onCreate`, le symbole système reste affiché au moins 1 000 ms ; une recréation d'Activity
n'ajoute pas cette attente de lancement à froid. Android 13+ reçoit explicitement la préférence
`icon_preferred`. La fenêtre et ses barres système restent sur le fond sombre pendant la relève.
La vidéo est attachée derrière le wordmark, préparée en pause à la position zéro, puis autorisée
uniquement après le retrait effectif du splash, 500 ms de wordmark dans une fenêtre visible sur
plusieurs frames et un lifecycle au premier plan. Un passage en arrière-plan ou une surface
détachée interrompt cette autorisation.
Lors d'un lancement ultérieur, ce même wordmark couvre aussi la décision locale comparant la
révision embarquée à la dernière révision présentée. Cette vérification locale ne peut donc pas
intercaler l'écran générique de restauration entre le symbole système et une intro requise.

Les PNG Android/iOS sont régénérables sur Windows avec :

```powershell
.\tools\generate-brand-assets.ps1
```

La CI verrouille les trois masters, chaque dérivé d'icône, la géométrie visible, le cercle sûr et le câblage XML actif du SplashScreen. Le contrôle peut aussi être lancé localement, sans dépendance Python tierce :

```powershell
python -B tools/verify-brand-assets.py
```

Quand un asset de lancement ou son pipeline change, la CI appelle aussi
`.github/workflows/android-launch-evidence.yml`. Elle installe un APK frais sur les API 30, 31 et
36, puis réinitialise complètement ses données avant chacun des profils `xxxhdpi`, `xhdpi` et
`mdpi`. La capture brute AOSP de l'écran composé est d'abord armée par une frame HOME. Le même
shell appareil horodate ensuite
`/proc/uptime`, publie le marqueur et exécute le cold start. Android 12 ne fournit pas l'option
shell de forçage du splash à icône : son job API 31 utilise donc l'image AOSP standard rootable,
exige `adb shell id -u == 0` et vérifie que HOME est au premier plan avant le lancement. Le
framework Android 12 classe cet UID racine comme surface système et sélectionne ainsi le même
splash à icône qu'un lancement depuis HOME. Sur API 36, `--splashscreen-show-icon` demande
explicitement `SPLASH_SCREEN_STYLE_ICON` ; API 30 conserve son mécanisme de splash historique.

La séquence RGBA commence par un burst de trois secondes à cible de 450 ms afin d'observer le
splash court, passe à une cible de deux secondes jusqu'au retour de `am start -W`, puis reprend une
cible de 450 ms pendant au moins cinq secondes. Le même shell publie successivement, par
renommages atomiques, l'horodatage de ce retour, le marqueur `ready` et la demande d'arrêt ; le
worker n'honore cette dernière qu'après le burst final. Celui-ci doit contenir au moins quatre
captures postérieures au marqueur et couvrir au moins 2,5 secondes. Chaque commande est bornée à
trois secondes ; le temps réellement inactif entre la fin d'une capture valide et le début de la
suivante reste limité à 4,5 secondes. Un dépassement, un timeout de commande ou une capture HOME
initiale vide rejette toute la séquence et autorise jusqu'à deux recaptures complètes ; aucune frame
d'un essai rejeté n'est réutilisée. L'en-tête, le format, les
dimensions et la taille exacte de chaque frame sont vérifiés avant conversion PNG côté hôte. Les
budgets statiques sont de 608 633 344 octets en `xxxhdpi`, 177 324 544 en `xhdpi` et 69 497 344 en
`mdpi`, chacun contrôlé avec 128 Mio supplémentaires réservés au système et sous le quota de
sécurité de 640 Mio. Le profil le plus lourd passe en premier. Les données brutes sont supprimées
après production d'un manifeste SHA-256, puis l'ensemble validé est publié par un unique renommage
atomique de répertoire.

Le `screenrecord` long démarre seulement après la validation et la compression de cette séquence
critique afin de ne pas lui disputer SurfaceFlinger. La CI réinitialise une seconde fois les données
applicatives avant de l'armer : cette preuve continue observe donc elle aussi un état applicatif
vierge de premier lancement et peut rendre visible le bref raccord wordmark → intro même lorsqu'un
`screencap` haute résolution l'a échantillonné autour. Cette preuve de continuité, distincte de la
preuve launch
native, est encodée en 360×780 pour rester sous les capacités AVC logicielles des images AOSP.
Recorder armé sur une surface HOME attestée pour chaque API, la CI fige une baseline MP4 monotone
juste avant l'action, provoque un cold start depuis cet état vierge puis, au moins quinze secondes
plus tard, une transition vers HOME suivie d'une reprise. Le même processus `screenrecord` doit rester
actif et le MP4 doit croître séparément après le cold start, après HOME et après la reprise. Chaque
baseline est un snapshot de taille validé au plus près de l'action, supérieur ou égal au dernier
octet déjà prouvé, et encadré par deux contrôles du même PID distant et du processus hôte. La
croissance strictement supérieure qui suit, les assertions d'activité/UI et la validation finale du
MP4 prouvent ensemble que le flux traverse chaque transition ; le processus applicatif doit aussi
rester identique entre HOME et la reprise. Le `LaunchState` doit être `HOT` ou `WARM`. Le retour
`UNKNOWN (0)` n'est admis que si `am start` émet aussi exactement l'avertissement indiquant que la
tâche courante a été ramenée au premier plan ; cette paire AOSP reste corroborée par le même PID,
la croissance du flux, l'activité reprise et l'état UI.
Les phases
partagent un deadline global borné sous la limite AOSP de 180 secondes et conservent dix secondes
pour finaliser le conteneur. La preuve exige enfin un H.264 décodable d'au moins quatre frames VFR,
au moins quinze secondes PTS, vingt-quatre secondes murales et une assertion UI distincte après le
cold start puis après la reprise, confirmant l'intro ou le landing configuré. La CI
publie les vidéos source et de revue, les
planches-contact, la frame HOME et les métadonnées pendant 7 jours ; en cas d'échec de cadence,
les PNG déjà validés restent disponibles pour le diagnostic. Une planche montre chaque
échantillon exactement une fois et un manifeste distingue `startup` de `post-ready`. La vidéo et
la planche normalisées sont des reconstructions qui maintiennent chaque PNG jusqu'à l'échantillon
suivant : elles facilitent la lecture, mais ne prouvent pas la persistance intermédiaire. Les
sources horodatées prouvent les pixels observés ; les tests déterministes imposent séparément
1 000 ms de splash et 500 ms de wordmark. L'APK de preuve utilise une URL réservée `.invalid` et
une clé factice non secrète ; la capture échoue si l'activité n'est pas reprise ou si aucune
surface onboarding configurée n'est exposée. Ces preuves automatisées ne remplacent pas la revue
perceptuelle finale sur appareils Pixel/Samsung et iOS. Une matrice sans frame wordmark clairement
identifiable est donc rejetée lors de cette revue, même si ses garde-fous temporels sont verts.

## Clé d'upload Play

Le propriétaire génère et sauvegarde une clé d'upload dédiée hors du dépôt. Exemple à exécuter dans
un emplacement privé et sauvegardé :

```powershell
keytool -genkeypair -v -keystore kwabor-upload.jks -alias kwabor-upload -keyalg RSA -keysize 2048 -validity 10000
```

Google Play doit utiliser Play App Signing. La clé d'upload authentifie l'AAB envoyé à Play
Internal ; elle n'est jamais embarquée dans Git. Sa perte ou sa compromission suit la procédure
Play de réinitialisation de clé d'upload.

Le workflow compare deux fois le certificat à `KWABOR_ANDROID_UPLOAD_CERT_SHA256` : lors de
l'injection du keystore, puis sur l'AAB signé. Il exige exactement une structure de signature JAR
et refuse un bundle partiellement ou doublement signé.

## Configuration GitHub

### Protections obligatoires

Les GitHub Environments `staging` et `play-internal` sont distincts. Chacun doit :

- imposer `deployment_branch_policy.protected_branches=true` et
  `deployment_branch_policy.custom_branch_policies=false` ;
- définir au moins un reviewer obligatoire ;
- interdire l'auto-approbation avec `prevent_self_review` ;
- interdire le bypass administrateur avec `can_admins_bypass`.

Le workflow lit ces règles avec l'API GitHub et échoue fermé si une condition manque. Les secrets ne
doivent pas être placés au niveau dépôt pour contourner ces Environments.

### Environment `staging` — build B7.02

Variables requises :

| Variable | Rôle |
| --- | --- |
| `KWABOR_SUPABASE_URL` | URL HTTPS exacte du projet staging |
| `KWABOR_SUPABASE_PUBLISHABLE_KEY` | clé publique mobile staging |
| `KWABOR_SUPABASE_PROJECT_REF` | référence du projet staging |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | garde négatif : doit différer du staging |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | empreinte protégée de la référence staging |
| `KWABOR_FIREBASE_PROJECT_ID` | identifiant Firebase staging |
| `KWABOR_STAGING_FIREBASE_PROJECT_ID_SHA256` | empreinte protégée de l'identifiant Firebase |
| `KWABOR_GOOGLE_WEB_CLIENT_ID` | client OAuth Web/serveur staging |
| `KWABOR_ANDROID_UPLOAD_CERT_SHA256` | empreinte du certificat de la clé d'upload |

La référence production n'est jamais injectée dans l'application. Elle sert uniquement à prouver
que l'URL mobile pointe vers un projet staging distinct.

Secrets requis :

| Secret | Contenu |
| --- | --- |
| `KWABOR_FIREBASE_ANDROID_CONFIG_BASE64` | `google-services.json` staging encodé en Base64 |
| `KWABOR_ANDROID_KEYSTORE_BASE64` | keystore d'upload encodé en Base64 |
| `KWABOR_ANDROID_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `KWABOR_ANDROID_KEY_ALIAS` | alias de la clé d'upload |
| `KWABOR_ANDROID_KEY_PASSWORD` | mot de passe de la clé |

Le workflow vérifie le project ID Firebase et l'unique client `com.kwabor.android`, puis supprime le
fichier et le keystore temporaires, y compris après échec.

Avant la première distribution Firebase réelle, confirmer que les testeurs partent d'une
installation propre. Si une ancienne build a pu activer une collecte automatique, bloquer la
release et documenter sa migration avant la cohorte.

### Environment `play-internal` — publication B7.04

| Type | Nom | Valeur attendue |
| --- | --- | --- |
| Variable | `KWABOR_PLAY_PACKAGE_NAME` | exactement `com.kwabor.android` |
| Variable | `KWABOR_ANDROID_UPLOAD_CERT_SHA256` | même empreinte que dans `staging` |
| Secret | `KWABOR_PLAY_SERVICE_ACCOUNT_JSON_BASE64` | JSON du compte de service Play encodé en Base64 |

Le compte de service doit être autorisé uniquement pour les opérations nécessaires sur
l'application Kwabor. Le workflow valide sa forme avant l'appel à Google Play et supprime le JSON
temporaire après l'étape de publication.

## Builds locaux

Après configuration du tier dans `local.properties`, les commandes de développement restent :

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:assembleStaging
.\gradlew.bat :androidApp:bundleStaging
```

Sorties principales :

- `androidApp/build/outputs/apk/debug/androidApp-debug.apk` ;
- `androidApp/build/outputs/apk/staging/androidApp-staging.apk` ;
- `androidApp/build/outputs/bundle/staging/androidApp-staging.aab` ;
- `androidApp/build/outputs/mapping/staging/mapping.txt`.

L'AAB `bundleStaging` local conserve la signature debug définie dans Gradle : il ne doit jamais être
téléversé sur Play. Seul le job G6 retire cette signature, applique la clé d'upload protégée et
produit l'AAB distribuable. Le workflow bêta ne lance jamais `bundleRelease` et ne charge aucune
configuration backend production.

## Workflow manuel G6

Le workflow `Android closed-beta release` est uniquement déclenchable par `workflow_dispatch` sur
le dépôt canonique et la branche `main`.

| Entrée | Contrat |
| --- | --- |
| `expected_sha` | SHA Git complet, minuscule, de 40 caractères |
| `version_code` | entier Play croissant de `1` à `2 100 000 000` |
| `version_name` | version sémantique, 64 caractères maximum |
| `publish_to_play_internal` | `false` par défaut ; active le job de publication séparé |
| `publish_confirmation` | `PUBLISH-EXACT-AAB-TO-PLAY-INTERNAL` si publication demandée |

### Job 1 — construire et archiver

1. Vérifier que `expected_sha` est le SHA sélectionné de `main`.
2. Checkout ce SHA sans conserver les credentials Git.
3. Exiger un run `CI` réussi, déclenché par `push` sur `main`, pour ce SHA exact.
4. Vérifier les protections de l'Environment `staging` et les identités staging.
5. Construire `:androidApp:bundleStaging` sans rejouer la gate globale déjà prouvée par la CI.
6. Retirer la signature debug, signer avec la clé d'upload et vérifier le certificat attendu.
7. Archiver pendant 30 jours l'AAB, `mapping.txt`, `KWABOR-SHA256SUMS.txt` et
   `KWABOR-ANDROID-PROVENANCE.json`.

Le nom de l'artefact contient version, code et `expected_sha`. La provenance relie le SHA source,
le run CI qualifié, le run de build, les empreintes de staging, la signature et les hashes de
l'AAB/mapping. Si `publish_to_play_internal` vaut `false`, le workflow s'arrête ici sans accès Play.

### Job 2 — publier après approbation

Le job `publish` dépend du job de build. Il ne démarre que si
`publish_to_play_internal` vaut `true`, après approbation de l'Environment `play-internal` et avec la
phrase de confirmation exacte.

Avant tout upload, il retélécharge l'artefact du même run et revérifie :

- checkout, `expected_sha`, version et code ;
- provenance du run et backend `staging` ;
- SHA-256 de l'AAB et du mapping ;
- signature unique et certificat d'upload ;
- package `com.kwabor.android` et credential Play.

L'action Play est épinglée par SHA, utilise exclusivement `tracks: internal` avec
`status: completed` et ne définit aucun `userFraction`. « Completed » rend la version disponible à
tous les testeurs éligibles de la piste Internal ; ce n'est ni une promotion ni un rollout public.

## Preflight et preuves G6

Avant d'approuver le job `publish` :

- G4 et G5 sont verts pour le même `expected_sha` ;
- le `versionCode` dépasse la dernière version téléversée ;
- les deux Environments respectent toutes les protections et contiennent uniquement les autorités
  attendues ;
- Play App Signing, le compte de service, l'application `com.kwabor.android` et la liste fermée de
  testeurs existent ;
- l'empreinte du certificat d'upload correspond à Play Console ;
- l'artefact Actions, son digest, la provenance, les SHA-256 et le mapping R8 sont inspectés ;
- aucun secret ou fichier fournisseur n'a été ajouté à Git ;
- les consentements, la fiche Data safety, la confidentialité et les droits média sont approuvés.

Un succès d'upload ne ferme pas G6. Il faut encore prouver le traitement Play Internal,
l'installation sur les appareils cibles, les parcours critiques, l'accessibilité et les mesures P75
définies dans le [plan de bêta fermée](closed-beta-delivery-plan.md).

Le GEL enregistre le workflow/run, `expected_sha`, l'URL et le digest de l'artefact, les SHA-256,
l'auteur/reviewer, l'horodatage et le résultat Play. Toute nouvelle RC exige un nouveau
`versionCode`, un SHA exact requalifié et les gates affectées.

Documents liés : [ADR-0036](adr/0036-closed-beta-catalog-delivery-profile.md),
[déploiement](deployment.md) et [configuration des environnements](environment-configuration.md).
