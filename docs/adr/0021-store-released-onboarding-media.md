# 0021 — Distribuer la vidéo d'intro exclusivement avec les versions Store

- **Statut** : accepté
- **Date** : 2026-08-02
- **Décideurs** : Produit Kwabor, Architecture, Mobile
- **Remplace** : ADR-0016

## Contexte et problème

L'ADR-0016 autorisait le remplacement éditorial de la vidéo d'intro par Firebase Remote Config et
un CDN après consentement. Ce canal exigeait quatre paramètres distants, un téléchargement et une
validation native sur deux plateformes, un cache atomique, des révisions concurrentes, une
quarantaine, une purge durable et un runbook de publication séparé. Cette complexité est
disproportionnée par rapport à la fréquence réelle de changement de l'intro et a retardé la V1.

La vidéo locale garantit déjà le besoin prioritaire : une première expérience déterministe, rapide
et disponible hors ligne. Le 2 août 2026, le produit a demandé que tout changement de vidéo passe
par une nouvelle version Android/iOS publiée dans les Stores.

Firebase Remote Config reste retenu par l'ADR-0013 pour des valeurs UX et des feature flags sûrs.
La présente décision retire uniquement son usage comme canal de distribution de média.

## Options envisagées

- **Conserver le canal distant** : publication éditoriale rapide, mais maintien de deux pipelines
  média, de caches sensibles aux courses et d'une exploitation Firebase/CDN dédiée.
- **Distribuer uniquement le média embarqué** : changement plus lent car soumis aux Stores, mais
  un seul actif qualifié, un comportement hors ligne déterministe et beaucoup moins d'états
  runtime.
- **Conserver un mode hybride désactivé** : garde une flexibilité théorique, mais conserve la dette,
  la surface d'attaque et le coût de test sans bénéfice V1.

## Décision

Nous retenons la distribution exclusivement embarquée parce qu'elle rend le parcours plus simple,
testable et prévisible sur Android/iOS et réseau dégradé.

- Android et iOS embarquent exactement les mêmes octets MP4 ainsi que la même image de repli.
- La révision embarquée initiale vaut `1` sur les deux plateformes. Elle est strictement positive et
  les deux constantes doivent toujours être égales.
- L'application persiste la dernière révision embarquée présentée. Elle affiche l'intro au premier
  lancement, puis une seule fois au premier lancement suivant l'installation d'une révision
  strictement supérieure. Une mise à jour qui conserve la même révision ne rejoue pas l'intro.
- Lors de la migration depuis le comportement antérieur, une installation ayant déjà terminé
  l'intro est considérée comme ayant présenté la révision `1`. Les métadonnées et fichiers distants
  hérités sont supprimés et ne peuvent plus influencer la navigation.
- Tout changement des octets vidéo exige simultanément leur remplacement byte-identical dans les
  deux bundles, l'incrément des deux constantes de révision, les validations média/launch sur les
  deux plateformes, un nouveau numéro de build et une publication Store.
- Inversement, une révision ne peut pas changer si les octets vidéo ne changent pas. Un retour à
  d'anciens octets constitue une nouvelle révision strictement supérieure et une nouvelle release.
- Le bouton **Passer**, le fallback statique `reduced-motion`, l'absence de piste audio, les
  événements `intro_video_shown` / `intro_video_skipped` et la navigation suivante sont conservés.
- Les clés `intro_video_enabled`, `intro_video_url`, `intro_video_sha256` et
  `intro_video_revision`, les modèles de média distant, le téléchargement, le cache, la
  quarantaine, la purge et l'observation temps réel associée sont retirés du produit actif.
- `tools/verify-onboarding-media.py` reste la porte de validation des actifs embarqués. Il vérifie
  le contrat MP4/fallback, l'égalité Android/iOS, l'égalité des révisions et, contre une base Git,
  le couplage strict entre changement d'octets et incrément de révision.

Remote Config reste disponible après consentement pour de futurs flags sûrs explicitement
allowlistés, typés et munis d'une valeur embarquée sûre. Il ne transporte aucun média, URL de
contenu ou octet éditorial et ne pilote jamais une autorisation, un prix, un paiement, une règle
RLS ou une autre décision d'autorité. Aucun flag générique n'est inventé tant qu'un besoin concret
n'est pas approuvé.

## Conséquences

**Positives**

- Un seul pipeline média est à maintenir et à qualifier.
- Le premier rendu ne dépend ni du réseau, ni du consentement, ni de Firebase, ni d'un CDN.
- Les courses de téléchargement/cache/purge et la surface d'attaque URL/MIME/hash disparaissent.
- Une version Store prouve exactement quels octets et quelle révision sont livrés.

**Négatives / compromis assumés**

- Un changement éditorial attend le cycle de build, validation et revue des Stores.
- Les utilisateurs qui n'installent pas la nouvelle version conservent l'ancienne vidéo.
- Une vidéo publiée par erreur ne peut pas être désactivée à distance ; il faut arrêter le rollout
  si possible puis publier une version corrective.
- Le MP4 et le fallback continuent d'augmenter la taille des deux binaires.

**À revoir si**

- un besoin produit chiffré exige des campagnes média fréquentes auprès des installations
  existantes et justifie explicitement un nouveau canal sécurisé ;
- les Stores fournissent un mécanisme natif de ressources à la demande qui respecte les mêmes
  garanties hors ligne, de validation et de maîtrise opérationnelle.
