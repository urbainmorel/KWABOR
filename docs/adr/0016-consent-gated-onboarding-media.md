# 0016 — Garantir l'intro embarquée et conditionner son remplacement distant au consentement

- **Statut** : accepté
- **Date** : 2026-07-14
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

Après consentement Remote Config, chaque plateforme maintient un listener temps réel en complément du fetch de démarrage. Une nouvelle révision publiée par le super-admin est téléchargée sans recompilation, mais ne remplace jamais la source d'une lecture en cours. Une fois validée et mise en cache, elle est présentée une seule fois au lancement suivant. La dernière révision présentée est persistée par installation.

Le média distant doit être une ressource HTTPS `video/mp4` de trois Mio maximum. Le client vérifie le SHA-256 attendu, une durée de 15 à 25 secondes, le codec H.264, le format portrait et l'absence de piste audio avant publication atomique dans le cache. Un échec revient silencieusement à l'actif embarqué et n'expose aucun détail technique à l'utilisateur.

La révocation du consentement remet la configuration aux valeurs sûres, annule le travail en vol et supprime le média distant. Le choix « reduced motion » utilise l'image statique et un bouton de continuation explicite.

L'état « première intro déjà vue » et la dernière révision distante présentée sont persistés séparément. Une révision distante ne peut donc pas réafficher plusieurs fois l'intro. L'accès invité est uniquement conservé pour le processus courant : au lancement suivant, un utilisateur non authentifié revoit l'écran d'authentification, sauf si une nouvelle révision distante validée est en attente.

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
- Le consentement complet reste à livrer avec AUTH-003 ; jusque-là, les valeurs refusées par défaut maintiennent uniquement le fallback local.

**À revoir si**

- La base légale ou le modèle de consentement Remote Config change après validation juridique.
- Les limites de taille, durée ou codec du média d'intro évoluent dans le DESIGN.
- Le cache média commun devient réellement partagé avec d'autres features.
