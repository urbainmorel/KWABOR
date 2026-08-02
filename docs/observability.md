# Observabilité mobile Kwabor

Cette tranche intègre Firebase nativement sur Android et iOS sans faire entrer le SDK dans `shared`. Elle couvre Analytics, Crashlytics, Performance Monitoring et Remote Config. FCM reste dans la tranche Notifications.

## Garanties de confidentialité

Toute collecte est désactivée au premier lancement :

- Analytics, Crashlytics et Performance sont désactivés dans le manifest Android et dans `Info.plist` avant l'initialisation Firebase ;
- la personnalisation publicitaire et la collecte de l'identifiant publicitaire Android sont désactivées ;
- iOS utilise `FirebaseAnalyticsCore`, sans capacité IDFA, et désactive aussi l'IDFV Analytics ;
- Remote Config n'effectue aucun fetch tant que son consentement explicite n'est pas accordé ;
- un retrait arrête immédiatement les nouveaux fetch/listeners Remote Config, invalide leurs callbacks applicatifs, suspend la collecte et supprime les rapports de crash non envoyés ;
- aucun user ID Firebase, email, nom, téléphone, texte de recherche ou contenu libre n'est accepté par le contrat Analytics.

Les trois consentements persistés sont indépendants : mesure d'usage, diagnostics et configuration distante. L'écran d'inscription les raccorde dans `AUTH-003` au moment où l'utilisateur confirme ses choix, juste avant la finalisation atomique du compte ; le réglage utilisateur reste à livrer dans `PROF-002`. Tant qu'un choix explicite n'a pas été validé, les valeurs restent toutes à `false`.

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

## Configuration des builds

Les dépendances sont verrouillées à Firebase Android BoM `34.15.0`, plugins Google Services `4.5.0`, Crashlytics `3.0.7`, Performance `2.0.2` et Firebase Apple SDK `12.16.0` via Swift Package Manager.

En local :

- Android lit uniquement `androidApp/google-services.json`; sans ce fichier, le SDK compile mais `FirebaseApp` reste non configurée ;
- iOS lit uniquement un `GoogleService-Info.plist` inclus dans l'app ; le build script peut le copier depuis `KWABOR_FIREBASE_IOS_CONFIG_PATH`; sans fichier, l'adaptateur reste inactif.

Les workflows de release décodent les secrets `KWABOR_FIREBASE_ANDROID_CONFIG_BASE64` et `KWABOR_FIREBASE_IOS_CONFIG_BASE64`, comparent leur project ID à la variable d'environnement `KWABOR_FIREBASE_PROJECT_ID`, valident respectivement `com.kwabor.android` et `com.kwabor.ios`, injectent les fichiers uniquement pendant le job puis les suppriment. Un release staging ou production échoue si sa configuration Firebase est absente ou cible un autre projet ou une autre app.

## Symboles et diagnostics

Android conserve le mapping R8 et le plugin Crashlytics ajoute son identifiant de build. iOS exécute le script officiel `Crashlytics/run` uniquement pour un build device qui contient une configuration Firebase; les builds simulateur génériques ne publient rien.

Les erreurs non fatales acceptent seulement un `DiagnosticCode` fermé. Aucun message d'exception amont, payload, token, URL fournisseur ou donnée utilisateur n'est joint aux rapports.

## Déclarations stores à valider

Le Privacy Manifest hôte déclare la ville de profil comme localisation approximative liée au compte, pour la fonctionnalité applicative et, après consentement, Analytics. Les événements d'interaction produit restent non liés et sans tracking. Les coordonnées ponctuelles utilisées pour proposer une ville restent sur l'appareil. Les manifests embarqués des SDK Firebase couvrent leurs collectes propres; les formulaires App Store et Play Data safety doivent néanmoins reprendre le comportement effectif Analytics, Crashlytics, Performance et Remote Config après consentement.

Avant la release candidate, le propriétaire doit valider la politique de confidentialité, les libellés de consentement, la durée de conservation, la région Analytics, les réglages de partage Google et les réponses exactes des deux stores. La référence Firebase à réauditer à chaque montée de version est [Prepare for Apple's App Store data disclosure requirements](https://firebase.google.com/docs/ios/app-store-data-collection).
