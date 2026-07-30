# Onboarding mobile

## Fondation livrée par AUTH-002

Au premier lancement, Android Compose et iOS SwiftUI affichent une intro portrait silencieuse. Les CTA **S'inscrire**, **Se connecter** et **Continuer sans compte** sont disponibles immédiatement au-dessus de la vidéo ; celle-ci ne bloque jamais l'interaction. **Passer** arrête seulement la lecture et conserve la même surface sur l'image statique. Le logo horizontal officiel reste visible entre le lancement natif et la première frame. Le mouvement réduit, un échec de lecture et les lancements suivants utilisent directement le fallback statique.

Après l'intro, un utilisateur non connecté peut ouvrir le flux OTP ou demander un accès invité. Avant de confirmer cet accès, l'application précise que les prix restent en FCFA et que les interactions nécessitent un compte. L'accès invité ouvre le mur Explore en lecture seule ; toucher une destination protégée conserve le mur souple d'authentification.

L'intro embarquée n'est affichée qu'une fois par installation. Chaque nouvelle révision distante validée peut ensuite être affichée une seule fois, au lancement suivant son préchargement. L'accès invité n'est pas persisté : au prochain lancement sans session authentifiée, l'écran de connexion est présenté, sauf si une nouvelle révision d'intro est en attente.

## Inscription livrée par AUTH-003

Android Compose et iOS SwiftUI suivent le même parcours unidirectionnel :

1. email puis OTP de 6 chiffres, avec collage/autoremplissage natif, soumission automatique, correction de l'email et renvoi après 30 secondes ;
2. un seul champ de mot de passe d'au moins 8 caractères, compatible gestionnaire de mots de passe et jamais conservé dans l'état UI ;
3. un écran défilant **Finaliser mon profil** : prénom, nom, ville recherchable, devise XOF par défaut et trois acceptations juridiques séparées avec liens HTTPS et versions visibles ;
4. finalisation serveur atomique puis fermeture immédiate du tunnel, sans écran succès ni permission.

Le chemin email compte quatre écrans maximum et affiche `2/4`, `3/4`, `4/4` seulement après le choix de méthode. Google et Apple ouvrent directement le profil final avec les noms préremplis et modifiables ; la progression affiche **Dernière étape**. Les villes et documents légaux sont préchargés dès l'ouverture. Une erreur offre un retry ciblé sans perdre les saisies. Une ville n'est suggérée que si la softwall provient d'un lieu dont le `cityId` est valide.

Aucune géolocalisation, permission notification ou demande de consentement d'observabilité n'appartient plus au tunnel. Les nouveaux comptes conservent Analytics, diagnostics et Remote Config à `false` jusqu'à leur future gestion dans Réglages ; l'intro locale reste donc le comportement par défaut.

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

## AUTH-005 implémentée sur branche, validations finales en attente

Google est acquis nativement sur Android et iOS. Sign in with Apple est acquis par
`AuthenticationServices` sur iOS uniquement et apparaît au même niveau que Google. Chaque tentative
utilise un nonce aléatoire à usage unique ; seuls l'ID token, le nonce brut correspondant et les
indices de nom éventuellement fournis sont transmis au data layer partagé. Aucun access token ou
refresh token Google/Apple n'est demandé, persisté ou envoyé à Analytics.

Un compte complet ouvre la destination protégée attendue. Un compte nouveau ou incomplet reprend
directement **Finaliser mon profil** après Google/Apple, ou le mot de passe/profil selon la session
email. Les indices de nom restent modifiables et ne finalisent jamais le profil à eux seuls. Apple
pouvant ne fournir le nom qu'à la première autorisation, le profil final reste utilisable sans
indice. Une annulation du fournisseur ne crée pas de session et ne montre aucun message technique.

### Activation Promoteur

Le lien accepté est strictement `kwabor://auth/promoter-activate` avec un token d'invitation et,
si le callback doit établir une session temporaire, un code d'autorisation PKCE. Les fragments et
jetons implicites, ainsi que les schémas, hosts, chemins, paramètres inconnus ou dupliqués et valeurs
hors bornes, sont rejetés.

Le serveur n'affiche le nom du commerce que si le compte Auth possède une adresse confirmée
identique à celle de l'invitation. Une session déjà connectée n'est jamais remplacée par la preuve
du callback. Sans session, une session temporaire peut être importée pour la prévisualisation ; elle
est effacée si le lien est invalide ou si l'utilisateur annule.

L'utilisateur choisit ensuite un mot de passe ou une identité sociale native. L'activation serveur
ré-authentifie exactement le même utilisateur et exige dans son JWT une preuve `password` ou
`oauth` âgée de cinq minutes au maximum. L'activation serveur est atomique et peut uniquement
attribuer `promoteur` vérifié et le rôle d'organisation `editeur`.
Elle ne donne jamais Propriétaire, Gestionnaire ou Admin, et ne transfère pas la propriété de la
fiche. Le succès affiche le nom réel du commerce et propose le retour à l'accueil. Une destination
Promoteur typée est conservée de façon privée pour que B2B-003 ouvre le vrai tableau de bord lorsqu'il
sera livré ; aucun placeholder n'est présenté comme dashboard.

Un échec réseau pendant la restauration iOS bloque les actions Auth et invité jusqu'au retry. Un
callback Promoteur provisoire en erreur est déconnecté et son marqueur effacé avant l'affichage de
l'erreur. Une suppression de compte en cours invalide toute file ou callback Promoteur concurrent :
la suppression gagne toujours, sans réimporter ensuite une session devenue invalide.

### Danger Zone et suppression de compte

La suppression exige la confirmation exacte `SUPPRIMER`, puis une ré-authentification récente par
mot de passe ou par un nouvel ID token Google/Apple avec nonce. La fonction serveur compare
l'identité ré-authentifiée au bearer courant, vérifie les blocages de propriété d'organisation et
d'objets Storage, puis prépare l'effacement avec une clé d'idempotence. Les politiques Storage
restrictives partagent le verrou de suppression : un upload déjà engagé finit avant la vérification,
et tout nouvel upload attend puis échoue dès que le tombstone existe.

La préparation supprime le profil, les rôles, acceptations juridiques et données utilisateur
rattachées, et neutralise les attributions résiduelles de fiches. La fonction révoque ensuite toutes
les sessions, revalide propriété et Storage, puis supprime l'utilisateur Supabase Auth. Un retry
réutilise la même opération serveur, y compris si l'application redémarre avec une nouvelle clé
client.

Le tombstone privé `account_deletion_requests` conserve seulement un identifiant utilisateur
pseudonyme, une clé d'idempotence, un statut et des horodatages. Il ne contient aucun email, nom,
contenu ou credential. Une ligne `prepared` interdit les écritures produit jusqu'à reprise. Si le
compte Auth existe encore, l'utilisateur reprend avec une preuve fraîche ; s'il a déjà disparu, la
réconciliation privilégiée quotidienne refait le nettoyage idempotent puis clôt le tombstone. Les
tombstones complétés sont techniquement purgés après 30 jours. Cette durée et sa mention dans la
politique de confidentialité restent une gate juridique avant release candidate.

La ville est une préférence de profil obligatoire mais ne déclenche aucun accès GPS pendant
l'inscription. Les trois consentements d'observabilité déjà enregistrés sont préservés ; aucun
nouveau choix n'est collecté dans ce parcours. Une session complète restaurée ouvre directement
l'accueil, sans primer notifications.

La softwall conserve un contexte minimal : type d'action et `suggestedCityId` facultatif. Elle
propose directement les fournisseurs, l'email, la connexion existante et **Plus tard**. Après
authentification, Like ou Favori est rejoué une seule fois puis le contexte est effacé. Une
annulation explicite ou **Plus tard** l'efface ; une erreur réseau ou fournisseur récupérable le
conserve.

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

1. Nouvelle installation sans réseau : logo officiel complet sans flash vide, intro locale et CTA d'accès immédiatement utilisables.
2. Réduction des animations active : image de repli statique et mêmes CTA visibles, aucune lecture vidéo.
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
13. Inscription générique : aucune ville présélectionnée ; softwall liée à un lieu valide : sa ville est proposée.
14. Documents juridiques absents, dupliqués, inactifs ou non effectifs : finalisation bloquée avec retry ciblé sans perdre les saisies.
15. Nouveau compte sans consentement antérieur : aucune collecte ni récupération Remote Config ; inscription toujours finalisable.
16. Finalisation réussie : zéro permission, fermeture immédiate et accueil direct.
17. Réponse réseau tardive du préchargement : nom, ville, devise et acceptations déjà saisis restent inchangés.
18. Collage OTP ou double événement UI : une seule vérification envoyée.
19. « Se connecter » exige le mot de passe et ne déclenche jamais l'OTP d'inscription.
20. Adresse de récupération inconnue : même confirmation visible qu'une adresse connue, sans fuite d'existence du compte.
21. OTP Recovery invalide, expiré ou renvoyé trop tôt : état conservé et message utilisateur sûr, sans session authentifiée.
22. Application arrêtée après l'OTP Recovery : reprise au nouveau mot de passe, jamais à l'accueil.
23. Mot de passe Recovery faible, identique ou non concordant : mise à jour refusée sans perdre la session temporaire.
24. Récupération terminée ou annulée : session temporaire effacée et retour à la connexion.
25. Déconnexion confirmée : session et destination protégée en attente effacées, accueil invité affiché.
26. Google annulé sur Android/iOS : aucun compte créé, aucune erreur technique et destination en attente conservée.
27. Apple annulé sur iOS : même comportement que Google ; aucun bouton Apple visible sur Android.
28. ID token absent, nonce absent/réutilisé ou audience d'un autre environnement : authentification refusée sans secret dans les logs.
29. Nouveau compte Google/Apple : un seul profil final ; compte existant complet : connexion directe.
30. Apple sans nom lors d'une reconnexion : révision manuelle toujours disponible et aucun nom précédent attribué à une autre identité.
31. Invitation Promoteur invalide, expirée, utilisée ou destinée à un autre email : activation refusée sans révéler l'email attendu.
32. Lien Promoteur reçu avec une session existante : session jamais remplacée ni déconnectée, y compris si le lien est invalide.
33. Lien Promoteur sans session : session temporaire conservée jusqu'à activation, puis supprimée en cas d'annulation ou de prévisualisation invalide.
34. Activation réussie : rôle Promoteur vérifié et rôle Éditeur seulement ; aucun rôle critique ni transfert de `owner_id`.
35. Suppression sans la phrase exacte, avec mot de passe faux ou identité sociale différente : aucune préparation ni révocation.
36. Propriété d'organisation ou objets Storage restants : suppression bloquée avec message utilisateur sûr et données intactes avant résolution.
37. Double appui, retry réseau ou redémarrage avec une autre clé client : une seule préparation effective et aucune double suppression.
38. Échec après état `prepared` avec compte Auth encore présent : reprise utilisateur fraîche ; écritures produit bloquées entre-temps.
39. Compte Auth déjà absent mais tombstone encore `prepared` : réconciliation serveur vers `completed`, sans suppression manuelle d'un compte présent.
40. Suppression réussie : toutes les sessions révoquées, session locale et destinations privées effacées, retour à l'accueil invité.
