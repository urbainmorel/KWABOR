# Onboarding mobile

## Comportement livré par AUTH-002

Au premier lancement, Android Compose et iOS SwiftUI affichent une intro portrait silencieuse. Le bouton **Passer** reste immédiatement disponible. Lorsque la réduction des animations est active, l'application affiche l'image statique embarquée et un bouton **Continuer**.

Après l'intro, un utilisateur non connecté peut ouvrir le flux OTP ou demander un accès invité. Avant de confirmer cet accès, l'application précise que les prix restent en FCFA et que les interactions nécessitent un compte. L'accès invité ouvre le mur Explore en lecture seule ; toucher une destination protégée conserve le mur souple d'authentification.

L'intro n'est affichée qu'une fois par installation. L'accès invité n'est pas persisté : au prochain lancement sans session authentifiée, l'écran de connexion est présenté.

## Média embarqué et distant

Les actifs de repli sont versionnés avec chaque client :

- Android : `res/raw/kwabor_intro.mp4` et `res/drawable-nodpi/kwabor_intro_fallback.png` ;
- iOS : `KwaborIntro.mp4` et l'image set `IntroFallback`.

Le remplacement distant dépend du consentement Remote Config et des clés documentées dans [Observabilité](observability.md). La configuration n'est acceptée que si l'URL est HTTPS, le SHA-256 comporte 64 caractères hexadécimaux et la révision est positive.

Après téléchargement, chaque client exige :

- réponse `video/mp4` et URL finale HTTPS ;
- taille maximale de 5 Mio ;
- SHA-256 identique à la configuration ;
- vidéo portrait H.264 de 15 à 25 secondes.

Le fichier n'est rendu actif qu'après validation et remplacement atomique. En cas d'échec, le fallback embarqué reste actif. Révoquer le consentement annule le téléchargement, supprime le cache distant et restaure les valeurs sûres.

## Vérification avant livraison

1. Nouvelle installation sans réseau : intro locale, bouton Passer et landing visibles.
2. Réduction des animations active : image statique et bouton Continuer visibles, aucune lecture vidéo.
3. Confirmation invité : navigation racine disponible ; interaction protégée renvoie vers l'authentification.
4. Nouveau lancement sans session : landing affichée sans rejouer l'intro.
5. Remote Config refusé ou absent : aucun téléchargement média.
6. Remote Config consenti et média valide : variante en cache utilisée au lancement suivant.
7. Hash, MIME, codec, durée ou taille invalides : fallback local et aucun message technique à l'écran.
8. Révocation : cache distant supprimé et fallback local restauré.
