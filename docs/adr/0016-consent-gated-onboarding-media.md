# 0016 — Garantir l'intro embarquée et conditionner son remplacement distant au consentement

- **Statut** : accepté
- **Date** : 2026-07-14
- **Dernière clarification** : 2026-07-31
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le PRD demande une intro vidéo disponible au premier lancement, remplaçable à distance et résiliente hors ligne. L'ADR-0013 impose cependant que Firebase Remote Config ne soit interrogé qu'après consentement explicite. Ce consentement fait partie de l'onboarding détaillé et ne peut pas être présumé avant l'affichage de l'intro.

La clarification produit du 15 juillet 2026 exige qu'un super-admin puisse remplacer cet actif à tout moment sans recompilation. Elle lève la contradiction entre « premier lancement uniquement » et « vidéo saisonnière distante » : l'asset local reste limité au premier lancement, tandis qu'une nouvelle révision distante peut être présentée une fois après préchargement.

Télécharger un média distant avant consentement contredirait la politique de collecte refusée par défaut. Attendre une configuration distante rendrait en revanche le premier lancement fragile sur réseau lent ou absent.

## Options envisagées

- **Télécharger avant consentement** : répond au remplacement distant immédiat, mais viole la politique consent-first.
- **Dépendre uniquement du média distant** : réduit la taille de l'application, mais ne garantit ni le premier lancement ni le mode hors ligne.
- **Embarquer un média sûr et utiliser le distant seulement après consentement** : garantit le parcours tout en respectant la préférence utilisateur.

## Décision

Android et iOS embarquent une vidéo H.264 portrait de quinze secondes et une image statique. Au tout premier lancement, l'application utilise toujours cet actif local et démarre sa lecture sans attendre le réseau.

Après consentement Remote Config, chaque plateforme maintient un listener temps réel en complément du fetch de démarrage. Une nouvelle révision publiée par le super-admin est téléchargée sans recompilation, mais ne remplace jamais la source d'une lecture en cours. Une fois validée et mise en cache, elle est présentée une seule fois au lancement suivant. La dernière révision présentée est persistée par installation. Si plusieurs publications arrivent avant ce lancement, seule la révision valide la plus élevée reste en attente (`latest-valid-wins`).

Le média distant doit être une ressource HTTPS `video/mp4` de trois Mio maximum. Le client vérifie le SHA-256 attendu, une durée cible de 15 à 25 secondes, le codec H.264, le format portrait et l'absence de piste audio avant publication atomique dans le cache. Une configuration indisponible, un téléchargement ou un média invalide ne devient jamais une révision en attente, n'efface pas la dernière révision déjà validée et n'expose aucun détail technique à l'utilisateur. Les candidats reçus sont qualifiés dans l'ordre sans qu'un candidat supérieur puisse annuler celui déjà en cours ; seule une désactivation `false` provenant effectivement du service Remote Config purge l'attente. Après le premier lancement, l'absence de révision distante prête mène immédiatement à l'authentification ou à l'accueil : la vidéo locale n'est pas rejouée. Si une révision validée échoue seulement au décodage après l'ouverture de l'intro, l'image statique embarquée est affichée et la révision est considérée comme consommée afin d'éviter une boucle ; la vidéo locale n'est pas rejouée.

La révocation du consentement remet la configuration aux valeurs sûres, annule le travail en vol et supprime le média distant en attente. Une intention de purge durable est enregistrée avant toute suppression ; aucun nouveau candidat n'est qualifié tant que la métadonnée et le cache ne sont pas supprimés puis la purge acquittée. Un redémarrage reprend une purge non acquittée, tandis qu'une erreur de lecture transitoire conserve la dernière révision validée sans la présenter. La dernière révision déjà présentée reste conservée afin qu'une campagne consommée ne puisse pas être rejouée après un nouveau consentement. Une publication `intro_video_enabled=false` applique la même purge. Un retour à d'anciens octets utilise toujours une nouvelle révision supérieure, jamais la restauration de leur ancien numéro. Le choix « reduced motion » utilise l'image statique et un bouton de continuation explicite.

L'état « première intro déjà vue » et la dernière révision distante présentée sont persistés séparément. Une révision distante ne peut donc pas réafficher plusieurs fois l'intro. L'accès invité est uniquement conservé pour le processus courant : au lancement suivant, un utilisateur non authentifié revoit l'écran d'authentification, sauf si une nouvelle révision distante validée est en attente.

Ce canal distant remplace les releases Store uniquement lorsque seuls les octets éditoriaux changent et que le média respecte le contrat existant. Modifier le codec, les limites, le schéma Remote Config, le consentement, le lecteur, le fallback, les textes ou l'interface exige une nouvelle version applicative. Le média du tout premier lancement reste lui aussi celui embarqué dans la version installée.

## Conséquences

**Positives**

- Le premier lancement reste déterministe hors ligne et sur réseau dégradé.
- Aucun appel Remote Config ou média n'est déclenché avant consentement.
- Une publication distante peut être détectée en temps réel après consentement, sans interrompre l'expérience courante.
- Un contenu distant altéré, trop lourd ou incompatible ne peut pas devenir actif.
- Android et iOS conservent des interfaces natives et un contrat de routage partagé pur.

**Négatives / compromis assumés**

- La vidéo embarquée augmente la taille binaire d'environ 1,1 Mio et l'image d'environ 2,1 Mio.
- Une campagne distante ne peut pas remplacer l'intro lors d'une toute première installation avant recueil du consentement.
- Une mise à jour distante nécessite que l'appareil soit en ligne, consenti et que l'application s'exécute au moins une fois pour précharger la révision ; le fallback embarqué reste l'unique garantie hors ligne.
- Le consentement client est livré avec AUTH-003. L'activation réelle du canal distant reste conditionnée au provisionnement Firebase/CDN staging et production d'ENV-001B/OBS-001B, puis aux preuves sur appareils physiques.

**À revoir si**

- La base légale ou le modèle de consentement Remote Config change après validation juridique.
- Les limites de taille, durée ou codec du média d'intro évoluent dans le DESIGN.
- Le cache média commun devient réellement partagé avec d'autres features.
