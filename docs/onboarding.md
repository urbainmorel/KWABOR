# Onboarding mobile

## Fondation livrée par AUTH-002

Au premier lancement, Android Compose et iOS SwiftUI affichent une intro portrait silencieuse. Les CTA **S'inscrire**, **Se connecter** et **Continuer sans compte** sont disponibles immédiatement au-dessus de la vidéo ; celle-ci ne bloque jamais l'interaction. **Passer** arrête seulement la lecture et conserve la même surface sur l'image statique. Le logo horizontal officiel reste visible entre le lancement natif et la première frame. Le mouvement réduit, un échec de lecture et les lancements suivants utilisent directement le fallback statique.

Après l'intro, un utilisateur non connecté peut ouvrir le flux OTP ou demander un accès invité. Avant de confirmer cet accès, l'application précise que les prix restent en FCFA et que les interactions nécessitent un compte. L'accès invité ouvre le mur Explore en lecture seule ; toucher une destination protégée conserve le mur souple d'authentification.

L'intro embarquée est affichée une fois par révision installée. La révision initiale vaut `1` sur Android et iOS ; une version Store qui conserve cette valeur ne rejoue pas l'intro. Une révision strictement supérieure est proposée une seule fois au lancement suivant son installation. L'accès invité n'est pas persisté : au prochain lancement sans session authentifiée et sans nouvelle révision embarquée, l'écran de connexion est présenté.

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

## Auth fédérée, activation Promoteur et suppression livrées par AUTH-005

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
mot de passe ou par un nouvel ID token Google/Apple avec nonce. Le data layer crée pour chaque
tentative un client Supabase Auth/Functions dédié avec `MemorySessionManager`, sans persistance,
auto-refresh ni callbacks de cycle de vie, et avec `LogLevel.NONE`. Le secret primaire est envoyé
exclusivement à Supabase Auth. L'identifiant obtenu doit être strictement égal à celui de la session
principale ; une identité différente échoue sans remplacer ni effacer cette session.

Le client éphémère appelle ensuite `account-delete` avec un body JSON contenant exactement
`idempotency_key`. L'Edge Function exige que `userClaims.id`, `jwtClaims.sub` et l'utilisateur live
retourné par `getUser()` désignent le même UUID. Le claim `session_id` doit être un UUID et l'entrée
AMR la plus récente doit être `password` ou `oauth`, dater d'au plus 300 secondes et ne pas dépasser
l'horloge serveur de plus de 30 secondes. Toute AMR absente, malformée, OTP, magic link, Recovery,
token refresh, trop ancienne ou trop future est refusée.

La première mutation utilise le RPC privilégié `prepare_account_deletion_with_session` : il vérifie
et verrouille atomiquement la ligne `auth.sessions` du même utilisateur avant de préparer
l'effacement. La fonction vérifie ensuite les blocages de propriété d'organisation et d'objets
Storage. Les politiques Storage restrictives partagent le verrou de suppression : un upload déjà
engagé finit avant la vérification, et tout nouvel upload attend puis échoue dès que le tombstone
existe.

La préparation anonymise les invitations, supprime les données applicatives rejouables, les rôles et
les acceptations juridiques, puis neutralise les attributions résiduelles de fiches. Elle remplace
provisoirement le profil par une sentinelle pseudonymisée comme ancre de routage, la masque aux
lecteurs publics et interdit toute mutation grâce au tombstone. La fonction révoque ensuite toutes
les sessions,
revalide propriété et Storage, puis supprime l'utilisateur Supabase Auth. Si une tentative s'arrête
avec un tombstone `prepared` et un utilisateur Auth encore présent, l'utilisateur se reconnecte au
même compte, rouvre la Danger Zone et crée une nouvelle session éphémère ; la clé effective serveur
est reprise, y compris après redémarrage ou expiration de l'ancien token. La sentinelle retenue est
supprimée seulement après la disparition de l'utilisateur Auth. À partir de ce point, aucune preuve
utilisateur ne peut être recréée : seule la réconciliation serveur termine l'opération.

Le body ne transporte plus aucun mot de passe, ID token, nonce, email ou fournisseur. L'ouverture aux
utilisateurs staging et production reste néanmoins interdite tant que les AMR réellement émises pour
email/mot de passe, Google et Apple ne sont pas prouvées, et tant que la politique d'accès, de
rétention et d'expurgation des en-têtes d'invocation et des éventuels Log Drains n'est pas validée.
Seuls des comptes synthétiques servent à ces preuves staging. L'en-tête `Authorization` reste un
secret ; voir le
[runbook Auth/session/suppression](runbooks/auth-session-account-deletion-incident.md) et
[l'ADR-0025](adr/0025-ephemeral-account-deletion-step-up-session.md).

Le tombstone privé `account_deletion_requests` conserve seulement un identifiant utilisateur
pseudonyme, une clé d'idempotence, un statut et des horodatages. Il ne contient aucun email, nom,
contenu ou credential. Une ligne `prepared` interdit les écritures produit jusqu'à reprise. Si le
compte Auth existe encore, l'utilisateur reprend avec une session éphémère fraîche ; s'il a déjà
disparu, la réconciliation privilégiée quotidienne refait le nettoyage idempotent puis clôt le
tombstone. Aucun opérateur ne supprime manuellement un utilisateur Auth encore présent. Les
tombstones complétés sont techniquement purgés après 30 jours. Cette durée et sa
mention dans la
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

## Média embarqué et révision Store

Les actifs de repli sont versionnés avec chaque client :

- Android : `res/raw/kwabor_intro.mp4` et `res/drawable-nodpi/kwabor_intro_fallback.png` ;
- iOS : `KwaborIntro.mp4` et l'image set `IntroFallback`.

`tools/verify-onboarding-media.py` impose en CI des MP4 byte-identical sur les deux plateformes et
verrouille aussi la présence, les octets et les dimensions de l'image statique commune. Il exige
une révision embarquée strictement positive et identique dans les deux clients. Avec `--base-ref`,
il refuse un changement vidéo sans incrément de révision et un incrément sans changement vidéo.

Le raccord de lancement utilise séparément le master `kwabor_2.png`, copié bit pour bit dans les ressources Android et iOS. Le format officiel 2172 × 724, son ratio 3:1, son mode RGBA opaque et son SHA-256 sont contrôlés en CI par `tools/verify-brand-assets.py`. Android conserve le symbole carré pendant le splash système masqué, puis affiche immédiatement le wordmark en `Fit`. iOS l'affiche dès `LaunchScreen.storyboard` en `scaleAspectFit`. Sur les deux plateformes, il reste au-dessus du lecteur jusqu'au signal natif de première frame ; le démarrage hors ligne ne dépend donc jamais du réseau ni d'un décodage déjà prêt.

La vidéo ne possède aucun canal Firebase/CDN. La révision `1` est la baseline de migration : une
installation ayant déjà terminé l'ancienne intro est considérée comme l'ayant présentée. Les
anciens fichiers et métadonnées distants sont supprimés sans pouvoir bloquer le lancement. Une
révision plus récente est affichée une seule fois ; sa fin, son passage manuel ou son fallback
marquent cette révision comme présentée avant de poursuivre.

Les événements `intro_video_shown` et `intro_video_skipped` restent soumis au consentement
Analytics. Le tout premier lancement précède ce consentement : ses événements sont donc ignorés
et ne sont jamais mis en attente. Le taux de skip ne couvre que les révisions ultérieures vues par
des installations ayant déjà accordé Analytics ; il ne doit pas être interprété comme une mesure
exhaustive de tous les nouveaux utilisateurs.

## Publication d'une nouvelle vidéo

Le pas-à-pas complet et le registre des révisions sont définis dans le
[runbook de release Store](runbooks/onboarding-video-store-release.md). Une publication exige :

1. encoder le MP4 candidat selon le contrat H.264 commun et enregistrer son SHA-256 ;
2. remplacer les deux MP4 par exactement les mêmes octets ;
3. incrémenter ensemble les constantes Android/iOS, sans modifier la baseline de migration ;
4. exécuter `tools/verify-onboarding-media.py` avec la branche de base, les gates Kotlin/Swift et les preuves sur
   appareils staging ;
5. versionner les builds, archiver hash/droits/approbations, puis publier Android et iOS dans les
   Stores avec rollout contrôlé.

Un retrait ou un retour arrière exige lui aussi une nouvelle révision strictement supérieure et
une release corrective. Remote Config peut continuer à piloter des flags sûrs selon l'ADR-0013,
mais ne peut ni choisir, ni télécharger, ni désactiver la vidéo d'intro.

## Vérification avant livraison

1. Nouvelle installation sans réseau : logo officiel complet sans flash vide, intro locale et CTA d'accès immédiatement utilisables.
2. Réduction des animations active : image de repli statique et mêmes CTA visibles, aucune lecture vidéo.
3. Confirmation invité : navigation racine disponible ; interaction protégée renvoie vers l'authentification.
4. Nouveau lancement sans session et sans nouvelle révision embarquée : landing affichée sans rejouer l'intro.
5. Mise à jour Store avec le même MP4 et la même révision : aucune intro supplémentaire.
6. MP4 changé sans incrément, révision changée sans MP4 ou constantes Android/iOS divergentes : vérification refusée.
7. Version Store avec révision supérieure : intro embarquée utilisée une seule fois au lancement suivant, même hors ligne.
8. Échec de décodage après ouverture : image statique, poursuite sans message technique et révision marquée présentée pour éviter une boucle.
9. Mise à jour installée pendant une session : aucun écran interrompu ; la nouvelle révision apparaît une fois au prochain lancement.
10. Relance suivante avec la même révision : l'intro ne rejoue pas.
11. OTP vérifié puis application arrêtée : reprise au mot de passe, jamais à l'accueil.
12. Annulation après OTP avec déconnexion en échec : parcours maintenu ouvert et session incomplète inutilisable comme compte finalisé.
13. Inscription générique : aucune ville présélectionnée ; softwall liée à un lieu valide : sa ville est proposée.
14. Documents juridiques absents, dupliqués, inactifs ou non effectifs : finalisation bloquée avec retry ciblé sans perdre les saisies.
15. Nouveau compte sans consentement antérieur : aucune collecte ni récupération Remote Config ; inscription toujours finalisable et intro embarquée inchangée.
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
35. Suppression sans la phrase exacte, avec mot de passe faux ou identité sociale différente : aucune préparation ni révocation, et session principale inchangée.
36. Propriété d'organisation ou objets Storage restants : suppression bloquée avec message utilisateur sûr et données intactes avant résolution.
37. Double appui, retry réseau ou redémarrage avec une nouvelle session/clé client : une seule préparation effective et aucune double suppression.
38. Échec après état `prepared` avec compte Auth encore présent : reprise par une ré-authentification éphémère fraîche ; écritures produit bloquées entre-temps.
39. Compte Auth déjà absent mais tombstone encore `prepared` : réconciliation serveur vers `completed`, sans suppression manuelle d'un compte présent.
40. Suppression réussie : toutes les sessions révoquées, session locale et destinations privées effacées, retour à l'accueil invité.
41. Body Edge avec email, mot de passe, ID token, nonce, fournisseur ou tout champ supplémentaire : requête refusée avant mutation.
42. `session_id` absent/invalide, AMR absente/malformée/non forte/ancienne/trop future, session Auth absente ou `getUser()` différent : requête refusée avant mutation.
