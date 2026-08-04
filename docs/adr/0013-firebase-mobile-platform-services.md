# 0013 — Services mobiles natifs avec Firebase

- **Statut** : accepté
- **Date** : 2026-07-14
- **Dernière précision** : 2026-08-03
- **Décideurs** : Produit Kwabor, Architecture, Mobile, Data
- **Remplace** : —

## Contexte et problème

La V1 nécessite push Android/iOS, analytics, Crashlytics, métriques de performance et Remote Config. Ces capacités sont liées aux runtimes mobiles et ne doivent ni contaminer le domaine partagé ni créer une troisième plateforme applicative.

Utiliser plusieurs fournisseurs spécialisés multiplierait les identités d'app, SDK, consentements et chaînes d'observabilité avant la V1.

## Options envisagées

- **Firebase natif Android/iOS** : FCM, Analytics, Crashlytics, Performance et Remote Config dans deux projets isolés par environnement.
- **Services séparés** : davantage de choix, mais intégration et exploitation plus complexes.
- **Implémentation maison Supabase uniquement** : ne remplace pas les transports FCM/APNs ni les diagnostics natifs de crash.

## Décision

Nous retenons Firebase pour les services mobiles de la V1, avec deux projets distincts `staging` et `production` et les identifiants `com.kwabor.android` / `com.kwabor.ios` tant que les stores ne signalent pas d'indisponibilité.

Les règles suivantes sont obligatoires :

- les SDK Firebase restent dans `androidApp` et `iosApp` ; aucune dépendance Firebase n'entre dans le domaine ;
- des adaptateurs plateforme minces exposent au shared les événements et capacités nécessaires ;
- FCM transporte les notifications Android et s'appuie sur APNs pour Apple ;
- l'envoi part d'un environnement serveur de confiance, jamais d'une clé Admin embarquée ;
- les tokens device sont enregistrés côté Supabase avec propriétaire, plateforme, environnement, statut et rotation ;
- Analytics respecte le consentement et n'enregistre pas de PII ;
- Crashlytics filtre toute donnée utilisateur sensible ;
- Remote Config pilote des valeurs UX et feature flags, jamais une autorisation, un prix, un paiement ou une règle RLS ;
- Remote Config ne transporte aucun média ou URL de contenu ; la vidéo d'intro est distribuée exclusivement avec les versions Store selon l'ADR-0021 ;
- sur Android, `FirebaseInitProvider` est retiré du manifest fusionné. La construction de l'adaptateur
  reste inerte et Firebase n'est configuré qu'après liaison du compte consentant ou pour reprendre une
  maintenance durable, avec Analytics, Crashlytics et Performance d'abord forcés à `false` ;
- sur Android, Crashlytics reste en collecte automatique désactivée. L'envoi et la suppression des
  rapports sont manuels après le check unique du processus ; une purge porte un identifiant de requête
  durable et reste attendue jusqu'à ce qu'un processus confirme l'absence de rapport ;
- sur Android, une révocation complète, une révocation Remote Config ou un changement de propriétaire
  persiste une suppression Firebase Installations identifiée avant l'appel réseau. Toutes les
  collectes restent suspendues jusqu'au succès et un callback ancien ne peut pas acquitter une demande
  plus récente ;
- sur Android, le stockage applique une écriture synchrone en deux phases : état fail-closed et
  maintenances d'abord, choix final ensuite. Chaque commit est réessayé de façon bornée et un échec de
  la phase finale restaure explicitement l'historique antérieur sous l'état fail-closed. Le runtime ne
  réactive jamais l'ancien consentement dans le processus et le choix non confirmé reste visible avec
  retry pour le même compte ; un changement de session convertit ce choix abandonné en révocation
  prioritaire. Si le stockage refuse toutes les tentatives synchrones, l'opération retourne un échec et ne
  prétend pas être durable : après arrêt du processus, seul le dernier état réellement écrit peut être
  relu ;
- sur iOS, Crashlytics reste en collecte automatique désactivée. Tout nouvel accord diagnostics est
  précédé d'un marqueur Keychain de purge ; toute révocation persiste d'abord le consentement final
  désactivé. Le check unique du processus fournit toujours l'action de suppression et le marqueur
  reste jusqu'au processus suivant si un rapport existe ou si une nouvelle purge survient après ce
  check. Seuls les diagnostics attendent cette purge ; les autres catégories gardent leurs propres
  portes de consentement ;
- sur iOS, toute révocation Remote Config ou révocation complète qui exige une suppression Firebase
  Installations est une transaction Keychain typée : l'intention finale du consentement est durable et
  réconciliée avant l'appel réseau, toutes les collectes restent suspendues jusqu'au succès client, et
  un callback ancien ne peut pas acquitter une demande plus récente ; seule une première installation
  reconnue peut effacer le marqueur survivant avant configuration, car l'ancien FID n'est plus
  adressable depuis le nouveau sandbox ;
- sur iOS, une migration d'override Firebase absente ou corrompue persiste purge diagnostics et
  suppression FID avant restauration. Si Firebase est déjà configuré après avoir été coupé, la phase
  de redémarrage attendu est écrite atomiquement ; tout échec Keychain maintient les SDK effectifs
  désactivés ;
- des valeurs sûres sont embarquées, le dernier Remote Config valide est mis en cache et une configuration invalide est rejetée ;
- les sources critiques d'observabilité sont verrouillées par empreintes d'audit normalisées et tout
  accès direct au SDK Firebase hors des adaptateurs approuvés est interdit ;
- les dépendances déclarées sont contrôlées après évaluation Gradle et les scripts Gradle sont
  verrouillés par inventaire et empreinte ; le groupe Firebase est interdit hors d'`androidApp` ;
- Android vérifie aussi les manifestes fusionnés debug, staging et release : le provider automatique,
  les permissions d'attribution publicitaire et la bibliothèque AdServices doivent être absents, et
  les six valeurs de collecte doivent rester exactement à `false` ;
- les fichiers de configuration et secrets spécifiques aux environnements sont injectés par CI et ne sont pas versionnés avec des valeurs réelles.

Firebase documente FCM comme solution cross-platform dont l'envoi doit venir d'un environnement de confiance, avec un transport spécifique à Android ou Apple : [Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging). La page tarifaire classe actuellement FCM, Analytics, Crashlytics, Performance et Remote Config parmi les produits sans coût direct, sans que cette tarification devienne une hypothèse immuable : [Firebase Pricing](https://firebase.google.com/pricing).

## Conséquences

**Positives**

- Chaîne cohérente pour push, qualité runtime et configuration distante.
- SDK natifs adaptés à Compose Android et SwiftUI iOS.
- Séparation claire entre télémétrie mobile et données métier Supabase.

**Négatives / compromis assumés**

- Configuration et validation nécessaires sur deux projets Firebase et deux plateformes.
- Consentement, privacy manifests et formulaires stores doivent couvrir les SDK réellement activés.
- La tarification et les quotas restent à surveiller malgré l'absence de coût produit direct actuelle.

**À revoir si**

- un produit Firebase devient incompatible avec les exigences privacy ou budgétaires ;
- les SDK imposent une dépendance non acceptable au shared ;
- le transport push retenu ne couvre plus une cible mobile V1.
