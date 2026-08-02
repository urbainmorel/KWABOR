# Publier une nouvelle vidéo d'intro via les Stores

Ce runbook applique l'[ADR-0021](../adr/0021-store-released-onboarding-media.md). La vidéo est un
actif embarqué Android/iOS : aucun upload CDN, paramètre Firebase ou changement éditorial distant
n'est autorisé. Toute nouvelle vidéo devient effective uniquement dans une nouvelle version
installée depuis les Stores.

## Contrat immuable du fichier

Le candidat doit respecter le sous-ensemble commun sûr Android/iOS :

- MP4 fast-start, une seule piste vidéo H.264 Baseline, Constrained Baseline ou Main, niveau 3.1
  maximum ;
- dimensions physiques 720 × 1280, sans rotation portée seulement par les métadonnées ;
- format pixel `yuv420p`, aucune piste audio ;
- durée comprise entre 15,000 et 25,000 secondes ;
- taille non vide et inférieure ou égale à 3 Mio (`3 145 728` octets) ;
- droits de diffusion, provenance et approbation visuelle enregistrés avant intégration.

Les runtimes tolèrent jusqu'à 25,5 secondes pour absorber l'arrondi de certains conteneurs. Le
validateur de release impose néanmoins la limite éditoriale de 25,0 secondes.

## Règle de révision

La révision initiale vaut `1` et vit dans deux constantes :

- Android : `BUNDLED_INTRO_REVISION` dans
  `androidApp/src/main/kotlin/com/kwabor/android/onboarding/FirstLaunchStore.kt` ;
- iOS : `bundledIntroRevision` dans
  `iosApp/Kwabor/Onboarding/IntroVideoPresentationStore.swift`.

Les deux valeurs doivent toujours être égales et strictement positives. Elles ne changent que si
les octets MP4 changent. Si les octets changent, la nouvelle valeur doit être strictement
supérieure à celle de la branche de base. Ne jamais modifier les constantes de baseline de
migration historique : elles restent fixées à `1`.

Une révision supérieure est présentée une seule fois au premier lancement après installation. Une
release qui conserve les mêmes octets et la même révision ne rejoue pas l'intro. Réutiliser une
ancienne vidéo constitue un nouveau changement d'octets et exige donc une nouvelle révision.

## Préparer le candidat

1. Encoder le candidat selon le contrat ci-dessus et le conserver dans un dossier de travail ignoré.
2. Calculer son SHA-256 et l'archiver avec les preuves de provenance, droits et approbation éditoriale.
3. Remplacer les deux actifs par exactement les mêmes octets :

```text
androidApp/src/main/res/raw/kwabor_intro.mp4
iosApp/Kwabor/Resources/KwaborIntro.mp4
```

4. Incrémenter ensemble `BUNDLED_INTRO_REVISION` et `bundledIntroRevision`.
5. Ne modifier l'image fallback que si le changement est explicitement approuvé ; dans ce cas, les
   deux PNG doivent également rester byte-identical.
6. Exécuter immédiatement le vérificateur autonome ; il valide les octets désormais intégrés dans
   les deux bundles, jamais un candidat externe ou une publication distante.

## Vérifier avant commit

Exécuter d'abord la validation autonome des actifs et constantes courants :

```powershell
python -B tools/verify-onboarding-media.py
```

Puis vérifier le couplage avec la branche de base de la PR :

```powershell
python -B tools/verify-onboarding-media.py --base-ref origin/codex/explore-002b-contract
```

Le second appel doit refuser :

- des MP4 Android/iOS différents ;
- des constantes Android/iOS différentes ou non positives ;
- un changement d'octets sans révision strictement supérieure ;
- une révision modifiée alors que les octets restent identiques ;
- une première introduction des constantes à une autre valeur que `1`.

Exécuter ensuite les gates du dépôt et les tests onboarding ciblés, puis `check` selon le risque.
Toute modification d'actif ou de lecteur exige aussi la matrice de preuve de lancement Android et
une compilation/qualification iOS.

## Qualifier sur staging

Sur au moins un appareil Android cible et un appareil iOS :

1. installer proprement la build staging, hors ligne, et vérifier wordmark → intro → landing ;
2. vérifier lecture silencieuse, cadrage, bouton **Passer** et absence de flash vide ;
3. activer `reduced-motion` et vérifier l'image statique + **Continuer**, sans lecture vidéo ;
4. terminer ou passer l'intro, relancer et vérifier qu'elle ne se répète pas ;
5. installer par-dessus une build de révision précédente sans effacer les données, puis vérifier
   que la nouvelle révision apparaît une seule fois au lancement suivant ;
6. installer une build plus récente avec la même révision et vérifier l'absence de rejeu ;
7. simuler un échec de lecture et vérifier fallback, poursuite sûre et absence de boucle ;
8. sur une installation ayant déjà accordé Analytics, vérifier les événements
   `intro_video_shown` et `intro_video_skipped` sans PII. Le premier lancement avant consentement
   n'est volontairement ni collecté ni mis en attente et reste hors du taux de skip.

## Publier

1. Mettre à jour les versions/build numbers Android et iOS selon leurs runbooks de release.
2. Produire les artefacts signés depuis `main` et vérifier leurs checksums.
3. Archiver le SHA-256 du MP4, la révision, les preuves appareils, les droits et les approbations.
4. Distribuer d'abord en test interne/TestFlight, puis lancer le rollout Store approuvé.
5. Surveiller crash, démarrage, taux de skip et retours utilisateurs avant chaque palier.

## Incident et retour arrière

La vidéo ne possède aucun kill switch distant. En cas d'actif incorrect ou juridiquement retiré :

1. suspendre immédiatement le rollout Store lorsque la console le permet ;
2. préparer des octets corrigés ou restaurer un ancien fichier approuvé ;
3. attribuer une nouvelle révision strictement supérieure ;
4. répéter toutes les validations et publier une release corrective accélérée.

Les utilisateurs qui n'installent pas le correctif conservent l'ancien actif. Remote Config ne doit
jamais être utilisé pour contourner cette procédure.

## Registre des révisions

Chaque changement ajoute une ligne avant publication. Les manifestes de build et preuves complètes
sont archivés dans le dossier de release approuvé, jamais avec des secrets dans Git.

| Révision | SHA-256 vidéo | Versions Android/iOS | Approbations et preuves | État |
|---:|---|---|---|---|
| `1` | `2b30d2fe685f2b12d60323d45eb2d2daf592f958f91b6182b04d3fd19cf75c14` | baseline à renseigner lors de la première release | validation technique verte ; provenance, droits et approbation éditoriale à attester | embarqué |
