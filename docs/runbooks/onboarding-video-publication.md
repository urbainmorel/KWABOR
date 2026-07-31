# Publication distante de la vidéo d'intro

Ce runbook permet de remplacer une campagne vidéo Kwabor sans reconstruire Android/iOS et sans
publier une nouvelle version dans les stores. Il couvre uniquement un changement de contenu qui
reste compatible avec le lecteur déjà installé.

## Frontière entre publication distante et release Store

| Changement | Canal |
|---|---|
| Nouveaux octets vidéo respectant exactement le contrat ci-dessous | CDN + Firebase Remote Config |
| Désactivation d'une campagne ou retour à d'anciens octets compatibles | Remote Config, avec une nouvelle révision pour le retour |
| Vidéo embarquée visible au tout premier lancement | Nouvelle version Store |
| Codec, dimensions, durée, poids, audio, consentement ou schéma des quatre paramètres | Nouvelle version Store |
| Lecteur, fallback, bouton, texte, navigation, tracking ou logique d'affichage | Nouvelle version Store |

Une première installation reste toujours locale et hors ligne. Une campagne distante ne peut être
présentée qu'après consentement Remote Config, préchargement réussi et lancement suivant. Elle est
ensuite consommée une seule fois. Ce comportement est défini par
[l'ADR-0016](../adr/0016-consent-gated-onboarding-media.md).

## Gate d'activation

Le code client est livré, mais le canal ne doit pas être annoncé comme opérable avant clôture de
`ENV-001B` et `OBS-001B` : projets Firebase staging/production distincts, applications Android/iOS,
API Firebase Remote Config Realtime, CDN HTTPS, IAM minimal, secrets de build et preuves sur
appareils. En l'absence d'un de ces éléments, les valeurs sûres embarquées restent actives.

La console Firebase est l'interface super-admin V1. Elle évite d'introduire un nouveau client
applicatif. Une éventuelle automatisation future devra télécharger le template courant, préserver
tous ses autres paramètres, le valider avec son ETag puis publier le template complet ; elle ne
doit jamais reconstruire un template partiel. Firebase documente la
[gestion et la validation des templates](https://firebase.google.com/docs/remote-config/templates)
ainsi que la stratégie recommandée de
[chargement pour le lancement suivant](https://firebase.google.com/docs/remote-config/loading).

## Contrat du fichier

Le candidat opérateur doit respecter le sous-ensemble commun sûr Android/iOS :

- MP4 fast-start, une seule piste vidéo H.264 Baseline, Constrained Baseline ou Main, niveau 3.1 maximum ;
- dimensions physiques 720 × 1280, sans dépendre d'une rotation portée uniquement par les métadonnées ;
- format pixel `yuv420p`, aucune piste audio ;
- durée éditoriale comprise entre 15,000 et 25,000 secondes ;
- taille non vide et inférieure ou égale à 3 Mio (`3 145 728` octets) ;
- droits de diffusion, provenance et approbation visuelle enregistrés avant staging.

Les runtimes tolèrent jusqu'à 25,5 secondes pour absorber l'arrondi des conteneurs déjà reçus. Le
validateur de prépublication impose néanmoins la limite éditoriale de 25,0 secondes du PRD et
rejette tout candidat plus long.

## Préparer une révision

1. Choisir une révision strictement supérieure à toutes les révisions déjà publiées dans les deux
   environnements. Le format recommandé est `AAAAMMJJNN`, où `NN` est un compteur du jour.
2. Ne jamais réutiliser une URL. Le chemin CDN recommandé est adressé par contenu :
   `/intro/<sha256>.mp4`.
3. Valider d'abord le fichier local, sans URL encore inconnue :

```powershell
$candidate = "C:\chemin\intro-candidate.mp4"
$revision = 2026073101
python -B tools/verify-onboarding-media.py --input $candidate
```

4. Calculer le chemin adressé par contenu, puis exécuter la validation définitive et conserver le
   manifeste JSON avec les preuves de campagne :

```powershell
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $candidate).Hash.ToLowerInvariant()
$url = "https://cdn-staging.kwabor.example/intro/$sha256.mp4"
python -B tools/verify-onboarding-media.py `
  --input $candidate `
  --url $url `
  --revision $revision `
  --json
```

## Qualifier le CDN staging

1. Envoyer exactement les octets validés vers une URL HTTPS immuable.
2. Interdire le transcodage, la compression de contenu et l'écrasement d'un objet existant.
3. Télécharger l'URL sans accepter de redirection :

```powershell
curl.exe --fail --silent --show-error --location --max-redirs 0 `
  --proto "=https" --proto-redir "=https" `
  --dump-header build\intro-cdn-headers.txt `
  --output build\intro-cdn-download.mp4 `
  --write-out "%{http_code}`t%{content_type}`t%{size_download}`n" `
  $url
```

La sortie doit indiquer `200`, `video/mp4` et au plus `3145728` octets. Revalider ensuite les
octets réellement servis :

```powershell
python -B tools/verify-onboarding-media.py `
  --input build\intro-cdn-download.mp4 `
  --expected-sha256 $sha256 `
  --url $url `
  --revision $revision
```

## Publier en staging

Dans **Firebase Console → Remote Config**, modifier les valeurs par défaut sans condition et
publier les quatre paramètres dans un même template :

| Clé | Type | Valeur |
|---|---|---|
| `intro_video_enabled` | booléen | `true` |
| `intro_video_url` | chaîne | URL CDN staging qualifiée |
| `intro_video_sha256` | chaîne | SHA-256 minuscule du fichier servi |
| `intro_video_revision` | nombre | nouvelle révision positive |

Sur un appareil Android et un appareil iOS reliés au staging :

1. vérifier qu'un refus de consentement n'effectue aucun téléchargement ;
2. consentir, garder l'app au premier plan et vérifier que la publication ne remplace aucun écran
   en cours ;
3. passer hors ligne, relancer et vérifier la lecture de la révision précachée ;
4. terminer ou passer l'intro, relancer et vérifier qu'elle ne se répète pas ;
5. publier une révision supérieure pendant une session et vérifier `latest-valid-wins` au lancement
   suivant ;
6. révoquer le consentement et vérifier la purge de l'attente/cache sans rejeu local ;
7. tester une URL, un MIME et un hash invalides : aucune nouvelle révision ne devient active et la
   dernière révision déjà validée reste disponible ;
8. publier explicitement `intro_video_enabled=false` depuis le service : la campagne en attente doit
   être retirée, tandis qu'une configuration temporairement indisponible doit la conserver.

## Promouvoir en production

Promouvoir les **mêmes octets** vers une URL production immuable, répéter intégralement la
qualification HTTP et SHA-256, puis publier les quatre valeurs avec la même révision de campagne.
Un valideur différent du publieur donne le go/no-go. Effectuer enfin un smoke test sur un appareil
par plateforme sans réutiliser une installation ayant servi au staging.

La publication Remote Config rend les valeurs immédiatement disponibles, mais Kwabor les
précharge sans interrompre la session et n'affiche le média qu'au lancement suivant.

## Désactivation et retour arrière

- Urgence ou retrait : publier `intro_video_enabled=false`. Conserver les trois autres valeurs pour
  l'audit ; les clients utilisent la valeur sûre et purgent l'attente distante.
- Retour à un ancien contenu : republier ses octets sous une URL immuable avec une **nouvelle**
  révision supérieure.
- Ne pas utiliser seul le rollback de template Firebase : il crée une nouvelle version de template
  mais peut restaurer un ancien `intro_video_revision` que les clients ont déjà consommé.
- Un changement incompatible avec le contrat repasse par une release Store et la matrice de preuve
  du lecteur/splash.

## Registre des révisions

Chaque publication distante ajoute une ligne dans ce tableau. Cette mise à jour documentaire ne
déclenche aucune publication Store. Le manifeste JSON, les en-têtes CDN, les versions de template
Firebase et les preuves appareils sont conservés dans l'archive de release approuvée, jamais avec
des secrets dans Git.

| Révision | Média / SHA-256 | URLs | Templates staging/prod | Approbations et preuves | État |
|---:|---|---|---|---|---|
| `0` (embarqué) | vidéo `2b30d2fe685f2b12d60323d45eb2d2daf592f958f91b6182b04d3fd19cf75c14` ; image `796b73ed0d06adc36c7841532bb28201e2114d1ca7bfa7ac363959f8ef05f4f9` | Android/iOS bundle | sans objet | validation technique verte ; provenance, droits et approbation éditoriale à attester | fallback local |
