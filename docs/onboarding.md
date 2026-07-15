# Onboarding mobile

## Comportement livré par AUTH-002

Au premier lancement, Android Compose et iOS SwiftUI affichent une intro portrait silencieuse. Le bouton **Passer** reste immédiatement disponible. Lorsque la réduction des animations est active, l'application affiche l'image statique embarquée et un bouton **Continuer**.

Après l'intro, un utilisateur non connecté peut ouvrir le flux OTP ou demander un accès invité. Avant de confirmer cet accès, l'application précise que les prix restent en FCFA et que les interactions nécessitent un compte. L'accès invité ouvre le mur Explore en lecture seule ; toucher une destination protégée conserve le mur souple d'authentification.

L'intro embarquée n'est affichée qu'une fois par installation. Chaque nouvelle révision distante validée peut ensuite être affichée une seule fois, au lancement suivant son préchargement. L'accès invité n'est pas persisté : au prochain lancement sans session authentifiée, l'écran de connexion est présenté, sauf si une nouvelle révision d'intro est en attente.

## Média embarqué et distant

Les actifs de repli sont versionnés avec chaque client :

- Android : `res/raw/kwabor_intro.mp4` et `res/drawable-nodpi/kwabor_intro_fallback.png` ;
- iOS : `KwaborIntro.mp4` et l'image set `IntroFallback`.

Le remplacement distant dépend du consentement Remote Config et des clés documentées dans [Observabilité](observability.md). La configuration n'est acceptée que si l'URL est HTTPS, le SHA-256 comporte 64 caractères hexadécimaux et la révision est positive. Après consentement, un listener temps réel permet de précharger une publication du super-admin sans attendre le prochain fetch périodique.

Après téléchargement, chaque client exige :

- réponse `video/mp4` et URL finale HTTPS ;
- taille maximale de 3 Mio ;
- SHA-256 identique à la configuration ;
- vidéo portrait H.264 de 15 à 25 secondes, sans piste audio.

Le fichier n'est rendu actif qu'après validation et remplacement atomique. La source est figée pendant toute lecture : une publication reçue en cours de session ne redémarre jamais la vidéo et ne surgit pas au-dessus d'un autre écran. La révision est proposée une seule fois au lancement suivant. En cas d'échec de lecture distante, le client revient à l'actif embarqué. Révoquer le consentement ferme le listener temps réel, annule le téléchargement, supprime le cache et la révision en attente, puis restaure les valeurs sûres.

## Publication par le super-admin

La console Firebase est l'interface opérationnelle V1 ; aucun nouveau client web n'est introduit. Pour publier une intro :

> **Dépendance avant activation réelle** : cette mécanique client est provisionnée par AUTH-002, mais aucun utilisateur n'autorise encore Remote Config. Elle devient opérable uniquement après raccordement du consentement dans AUTH-003 et provisionnement Firebase staging/production dans ENV-001B/OBS-001B. Elle ne doit pas être annoncée comme active en bêta avant ces deux preuves.

1. encoder et contrôler le MP4 avec les mêmes invariants que l'actif embarqué ;
2. déposer le fichier sur le CDN HTTPS approuvé, sans redirection ;
3. calculer son SHA-256 ;
4. publier ensemble `intro_video_enabled=true`, l'URL, le SHA-256 et une révision strictement supérieure à toutes les révisions précédentes ;
5. vérifier sur staging le préchargement, la lecture au lancement suivant, le mode hors ligne et la non-répétition avant publication production.

Pour retirer une campagne, publier `intro_video_enabled=false`. Pour revenir à un ancien contenu, republier son fichier et son hash avec une **nouvelle** révision supérieure : réutiliser un ancien numéro serait ignoré par les clients qui l'ont déjà présenté. Une publication est détectée rapidement par les applications consenties et au premier plan ; un appareil hors ligne la récupère lors d'une exécution ultérieure et conserve toujours l'actif embarqué comme repli.

## Vérification avant livraison

1. Nouvelle installation sans réseau : intro locale, bouton Passer et landing visibles.
2. Réduction des animations active : image statique et bouton Continuer visibles, aucune lecture vidéo.
3. Confirmation invité : navigation racine disponible ; interaction protégée renvoie vers l'authentification.
4. Nouveau lancement sans session et sans nouvelle révision : landing affichée sans rejouer l'intro.
5. Remote Config refusé ou absent : aucun téléchargement média.
6. Remote Config consenti et média valide : variante préchargée puis utilisée une seule fois au lancement suivant.
7. Hash, MIME, codec, durée ou taille invalides : fallback local et aucun message technique à l'écran.
8. Révocation : cache distant supprimé et fallback local restauré.
9. Publication d'une révision supérieure pendant une session : aucun écran interrompu ; la variante apparaît une fois au prochain lancement.
10. Relance suivante sans nouvelle révision : la variante ne rejoue pas.
