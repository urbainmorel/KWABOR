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

## AUTH-005 implémentée sur branche, validations finales en attente

Google est acquis nativement sur Android et iOS. Sign in with Apple est acquis par
`AuthenticationServices` sur iOS uniquement et apparaît au même niveau que Google. Chaque tentative
utilise un nonce aléatoire à usage unique ; seuls l'ID token, le nonce brut correspondant et les
indices de nom éventuellement fournis sont transmis au data layer partagé. Aucun access token ou
refresh token Google/Apple n'est demandé, persisté ou envoyé à Analytics.

Un compte complet ouvre la destination protégée attendue. Un compte nouveau ou incomplet reprend la
révision Nom/Prénom, puis Ville/GPS, Devise, consentements et primer notifications. Les indices de
nom restent modifiables et ne finalisent jamais le profil à eux seuls. Apple pouvant ne fournir le
nom qu'à la première autorisation, l'écran de révision reste utilisable sans indice. Une annulation
du fournisseur ne crée pas de session et ne montre aucun message technique.

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

Le GPS reste facultatif. Android ne demande que `ACCESS_COARSE_LOCATION` et iOS utilise une précision kilométrique ; les coordonnées ne sont ni envoyées au backend ni persistées. Elles servent uniquement à choisir localement la ville béninoise la plus proche. Un refus, une position indisponible ou hors du Bénin ramène toujours vers la sélection manuelle.

Les trois consentements observabilité sont appliqués et persistés par les adaptateurs Firebase natifs lorsque l'utilisateur confirme cette étape, juste avant `complete_user_onboarding`. Ainsi, une réponse réseau perdue après le commit serveur ne peut pas effacer son choix explicite ; chaque nouvelle confirmation réapplique la dernière valeur sélectionnée. Autoriser Remote Config rend alors opérationnel le préchargement de l'intro distante décrit ci-dessous.

La permission notifications n'arrive qu'après le succès serveur et reste non bloquante, qu'elle soit acceptée, refusée ou remise à plus tard ; l'enregistrement du token est réservé à la tranche Notifications. La résolution de cet écran est persistée localement par installation avant d'ouvrir l'accueil. Si l'application est arrêtée après la finalisation serveur mais avant ce choix, une session complète restaurée reprend donc le primer au lieu de le perdre ou de le contourner. Une écriture locale Android en échec conserve l'écran avec une action de retry ; les doubles appuis ne peuvent jamais ouvrir deux demandes système.

## Média embarqué et distant

Les actifs de repli sont versionnés avec chaque client :

- Android : `res/raw/kwabor_intro.mp4` et `res/drawable-nodpi/kwabor_intro_fallback.png` ;
- iOS : `KwaborIntro.mp4` et l'image set `IntroFallback`.

`tools/verify-onboarding-media.py` impose en CI des MP4 byte-identical sur les deux plateformes et
verrouille aussi la présence, les octets et les dimensions de l'image statique commune.

Le raccord de lancement utilise séparément le master `kwabor_2.png`, copié bit pour bit dans les ressources Android et iOS. Le format officiel 2172 × 724, son ratio 3:1, son mode RGBA opaque et son SHA-256 sont contrôlés en CI par `tools/verify-brand-assets.py`. Android conserve le symbole carré pendant le splash système masqué, puis affiche immédiatement le wordmark en `Fit`. iOS l'affiche dès `LaunchScreen.storyboard` en `scaleAspectFit`. Sur les deux plateformes, il reste au-dessus du lecteur jusqu'au signal natif de première frame ; le démarrage hors ligne ne dépend donc jamais du réseau ni d'un décodage déjà prêt.

Le remplacement distant dépend du consentement Remote Config et des clés documentées dans [Observabilité](observability.md). La configuration n'est acceptée que si l'URL est HTTPS, le SHA-256 comporte 64 caractères hexadécimaux et la révision est positive. Après consentement, un listener temps réel permet de précharger une publication du super-admin sans attendre le prochain fetch périodique.

Après téléchargement, chaque client exige :

- réponse `video/mp4` et URL finale HTTPS ;
- taille maximale de 3 Mio ;
- SHA-256 identique à la configuration ;
- vidéo portrait H.264 de 15 à 25 secondes, sans piste audio.

Le fichier n'est rendu actif qu'après validation et remplacement atomique. La source est figée pendant toute lecture : une publication reçue en cours de session ne redémarre jamais la vidéo et ne surgit pas au-dessus d'un autre écran. Les candidats sont qualifiés dans l'ordre et la révision valide la plus élevée est proposée une seule fois au lancement suivant. Une configuration indisponible ou un média plus récent mais invalide ne remplace ni n'efface cette dernière révision validée ; seule une désactivation explicite reçue du service le fait. Une erreur de lecture locale transitoire utilise le fallback pour ce lancement et conserve la révision pour une tentative ultérieure. Après le premier lancement, l'absence de révision distante prête ne rejoue pas la vidéo locale : l'application continue vers l'authentification ou l'accueil. Si le décodage d'une révision déjà ouverte échoue, le client affiche l'image statique, met cette révision en quarantaine et permet de continuer sans message technique. Révoquer le consentement ferme le listener temps réel, annule le téléchargement et persiste d'abord une intention de purge. Métadonnée et cache doivent être supprimés puis cette intention acquittée avant toute nouvelle qualification ; une purge interrompue ou en échec est reprise au redémarrage. L'historique anti-rejeu reste conservé.

## Publication par le super-admin

La console Firebase est l'interface opérationnelle V1 ; aucun nouveau client web n'est introduit. Le pas-à-pas, la frontière Store/distant et le registre des révisions sont définis dans le [runbook de publication vidéo](runbooks/onboarding-video-publication.md). Pour publier une intro :

> **Dépendance avant activation réelle** : le consentement client est raccordé par AUTH-003. La mécanique ne devient néanmoins opérable qu'après provisionnement Firebase staging/production dans ENV-001B/OBS-001B et vérification sur appareils. Elle ne doit pas être annoncée comme active en bêta avant ces preuves.

1. encoder et contrôler le MP4 candidat avec `tools/verify-onboarding-media.py --input` ;
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
7. Hash, MIME, codec, durée ou taille invalides avant ouverture : aucun écran intro ajouté et aucun message technique à l'écran ; échec de décodage après ouverture : image statique puis révision mise en quarantaine.
8. Révocation : cache et attente distants supprimés, dernière révision présentée conservée, puis route normale sans rejeu local.
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
26. Google annulé sur Android/iOS : aucun compte créé, aucune erreur technique et destination en attente conservée.
27. Apple annulé sur iOS : même comportement que Google ; aucun bouton Apple visible sur Android.
28. ID token absent, nonce absent/réutilisé ou audience d'un autre environnement : authentification refusée sans secret dans les logs.
29. Nouveau compte Google/Apple : révision du nom puis onboarding complet ; compte existant complet : connexion directe.
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
