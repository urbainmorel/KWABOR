# Livraison Kwabor V1 production

Ce document transforme le périmètre V1 du `PRD.md` en séquence de livraison traçable, sans confondre l'existant, la cible et les prérequis externes.

## En un coup d'œil

| Élément | Décision |
|---|---|
| Périmètre V1 | Totalité de `PRD.md` §5.1 sur Android et iOS |
| Clients | Android Compose Multiplatform et iOS SwiftUI uniquement |
| Backend | Supabase, RLS, Storage et Edge Functions |
| Mode de livraison | Une branche `codex/<ticket>` et une PR cohérente à la fois |
| Gates obligatoires | Tests ciblés, `check`, pgTAP si données, `quality`, `iOS simulator build` |
| État initial | Fondations, catalogue, Explore Android et auth email OTP minimale déjà livrés |
| Source d'exécution | `BACKLOG.md` pour les tickets ; ce document pour les dépendances et gates |

> [!IMPORTANT]
> Une case du backlog signifie qu'un livrable vérifiable existe. Elle ne signifie jamais qu'un compte fournisseur, une validation légale ou une signature store ont été simulés.

## Périmètre verrouillé

La V1 couvre exactement `PRD.md` §5.1. Les éléments suivants restent hors V1 :

- anglais, vidéo et commentaires Social, voix IA et statistiques avancées, prévus en V1.1 ;
- TikTok et langues PT/DE/ES/IT, prévus en V1.2+ ;
- réservation de table/chambre et billetterie intégrée, prévues au-delà de la V1 ;
- tout client autre qu'Android et iOS.

La V1 doit cependant conserver une architecture i18n-ready, les médias et modèles compatibles avec les extensions déjà prévues, et des migrations additives.

## Baseline vérifiée au démarrage

Au commit `27590e8`, les éléments suivants existent et passent la CI :

- modules `shared`, `androidApp` et hôte `iosApp` SwiftUI ;
- domaine/data Supabase pour catalogue, organisations, auth et Like/Favori ;
- RLS et pgTAP initiaux ;
- écran Explore Android lecture seule avec auth OTP minimale ;
- jobs GitHub Actions `quality` et `iOS simulator build`.

Les éléments suivants ne sont pas encore des capacités V1 complètes :

- iOS reste un hôte minimal sans parcours produit ;
- les dépendances runtime sont encore assemblées manuellement ;
- l'état applicatif Compose n'est pas encore porté par des ViewModels UDF par feature ;
- le cache/offline n'est pas persistant ;
- les intégrations Firebase, FedaPay, IA, médias et release ne sont pas présentes ;
- les comptes et secrets staging/production ne sont pas encore configurés.

## Règles d'exécution par ticket

Chaque ticket suit le même cycle :

1. repartir de `main` synchronisée et créer `codex/<ticket>` ;
2. relire le périmètre PRD/DESIGN et les ADR applicables ;
3. implémenter un incrément vertical sans stub visible ;
4. exécuter les tests ciblés, puis les gates proportionnés au risque ;
5. mettre à jour `PROJECT_STATE.md` et `BACKLOG.md` ;
6. pousser une PR, attendre `quality` et `iOS simulator build`, puis merger ;
7. vérifier l'état réel de la PR et de `main` avant le ticket suivant.

Une migration Supabase ajoute en plus `supabase db reset`, `supabase test db`, les tests RLS négatifs concernés et une vérification des grants Data API. Une tranche release ajoute les builds signés et les smoke tests correspondants.

## Traçabilité PRD vers backlog

| Périmètre PRD | Tickets principaux | Preuve finale attendue |
|---|---|---|
| §5.1, §6.0 — shell et navigation | `ARCH-001` à `NAV-001` | Navigation Android/iOS native, deep links typés, UDF |
| §6.9 — auth et onboarding | `AUTH-002` à `AUTH-005` | Email/Google/Apple, onboarding, session, suppression |
| §6.1 — Explore | `CATALOG-002`, `EXPLORE-002`, `EXPLORE-IOS-001` | Pagination, filtres, cache et parité fonctionnelle |
| §6.2, §6.7 — détail et avis | `DETAIL-001`, `REVIEWS-001`, `ACTIONS-001` | DetailSheet, avis, partage, itinéraire, signalement |
| §6.3 — recherche | `SEARCH-001` | Récents, autocomplétion, résultats et fallback offline |
| §6.4, §6.5 — IA et surprise | `AI-001` à `AI-004` | Recherche hybride ancrée au catalogue et quotas |
| §6.8 — notifications | `NOTIF-001` à `NOTIF-003`, `NOTIF-IOS-001` | Préférences, remise, deep links et écrans natifs |
| §6.10 — profil et paramètres | `PROFILE-001`, `SETTINGS-001` | Profil public/personnel, sécurité et préférences |
| §6.11 — contribution | `LISTING-001`, `MISSING-001` | Wizard réservé, brouillons, preview, lieu manquant |
| §6.12 — Promoteur | `B2B-001` à `B2B-003` | Organisations, vérification, dashboard et droits RLS |
| §6.13 — promotion/paiement | `PAYMENT-001` à `PAYMENT-003` | Ledger, webhook signé, campagne activée serveur |
| §6.14 — devises | `FX-001` | Taux valides, cache sept jours et repli XOF |
| §6.15 — Social photo/diaporama | `SOCIAL-001` à `SOCIAL-003`, `SOCIAL-IOS-001` | Feed, follows/likes, composeur et watermark |
| §8 — non-fonctionnel | `OFFLINE-001` à `MEDIA-001`, `QUAL-001` à `RELEASE-001` | Offline, sécurité, performance, accessibilité, stores |

## Ordre de livraison et dépendances

### 1. Gouvernance et architecture

`V1-GOV-001` verrouille ce plan, le backlog, les ADR et la protection de `main`. `ARCH-001` à `NAV-001` viennent ensuite, car toutes les features Android/iOS dépendent de composition roots Koin, de frontières UI correctes, d'états UDF et d'une navigation typée.

### 2. Environnements et observabilité

`ENV-001`, `ANDROID-REL-001`, `IOS-REL-001` et `OBS-001` établissent staging/production, l'injection sans secret versionné, les variantes, les identités d'app et la télémétrie. Les comptes externes sont créés au moment de cette vague, pas simulés dans le dépôt.

### 3. Auth et socle local

L'onboarding complet précède les préférences, notifications, profils et droits Promoteur. Room KMP, DataStore et l'outbox suivent immédiatement afin que les verticales suivantes reposent sur un comportement offline réel.

### 4. Découverte et identité utilisateur

Le résumé catalogue paginé supprime le N+1 avant d'étendre Explore. La recherche, le détail, les avis, le profil et les paramètres s'appuient ensuite sur les mêmes contrats et données locales.

### 5. Social, contribution et modération

Le schéma/RLS Social précède les feeds Android/iOS. Le composeur et le ListingWizard précèdent le pipeline de modération, qui reste fail-closed : une indisponibilité du service automatique ne publie jamais un contenu ambigu.

### 6. Notifications et B2B

Les tokens et préférences précèdent la remise FCM/APNs. Les organisations existantes sont ensuite reliées aux fiches, claims, campagnes et budgets avant d'ouvrir le dashboard Promoteur.

### 7. Paiement et IA

Le ledger et l'idempotence précèdent FedaPay. La campagne n'est activée que par le serveur après webhook authentifié et rapprochement. Le socle pgvector précède l'orchestrateur IA ; le client ne reçoit que des fiches publiées et revalidées côté serveur.

### 8. Qualification et publication

La release candidate n'entre en bêta qu'après les tests E2E critiques, la sécurité négative, l'accessibilité et les budgets de performance. Les artefacts stores, textes légaux et formulaires privacy sont des livrables versionnés ou archivés comme preuves de release.

## Gates de production

Une release candidate V1 est admissible seulement si :

- `quality`, pgTAP et `iOS simulator build` sont verts sur le commit candidat ;
- aucun écran visible n'est un placeholder et tous les états prévus sont traités ;
- les tests RLS/IDOR, suppression de compte, replay webhook, rate limiting et uploads malveillants passent ;
- P75 Explore est inférieur à 1,5 s sur le profil réseau dégradé retenu ;
- TalkBack/VoiceOver, contrastes AA et cibles tactiles sont vérifiés ;
- la bêta compte au moins 10 testeurs Android et 5 iOS pendant sept jours ;
- aucun P0/P1 n'est ouvert et les sessions sans crash atteignent 99,5 % ;
- auth, push, offline et une transaction live minimale MTN puis Moov sont prouvés ;
- l'AAB et l'archive iOS sont signés avec les identités du propriétaire ;
- CGU, politique de confidentialité, licences et déclarations stores sont validées.
- la documentation d'installation, d'architecture, de données, de tests, de déploiement et les runbooks critiques reflètent le commit candidat.

## Interventions réservées au propriétaire

Ces actions ne peuvent pas être remplacées par du code ou des valeurs fictives :

- KYC et création/validation des comptes Supabase, Firebase et FedaPay ;
- Apple Developer, Google Play Console, certificats et provisioning profiles ;
- secrets fournisseurs et autorisations de production ;
- validation juridique des CGU, de la politique de confidentialité et de la licence UGC ;
- recrutement des bêta-testeurs et validation des transactions live ;
- décision finale de passage 5 % → 25 % → 50 % → 100 %.

L'implémentation doit préparer les contrats, contrôles, runbooks et emplacements de secrets afin que chaque intervention soit courte, vérifiable et réversible.

## Décisions techniques liées

- [ADR-0011 — Persistance locale Room KMP](adr/0011-room-kmp-local-persistence.md)
- [ADR-0012 — IA serveur multi-provider](adr/0012-server-ai-multi-provider.md)
- [ADR-0013 — Services mobiles Firebase](adr/0013-firebase-mobile-platform-services.md)
- [ADR-0014 — Paiements FedaPay côté serveur](adr/0014-fedapay-server-side-payments.md)
- [ADR-0015 — Navigation mobile native](adr/0015-native-mobile-navigation.md)
- [ADR-0016 — Média d'onboarding conditionné au consentement](adr/0016-consent-gated-onboarding-media.md)
- [Runbook onboarding mobile](onboarding.md)

Étape suivante : prendre le premier ticket non livré de `BACKLOG.md`, sans sauter ses dépendances.
