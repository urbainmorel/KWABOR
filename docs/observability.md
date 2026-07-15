# Observabilité mobile Kwabor

Cette tranche intègre Firebase nativement sur Android et iOS sans faire entrer le SDK dans `shared`. Elle couvre Analytics, Crashlytics, Performance Monitoring et Remote Config. FCM reste dans la tranche Notifications.

## Garanties de confidentialité

Toute collecte est désactivée au premier lancement :

- Analytics, Crashlytics et Performance sont désactivés dans le manifest Android et dans `Info.plist` avant l'initialisation Firebase ;
- la personnalisation publicitaire et la collecte de l'identifiant publicitaire Android sont désactivées ;
- iOS utilise `FirebaseAnalyticsCore`, sans capacité IDFA, et désactive aussi l'IDFV Analytics ;
- Remote Config n'effectue aucun fetch tant que son consentement explicite n'est pas accordé ;
- un retrait remet immédiatement la configuration distante aux valeurs sûres, suspend la collecte et supprime les rapports de crash non envoyés ;
- aucun user ID Firebase, email, nom, téléphone, texte de recherche ou contenu libre n'est accepté par le contrat Analytics.

Les trois consentements persistés sont indépendants : mesure d'usage, diagnostics et configuration distante. L'écran de consentement et le réglage utilisateur seront raccordés dans `AUTH-003` et `PROF-002`; tant qu'ils ne le sont pas, les valeurs restent toutes à `false`.

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

Les seules clés autorisées par cette fondation concernent le remplacement de la vidéo d'intro :

| Clé | Défaut embarqué | Validation |
|---|---:|---|
| `intro_video_enabled` | `false` | doit être explicitement vrai |
| `intro_video_url` | vide | HTTPS, hôte présent, aucun userinfo, 2048 caractères maximum |
| `intro_video_sha256` | vide | exactement 64 caractères hexadécimaux |
| `intro_video_revision` | `0` | entier strictement positif |

Une seule valeur absente ou invalide rejette l'ensemble distant et conserve l'asset embarqué. Après consentement, un listener Remote Config temps réel détecte les nouvelles publications et active seulement les changements qui touchent ces quatre clés. Le fetch production de douze heures reste le mécanisme de rattrapage ; aucun polling agressif n'est ajouté. Le listener est retiré à la révocation du consentement. Remote Config ne porte aucune autorisation, règle RLS, limite serveur, prix ou décision de paiement.

Le projet Firebase doit avoir l'API **Firebase Remote Config Realtime** activée. Le super-admin publie une révision strictement croissante depuis la console Firebase avec l'URL CDN et le SHA-256 du média. Les clients préchargent la révision validée puis la présentent une seule fois au lancement suivant ; une publication ne peut jamais interrompre une session en cours.

Le téléchargement, la validation codec/durée/taille et la révocation du média sont détaillés dans le [runbook onboarding](onboarding.md) et l'[ADR-0016](adr/0016-consent-gated-onboarding-media.md).

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

Le Privacy Manifest hôte déclare la ville agrégée comme localisation approximative et les événements comme interactions produit, non liés et sans tracking. Les manifests embarqués des SDK Firebase couvrent leurs collectes propres; les formulaires App Store et Play Data safety doivent néanmoins reprendre le comportement effectif Analytics, Crashlytics, Performance et Remote Config après consentement.

Avant la release candidate, le propriétaire doit valider la politique de confidentialité, les libellés de consentement, la durée de conservation, la région Analytics, les réglages de partage Google et les réponses exactes des deux stores. La référence Firebase à réauditer à chaque montée de version est [Prepare for Apple's App Store data disclosure requirements](https://firebase.google.com/docs/ios/app-store-data-collection).
