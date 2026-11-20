# Observabilité mobile Kwabor

Cette tranche intègre Firebase nativement sur Android et iOS sans faire entrer le SDK dans `shared`. Elle couvre Analytics, Crashlytics, Performance Monitoring et Remote Config. FCM reste dans la tranche Notifications.

## Garanties de confidentialité

Toute observabilité optionnelle pilotée par Kwabor est désactivée au premier lancement :

- Analytics, Crashlytics et Performance sont désactivés dans le manifest Android et dans `Info.plist` avant l'initialisation Firebase ;
- la personnalisation publicitaire et la collecte de l'identifiant publicitaire Android sont désactivées ;
- iOS utilise `FirebaseAnalyticsCore`, sans capacité IDFA, et désactive aussi l'IDFV Analytics ;
- Remote Config n'effectue aucun fetch tant que son consentement explicite n'est pas accordé ;
- un retrait ferme immédiatement les portes applicatives Analytics/diagnostics, arrête les nouveaux fetch/listeners Remote Config, invalide leurs callbacks et demande aux SDK de désactiver leurs collectes ;
- iOS conserve une transaction Keychain typée de suppression Firebase Installations après révocation Remote Config ou révocation complète ; elle mémorise l'état final du consentement avant l'appel réseau, suspend toutes les collectes pendant cette maintenance et réessaie au lancement, lors d'une nouvelle liaison de session et au retour au premier plan ;
- iOS conserve aussi un marqueur Keychain de purge Crashlytics : un nouvel accord diagnostics le
  précède, tandis qu'une révocation persiste d'abord l'état final désactivé ; une interruption ne peut
  donc ni activer un accord encore non purgé, ni restaurer l'ancien accord après une révocation ;
- Android retire `FirebaseInitProvider` du manifest fusionné. Créer le contrôleur ne configure aucun
  SDK ; la configuration n'arrive qu'après liaison du compte consentant ou pour reprendre une
  maintenance durable, et force d'abord toutes les collectes à `false` ;
- Android retire aussi des manifestes fusionnés `AD_ID`, les deux permissions AdServices, Install
  Referrer et la bibliothèque `android.ext.adservices`, inutiles au contrat Kwabor ;
- aucun user ID Firebase, email, nom, téléphone, texte de recherche ou contenu libre n'est accepté par le contrat Analytics.

Les trois préférences de consentement persistées sont indépendantes : mesure d'usage, diagnostics et configuration
distante. L'inscription ne demande plus de nouveau consentement et préserve les choix déjà enregistrés ;
pour un nouveau compte, les trois valeurs restent à `false`. Paramètres → Confidentialité affiche
les trois choix du compte sur Android et iOS et permet de les modifier ou de les retirer séparément. Android coupe le
runtime avant ses écritures synchrones ; iOS conserve un enregistrement atomique dans le Keychain,
avec une empreinte du propriétaire plutôt que son identifiant brut. Tant qu'un choix explicite n'a
pas été validé, les valeurs restent toutes à `false`. L'absence temporaire de session suspend les
capacités sans effacer le choix lié au compte ; une déconnexion, une annulation d'inscription après
création de session ou une suppression de compte le révoque explicitement pour empêcher tout héritage
inter-compte.

Sur Android, chaque écriture de préférence coupe d'abord le runtime, puis persiste synchroniquement
un état fail-closed et ses maintenances avant le choix final. Les commits sont réessayés trois fois,
sans attente. Si le choix final ne peut pas être écrit, le stockage restaure explicitement l'ancien
historique sous l'état fail-closed afin qu'un commit ultérieur ne puisse pas publier accidentellement
le choix refusé. L'ancien consentement ne redevient pas effectif dans le processus ; le changement
non confirmé reste en mémoire pour que l'action « Réessayer » le rejoue uniquement pour le même
compte. Si la session change avant ce retry, la mise à jour abandonnée devient une révocation sûre et
prioritaire au lieu d'être transférée ; le retry doit alors persister cet état désactivé avant toute
nouvelle liaison. Un retry encore en échec conserve et réaffiche l'erreur. Le retour au premier plan
reprend ces écritures et les maintenances pendantes.

Si les trois tentatives de la première écriture fail-closed échouent toutes, Android ne peut fournir
aucune preuve disque avec `SharedPreferences`. L'opération retourne donc `false`, le runtime reste
fermé et l'interface indique que le changement n'est pas terminé. Après arrêt du processus, seul le
dernier état réellement durable peut être relu ; la documentation et l'interface ne présentent jamais
ce cas comme une révocation enregistrée. Les identifiants historiques masqués par une phase
fail-closed réussie restent lisibles uniquement par le stockage pour décider les purges nécessaires ;
ils ne rouvrent aucune capacité. Cette limite suit le contrat Android : `commit()` confirme une
écriture persistante par son booléen, tandis que `apply()` ne remonte aucun échec et peut perdre un
changement avant arrêt du processus. Voir la
[référence Android SharedPreferences](https://developer.android.com/reference/android/content/SharedPreferences).

Crashlytics Android reste toujours en mode manuel : aucun appel runtime ne l'active automatiquement.
Un consentement diagnostics restauré appelle d'abord `checkForUnsentReports()` et n'envoie qu'en cas
de rapport confirmé. Une purge durable porte un `requestId`, fournit `deleteUnsentReports()` après le
check et conserve son marqueur jusqu'à ce qu'un processus suivant confirme l'absence de rapport si une
suppression a été demandée. Une nouvelle purge après consommation du check attend donc le prochain
processus. Performance utilise uniquement les traces personnalisées sous consentement ; son
instrumentation automatique est aussi désactivée au build.

Une révocation complète, une révocation Remote Config ou un changement de compte Android écrit un
`requestId` Firebase Installations avant `delete()`. Toutes les catégories restent désactivées pendant
l'opération, un échec réseau conserve la demande pour retry et un callback ancien ne peut pas retirer
une demande plus récente. `Analytics.resetAnalyticsData()` reste une maintenance séparée.

Sur iOS, dans la lignée propre et actuellement supportée, la collecte automatique Crashlytics est
désactivée avant la configuration et forcée à `false` au runtime. Le SDK peut conserver un
rapport local après un crash, mais Kwabor appelle `sendUnsentReports()` uniquement au lancement
suivant, après restauration d'un consentement diagnostics appartenant au même compte et en l'absence
de purge attendue. Pour chaque purge, l'app appelle une seule fois `checkForUnsentReports()`, fournit
toujours l'action `deleteUnsentReports()` — même si aucun rapport n'est retourné — et ne retire le
marqueur durable que lorsqu'aucun rapport antérieur n'est présent. Si un rapport existe, ou si une
nouvelle purge est demandée après que le check unique du processus a déjà été consommé, le marqueur
reste présent jusqu'au prochain lancement. Cette attente maintient les diagnostics désactivés ; les
choix Analytics et Remote Config indépendants peuvent rester effectifs tant qu'aucune maintenance
Firebase Installations ou neutralisation globale ne les bloque.

Les API ne garantissent ni l'annulation d'un envoi déjà commencé, ni l'effacement local immédiat d'une
ancienne installation dont l'override automatique était déjà actif. Une suspension momentanée de
session coupe les portes runtime sans effacer les rapports déjà couverts par le choix persistant du
même compte. Toute population ayant reçu une ancienne build Firebase sort de cette garantie et exige
le plan de migration bloquant décrit dans les runbooks de release Android et iOS.

La migration des anciens overrides Firebase est fail-closed, même si les anciennes clés
`UserDefaults` sont absentes ou corrompues. L'app conserve un état Keychain durable : phase à
neutraliser, redémarrage attendu, puis phase neutralisée. Elle ne configure Firebase que si le compte
a explicitement autorisé chaque catégorie potentiellement active ; les collectes restent malgré tout
suspendues pendant la neutralisation. Analytics est désactivé et réinitialisé, Crashlytics est forcé
en mode manuel pour le processus suivant, puis les choix du compte ne deviennent effectifs qu'après
ce redémarrage. Un état de migration absent ou corrompu persiste d'abord la purge Crashlytics et la
suppression FID. Si Firebase est déjà configuré et vient d'être coupé, la phase « redémarrage attendu »
est écrite directement en une seule opération Keychain ; un crash ne peut donc pas laisser une phase
intermédiaire impossible à reprendre. Un changement de propriétaire place aussi l'identifiant
Firebase en suppression attendue afin qu'aucun état technique ne soit repris silencieusement par le
compte suivant.

Avant chaque relecture du Keychain lors d'une liaison, d'un changement de choix, d'une révocation ou
d'un retry, le runtime désactive les SDK déjà configurés et invalide Remote Config. Une erreur de
lecture ou d'écriture ne peut donc pas laisser l'ancien état effectif actif en mémoire.

Limite P2 de la bêta fermée : sur un processus iOS froid, si un override Firebase historique inconnu
exige une configuration préalable pour être neutralisé, l'app ne configure pas le SDK avec un
consentement désactivé. L'observabilité reste alors en quarantaine OFF, la transaction et l'erreur
restent retryables au premier plan, et tout regrant est refusé tant que le cleanup n'est pas prouvé.
Cette quarantaine ne bloque ni la restauration Auth, ni le catalogue. Sa reprise doit être qualifiée
sur macOS/appareil avant de fermer G3. Dans l'implémentation actuelle, les retries ne franchissent pas
la phase `requiresSafeConfiguration` sur ce chemin froid : un mécanisme sûr reste à concevoir et
prouver. Aucune configuration de maintenance non documentée n'est utilisée pour contourner les
overrides persistants.

Une installation réellement neuve repart de la phase neutralisée et efface les consentements et
marqueurs Keychain survivants avant toute restauration Auth. L'ancien FID serveur d'une app déjà
désinstallée n'est plus adressable depuis le nouveau sandbox ; l'app ne crée donc pas un nouvel
identifiant uniquement pour tenter de supprimer l'ancien. C'est l'unique chemin qui retire localement
un marqueur FID sans appeler l'API : il s'exécute seulement avant toute configuration Firebase et
refuse de courir si une suppression est déjà en vol. Aucune permission ou collecte de remplacement
n'est déclenchée par l'inscription avant l'accueil.

## Sessions observées de bêta fermée

Le dénominateur crash-free utilise un tracker possédé par l'application, distinct du catalogue fermé
d'événements produit. Il émet uniquement `observed_session_started`, sans paramètre, identifiant de
session, compte ou autre donnée personnelle. L'émission exige simultanément les consentements
Analytics et Diagnostics effectivement actifs ; une suspension de maintenance ferme la porte sans
créer d'événement.

Le premier foreground éligible ouvre une session observée. Un foreground suivant n'en ouvre une
nouvelle qu'après un background enregistré depuis au moins 30 minutes : 29 min 59 s reprend la même
session et 30 min 00 s en crée une. Le checkpoint local non-PII contient seulement l'état
`foreground`, ou les temps wall et monotone avec l'identité/ancre de boot du dernier passage en
arrière-plan. À boot certain, le monotone est autoritaire, y compris après un saut d'horloge wall ;
un reboot, une régression monotone ou une ancre inter-processus incertaine reprend la session sans en
émettre une nouvelle. Une relance après un processus arrêté en foreground ne prétend donc jamais
avoir observé une inactivité qui n'a pas été enregistrée.

Le retrait de l'un des deux consentements, la révocation globale et un changement de compte effacent
ce checkpoint avant toute reprise. Android le conserve dans un fichier `SharedPreferences` privé et
synchrone, couvert par l'exclusion globale de sauvegarde ; iOS dans un fichier Application Support
borné et remplacé atomiquement, sans identifiant. Une écriture ou suppression non acquittée échoue
fermée. Le reset de première installation et chaque révocation effacent aussi le fichier et son
temporaire. La source wall/monotone, le stockage et les signaux de cycle de vie sont injectés dans le
tracker partagé ; les adaptateurs Firebase natifs ne font que relayer l'événement déjà admis.

## Contrat Analytics

`ObservabilityModels.kt` porte la liste fermée des événements du PRD §11 et leurs dimensions communes. `ville` et `entite_id` reçoivent uniquement des identifiants opaques composés de lettres ASCII, chiffres, tiret ou underscore. Le contrat rejette les espaces, `@`, URL et texte libre.

Les dimensions émises pour chaque événement sont :

| Paramètre | Valeur |
|---|---|
| `ville` | ID opaque de ville ou `not_applicable` |
| `type_entite` | enum fermée |
| `entite_id` | ID opaque ou `not_applicable` |
| `source_session` | `organic` ou `sponsored` |
| `langue` | tag de locale livré |
| `devise_affichage` | XOF, NGN, USD ou EUR |

`auth_method` et `post_type` ne sont acceptés que par leurs événements respectifs et via des enums fermées.

Le parcours simplifié peut émettre `auth_method`, `registration_otp_validated`, `registration_profile_succeeded`, `registration_profile_failed` et `protected_action_replayed`. Les adaptateurs natifs les filtrent avant envoi : si le consentement Analytics n'existe pas déjà, aucun événement ne quitte l'appareil. Aucun OTP, mot de passe, email, nom ou texte libre n'est attaché.

## Remote Config

La vidéo d'intro n'utilise plus Remote Config. Les quatre anciennes clés média, tout transport
d'URL ou d'octets éditoriaux et toute logique de téléchargement/cache sont interdits par
l'[ADR-0021](adr/0021-store-released-onboarding-media.md). Le média est embarqué, révisionné et
publié exclusivement avec les versions Store.

Remote Config reste disponible après consentement pour des valeurs UX et feature flags sûrs selon
l'ADR-0013. Chaque futur paramètre doit être approuvé avec :

- une clé explicitement allowlistée et un type fermé ;
- une valeur sûre embarquée qui reste fonctionnelle sans Firebase ni réseau ;
- des bornes et une validation fail-closed sur Android et iOS ;
- des tests de consentement, révocation, valeur absente/invalide et parité plateforme ;
- une documentation opérateur et un propriétaire nommé.

Remote Config ne transporte aucun média ou URL de contenu et ne porte aucune autorisation, règle
RLS, limite serveur, prix ou décision de paiement. Aucun dictionnaire arbitraire de flags n'est
exposé au domaine partagé. Tant qu'aucun flag concret n'est approuvé, les adaptateurs conservent la
capacité Firebase générique et leurs valeurs sûres sans inventer de contrat produit.

Firebase peut terminer en interne une opération `fetch`/`activate` déjà remise au SDK juste avant
une révocation ; cette cache SDK n'est jamais une autorité ni une valeur directement exposée. Tout
futur lecteur typé doit vérifier le consentement au moment de chaque lecture et retourner sa valeur
embarquée sûre lorsque le consentement est absent. Les générations de session empêchent en plus
les callbacks obsolètes de publier un état ou un diagnostic après révocation/réactivation.

La [documentation Remote Config](https://firebase.google.com/docs/reference/swift/firebaseremoteconfig/api/reference/Classes/RemoteConfig)
précise qu'un premier `fetch` peut lancer une synchronisation périodique via Firebase Installations.
Retirer le listener temps réel ne suffit donc pas. Android persiste le choix final et un identifiant de
requête ; iOS persiste une transaction typée (`preserve`, `replace` ou `revoke`). Les deux clients
réconcilient l'état durable, interdisent tout nouveau fetch, puis appellent `Installations.delete()`.
Un appel plus récent ne peut pas être acquitté par le résultat asynchrone d'un ancien appel. Le marqueur
n'est retiré qu'après le retour réussi de l'API cliente, sauf nettoyage iOS de première installation
décrit plus haut.

Un opt-in Remote Config reçu pendant cette maintenance est conservé comme choix souhaité, mais reste
sans effet et ne déclenche aucun fetch avant la fin de la suppression. Firebase peut alors créer un
nouveau FID, considéré comme non lié à l'ancien. Le succès de `Installations.delete()` confirme
l'achèvement de l'appel client, pas une purge instantanée de tous les systèmes Firebase : la
[documentation de gestion des installations](https://firebase.google.com/docs/projects/manage-installations)
annonce jusqu'à 180 jours pour retirer des systèmes actifs et de sauvegarde les données liées au FID.
Cette suppression ne supprime pas les données Google Analytics, qui utilisent leur propre identifiant ;
Kwabor appelle séparément `Analytics.resetAnalyticsData()` lorsque le consentement Analytics est
retiré. Voir aussi la
[référence API Firebase Installations](https://firebase.google.com/docs/reference/swift/firebaseinstallations/api/reference/Classes/Installations).

## Configuration des builds

Les dépendances sont verrouillées à Firebase Android BoM `34.15.0`, plugins Google Services `4.5.0`, Crashlytics `3.0.7`, Performance `2.0.2` et Firebase Apple SDK `12.16.0` via Swift Package Manager.

En local :

- Android lit uniquement `androidApp/google-services.json`. Même avec ce fichier, le provider
  automatique est retiré et l'adaptateur reste inerte jusqu'à un consentement lié au compte ou une
  maintenance durable ; sans fichier, le SDK compile mais `FirebaseApp` reste non configurée ;
- iOS lit uniquement un `GoogleService-Info.plist` inclus dans l'app ; le build script peut le copier depuis `KWABOR_FIREBASE_IOS_CONFIG_PATH`. Même avec ce fichier, Firebase n'est configuré qu'après restauration d'un consentement appartenant au compte authentifié, après un choix explicite, ou en mode maintenance sans collecte pour achever une suppression FID déjà persistée. Il reste bloqué pendant une migration d'override qui ne satisfait pas sa gate sûre ; sans fichier, l'adaptateur reste inactif.

Les workflows de release décodent les secrets `KWABOR_FIREBASE_ANDROID_CONFIG_BASE64` et `KWABOR_FIREBASE_IOS_CONFIG_BASE64`, comparent leur project ID à la variable d'environnement `KWABOR_FIREBASE_PROJECT_ID`, valident respectivement `com.kwabor.android` et `com.kwabor.ios`, injectent les fichiers uniquement pendant le job puis les suppriment. Un release staging ou production échoue si sa configuration Firebase est absente ou cible un autre projet ou une autre app.

La tâche Gradle `:androidApp:verifyFirebaseMergedManifests`, rattachée à `check`, construit puis analyse
les manifestes debug, staging et release. Elle refuse le provider automatique, les quatre permissions
d'attribution, `android.ext.adservices`, une valeur de collecte manquante, dupliquée ou différente de
`false`. Le contrôle Python complète cette preuve sur les sources et variantes versionnées.

## Symboles et diagnostics

Android conserve le mapping R8 et le plugin Crashlytics ajoute son identifiant de build. iOS exécute le script officiel `Crashlytics/run` uniquement pour un build device qui contient une configuration Firebase ; les builds simulateur génériques ne publient rien. Sur les deux plateformes, l'envoi des rapports reste manuel et lié au consentement du compte, indépendamment de l'upload des symboles.

L'instrumentation automatique Performance reste désactivée dans `gradle.properties`, `Info.plist` et dans les runtimes, car
la [référence Firebase Performance](https://firebase.google.com/docs/reference/swift/firebaseperformance/api/reference/Classes/Performance)
ne garantit une modification complète à chaud que lorsqu'elle intervient avant la configuration. Les
mesures Performance livrées sont uniquement des traces personnalisées ouvertes sous consentement
diagnostics ; leur envoi s'arrête dès que ce consentement n'est plus effectif.

### First usable viewport Explore

La bêta fermée instrumente `explore_initial_load` sur Android et iOS avec l'horloge monotone de la
plateforme. Le départ correspond au point de cycle de vie éligible d'Explore au premier plan ; le
premier échantillon éligible du processus est `first_process_explore`, puis chaque retour après
masquage, navigation ou arrière-plan est `subsequent_explore`. Ces valeurs décrivent uniquement
l'ordre des apparitions Explore dans le processus courant ; elles ne qualifient jamais un démarrage
`cold` ou `warm`. Une recherche ou une fiche qui recouvre Explore annule l'échantillon.

La mesure ne se termine qu'après confirmation d'un rendu de taille positive, postérieur aux
skeletons, pour un contenu tactile et scrollable ou un état terminal vide, offline ou erreur. Les
callbacks de rendu portent une génération : un callback obsolète ou dupliqué ne peut rien publier.
Un retrait du consentement, un passage en arrière-plan ou une navigation annule localement la mesure
et aucune trace partielle n'est remise à Firebase.

La trace terminée contient uniquement la métrique entière `first_usable_viewport_us` et deux
attributs à vocabulaire fermé : `process_explore_kind` (`first_process_explore` ou
`subsequent_explore`) et `viewport_state` (`content`, `empty`, `offline` ou `error`). Aucun
identifiant, texte libre, ville, URL ou contenu de fiche n'est attaché. Cette instrumentation ne
constitue pas la preuve B7.10 : seul le harnais opérateur attribue `cold` après force-stop et lancement
direct ou `warm` après retour contrôlé dans le même processus. Les 10 mesures cold et 20 warm sur
chacun des appareils physiques de référence, sous le profil réseau gelé, restent à exécuter et à
archiver sans inventer de valeurs.

Les erreurs non fatales acceptent seulement un `DiagnosticCode` fermé. Aucun message d'exception amont, payload, token, URL fournisseur ou donnée utilisateur n'est joint aux rapports.

## Déclarations stores à valider

Le Privacy Manifest hôte déclare le nom, l'e-mail, l'identifiant utilisateur, la ville de profil et
les interactions produit comme liés au compte, sans tracking. L'inscription ne collecte aucune
coordonnée ; la localisation approximative demandée plus tard sur action explicite pour proposer une
ville reste sur l'appareil. Les likes/favoris servent la fonctionnalité et les événements d'usage
servent Analytics après consentement. Les manifests réellement agrégés des SDK Firebase doivent
encore être inventoriés dans l'archive Release et rapprochés du Privacy Report Xcode. Les formulaires
App Store et Play Data safety doivent reprendre le comportement effectif Analytics, Crashlytics,
Performance et Remote Config après consentement. Voir
[l'inventaire iOS](audits/2026-08-03-ios-privacy-inventory.md).

Avant la release candidate, le propriétaire doit valider la politique de confidentialité, les libellés de consentement, la durée de conservation, la région Analytics, les réglages de partage Google et les réponses exactes des deux stores. La référence Firebase à réauditer à chaque montée de version est [Prepare for Apple's App Store data disclosure requirements](https://firebase.google.com/docs/ios/app-store-data-collection).
