# Onboarding mobile

## Fondation livrée par AUTH-002

Au premier lancement, Android Compose et iOS SwiftUI affichent une intro portrait silencieuse. Le bouton **Passer** reste immédiatement disponible. Le logo horizontal officiel reste visible entre le lancement natif et la première frame vidéo. Lorsque la réduction des animations est active, l'application affiche l'image de repli statique embarquée et un bouton **Continuer** sans démarrer la vidéo.

Après l'intro, un utilisateur non connecté peut ouvrir le flux OTP ou demander un accès invité. Avant de confirmer cet accès, l'application précise que les prix restent en FCFA et que les interactions nécessitent un compte. L'accès invité ouvre le mur Explore en lecture seule ; toucher une destination protégée conserve le mur souple d'authentification.

L'intro embarquée n'est affichée qu'une fois par installation. Chaque nouvelle révision distante validée peut ensuite être affichée une seule fois, au lancement suivant son préchargement. L'accès invité n'est pas persisté : au prochain lancement sans session authentifiée, l'écran de connexion est présenté, sauf si une nouvelle révision d'intro est en attente.

## Inscription livrée par AUTH-003

Android Compose et iOS SwiftUI suivent le même parcours unidirectionnel :

1. email puis OTP de 6 chiffres, avec renvoi après 30 secondes ;
2. mot de passe d'au moins 8 caractères, jamais conservé dans l'état UI ;
3. prénom et nom, chacun limité à 80 caractères ;
4. ville choisie manuellement ou estimée localement depuis une localisation approximative ponctuelle ;
5. devise d'affichage XOF, NGN, USD ou EUR, XOF restant la devise de stockage et de paiement ;
6. consultation et acceptation séparée des CGU, de la politique de confidentialité et de la licence UGC actives ;
7. choix facultatifs et désactivés par défaut pour Analytics, diagnostics et Remote Config ;
8. finalisation serveur atomique, puis écran d'explication avant la demande système de notifications.

L'OTP crée une session Supabase avant la fin du profil. Cette session porte le statut `OnboardingRequired` et n'est jamais considérée comme authentifiée par la navigation. La RPC vérifie elle-même que le compte email possède désormais un mot de passe avant toute écriture : un client modifié ne peut donc pas sauter cette étape. Si l'application est interrompue après vérification, elle reprend au minimum à l'étape du mot de passe et ne peut pas ouvrir l'accueil. Quitter le parcours après OTP déclenche d'abord une déconnexion confirmée ; un échec réseau conserve l'écran ouvert et affiche seulement un message utilisateur traduit.

## Connexion et récupération livrées par AUTH-004

La connexion est distincte de l'inscription sur les deux plateformes : l'utilisateur saisit
d'abord son email, puis son mot de passe. Le bouton de connexion ne peut jamais demander ou
vérifier l'OTP de création de compte. Un compte incomplet reprend son onboarding après une
connexion valide ; un compte complet reprend la destination protégée qui avait ouvert le mur
d'authentification.

Le parcours « Mot de passe oublié » suit les mêmes étapes en Compose et SwiftUI : email,
OTP Recovery de 6 chiffres, nouveau mot de passe et confirmation, puis retour à la connexion.
La réponse après demande du code reste identique que l'adresse existe ou non. Le renvoi n'est
disponible qu'après 30 secondes. Les OTP et mots de passe ne sont jamais conservés dans un
état UI persistant, une destination de navigation ou un log.

La vérification d'un OTP Recovery crée techniquement une session Supabase temporaire. Kwabor la
classe explicitement comme récupération et refuse de la traiter comme une session utilisateur
complète. Un arrêt de l'application avant le nouveau mot de passe reprend donc la
récupération, jamais l'accueil. Un succès ou une annulation ferme cette session locale avant le
retour au parcours public.

La déconnexion utilisateur est accessible depuis Profil et exige une confirmation destructive.
Elle retire la session de cet appareil, les destinations protégées en attente et revient sur
l'accueil invité. La révocation des autres appareils reste réservée aux paramètres de sécurité.

Le GPS reste facultatif. Android ne demande que `ACCESS_COARSE_LOCATION` et iOS utilise une précision kilométrique ; les coordonnées ne sont ni envoyées au backend ni persistées. Elles servent uniquement à choisir localement la ville béninoise la plus proche. Un refus, une position indisponible ou hors du Bénin ramène toujours vers la sélection manuelle.

Les trois consentements observabilité sont appliqués et persistés par les adaptateurs Firebase natifs lorsque l'utilisateur confirme cette étape, juste avant `complete_user_onboarding`. Ainsi, une réponse réseau perdue après le commit serveur ne peut pas effacer son choix explicite ; chaque nouvelle confirmation réapplique la dernière valeur sélectionnée. Autoriser Remote Config rend alors opérationnel le préchargement de l'intro distante décrit ci-dessous.

La permission notifications n'arrive qu'après le succès serveur et reste non bloquante, qu'elle soit acceptée, refusée ou remise à plus tard ; l'enregistrement du token est réservé à la tranche Notifications. La résolution de cet écran est persistée localement par installation avant d'ouvrir l'accueil. Si l'application est arrêtée après la finalisation serveur mais avant ce choix, une session complète restaurée reprend donc le primer au lieu de le perdre ou de le contourner. Une écriture locale Android en échec conserve l'écran avec une action de retry ; les doubles appuis ne peuvent jamais ouvrir deux demandes système.

## Média embarqué et distant

Les actifs de repli sont versionnés avec chaque client :

- Android : `res/raw/kwabor_intro.mp4` et `res/drawable-nodpi/kwabor_intro_fallback.png` ;
- iOS : `KwaborIntro.mp4` et l'image set `IntroFallback`.

Le raccord de lancement utilise séparément le master `kwabor_2.png`, copié bit pour bit dans les ressources Android et iOS. Le format officiel 2172 × 724, son ratio 3:1, son mode RGBA opaque et son SHA-256 sont contrôlés en CI par `tools/verify-brand-assets.py`. Android conserve le symbole carré pendant le splash système masqué, puis affiche immédiatement le wordmark en `Fit`. iOS l'affiche dès `LaunchScreen.storyboard` en `scaleAspectFit`. Sur les deux plateformes, il reste au-dessus du lecteur jusqu'au signal natif de première frame ; le démarrage hors ligne ne dépend donc jamais du réseau ni d'un décodage déjà prêt.

Le remplacement distant dépend du consentement Remote Config et des clés documentées dans [Observabilité](observability.md). La configuration n'est acceptée que si l'URL est HTTPS, le SHA-256 comporte 64 caractères hexadécimaux et la révision est positive. Après consentement, un listener temps réel permet de précharger une publication du super-admin sans attendre le prochain fetch périodique.

Après téléchargement, chaque client exige :

- réponse `video/mp4` et URL finale HTTPS ;
- taille maximale de 3 Mio ;
- SHA-256 identique à la configuration ;
- vidéo portrait H.264 de 15 à 25 secondes, sans piste audio.

Le fichier n'est rendu actif qu'après validation et remplacement atomique. La source est figée pendant toute lecture : une publication reçue en cours de session ne redémarre jamais la vidéo et ne surgit pas au-dessus d'un autre écran. La révision est proposée une seule fois au lancement suivant. En cas d'échec de lecture distante, le client revient à l'actif embarqué. Révoquer le consentement ferme le listener temps réel, annule le téléchargement, supprime le cache et la révision en attente, puis restaure les valeurs sûres.

## Publication par le super-admin

La console Firebase est l'interface opérationnelle V1 ; aucun nouveau client web n'est introduit. Pour publier une intro :

> **Dépendance avant activation réelle** : le consentement client est raccordé par AUTH-003. La mécanique ne devient néanmoins opérable qu'après provisionnement Firebase staging/production dans ENV-001B/OBS-001B et vérification sur appareils. Elle ne doit pas être annoncée comme active en bêta avant ces preuves.

1. encoder et contrôler le MP4 avec les mêmes invariants que l'actif embarqué ;
2. déposer le fichier sur le CDN HTTPS approuvé, sans redirection ;
3. calculer son SHA-256 ;
4. publier ensemble `intro_video_enabled=true`, l'URL, le SHA-256 et une révision strictement supérieure à toutes les révisions précédentes ;
5. vérifier sur staging le préchargement, la lecture au lancement suivant, le mode hors ligne et la non-répétition avant publication production.

Pour retirer une campagne, publier `intro_video_enabled=false`. Pour revenir à un ancien contenu, republier son fichier et son hash avec une **nouvelle** révision supérieure : réutiliser un ancien numéro serait ignoré par les clients qui l'ont déjà présenté. Une publication est détectée rapidement par les applications consenties et au premier plan ; un appareil hors ligne la récupère lors d'une exécution ultérieure et conserve toujours l'actif embarqué comme repli.

## Vérification avant livraison

1. Nouvelle installation sans réseau : logo officiel complet sans flash vide, intro locale, bouton Passer et landing visibles.
2. Réduction des animations active : image de repli statique et bouton Continuer visibles, aucune lecture vidéo.
3. Confirmation invité : navigation racine disponible ; interaction protégée renvoie vers l'authentification.
4. Nouveau lancement sans session et sans nouvelle révision : landing affichée sans rejouer l'intro.
5. Remote Config refusé ou absent : aucun téléchargement média.
6. Remote Config consenti et média valide : variante préchargée puis utilisée une seule fois au lancement suivant.
7. Hash, MIME, codec, durée ou taille invalides : fallback local et aucun message technique à l'écran.
8. Révocation : cache distant supprimé et fallback local restauré.
9. Publication d'une révision supérieure pendant une session : aucun écran interrompu ; la variante apparaît une fois au prochain lancement.
10. Relance suivante sans nouvelle révision : la variante ne rejoue pas.
11. OTP vérifié puis application arrêtée : reprise au mot de passe, jamais à l'accueil.
12. Annulation après OTP avec déconnexion en échec : parcours maintenu ouvert et session incomplète inutilisable comme compte finalisé.
13. GPS refusé, indisponible ou hors Bénin : sélection manuelle utilisable sans coordonnée transmise.
14. Documents juridiques absents, dupliqués, inactifs ou non effectifs : finalisation bloquée sans créer de profil partiel.
15. Consentements observabilité refusés : aucune collecte ni récupération Remote Config ; inscription toujours finalisable.
16. Permission notifications refusée ou différée : compte finalisé et navigation débloquée sans token enregistré.
17. Application arrêtée après la RPC mais avant le choix notifications : session restaurée sur le primer, puis résolution persistée avant l'accueil.
18. Double appui sur « Autoriser » : une seule demande système ; échec de persistance locale Android : primer maintenu avec retry.
19. « Se connecter » exige le mot de passe et ne déclenche jamais l'OTP d'inscription.
20. Adresse de récupération inconnue : même confirmation visible qu'une adresse connue, sans fuite d'existence du compte.
21. OTP Recovery invalide, expiré ou renvoyé trop tôt : état conservé et message utilisateur sûr, sans session authentifiée.
22. Application arrêtée après l'OTP Recovery : reprise au nouveau mot de passe, jamais à l'accueil.
23. Mot de passe Recovery faible, identique ou non concordant : mise à jour refusée sans perdre la session temporaire.
24. Récupération terminée ou annulée : session temporaire effacée et retour à la connexion.
25. Déconnexion confirmée : session et destination protégée en attente effacées, accueil invité affiché.
