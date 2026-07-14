# 0013 — Services mobiles natifs avec Firebase

- **Statut** : accepté
- **Date** : 2026-07-14
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
- des valeurs sûres sont embarquées, le dernier Remote Config valide est mis en cache et une configuration invalide est rejetée ;
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
