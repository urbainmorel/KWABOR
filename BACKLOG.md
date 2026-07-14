# BACKLOG.md — Kwabor

Feuille de route et gates : [docs/v1-production-delivery.md](docs/v1-production-delivery.md).

## En cours

- [x] FND-001 — Créer le scaffold KMP minimal compilable.
- [x] FND-002 — Formaliser les ADR fondateurs.
- [x] FND-003 — Installer le shell Compose partagé.
- [x] FND-007 — Créer la PR `foundation/app-foundations`.
- [x] FND-004 — Ajouter les contrats repositories du catalogue.
- [x] FND-005 — Préparer les migrations Supabase initiales avec RLS.
- [x] MOB-001 — Acter le cadrage mobile-only, iOS SwiftUI, CI macOS et rôles d'équipe vérifiée.
- [x] MOB-002 — Supprimer proprement l'ancienne cible non mobile du build.
- [x] IOS-001 — Créer l'hôte iOS SwiftUI et l'intégration du framework `shared`.
- [x] CI-001 — Ajouter un job GitHub Actions macOS qui compile iOS en simulateur sans signature.
- [x] CI-002 — Vérifier la CI macOS après push et corriger le build Xcode si nécessaire.
- [x] DATA-TEAM-001 — Créer les migrations Supabase équipes, membres, invitations, budgets et tests RLS.
- [x] DOMAIN-TEAM-001 — Ajouter les modèles domaine et contrats repository des organisations vérifiées.
- [x] DATA-TEAM-002 — Implémenter les DTO et repository data des organisations vérifiées.
- [x] DATA-TEAM-003 — Brancher `OrganizationDataSource` sur Supabase PostgREST/RPC.
- [x] DATA-CATALOG-001 — Brancher les repositories catalogue sur Supabase PostgREST.
- [x] AUTH-FOUNDATION-001 — Préparer la session auth partagée et le stockage sécurisé des tokens.
- [x] DATA-CATALOG-002 — Ajouter les contrats et la data Supabase pour Like/Favori catalogue.
- [x] FND-006 — Ajouter les previews UI et tests de design system.
- [x] CI-003 — Débloquer GitHub Actions et relancer les checks des PR ouvertes.
- [x] EXPLORE-001A — Injecter le `CatalogRepository` réel depuis Android/iOS sans secret commité.
- [x] EXPLORE-001B — Rendre les images distantes des cartes catalogue en KMP.
- [x] EXPLORE-001C — Relier Like/Favori au mur souple auth et préparer la queue offline.
- [x] EXPLORE-001 — Créer l'écran Explore lecture seule avec cartes catalogue et états transverses.
- [x] PR-EXPLORE-001 — Finaliser, pousser et merger la PR `feature/explore-read-only` après `quality` et `iOS simulator build` verts.
- [x] AUTH-001A — Brancher le mur souple Explore sur email OTP, profil minimal, acceptations légales et reprise Like/Favori après authentification.
- [x] PR-AUTH-001A — Finaliser, pousser et merger la PR `feature/auth-mvp` après `quality` et `iOS simulator build` verts.

## Livraison V1 active

- [x] V1-GOV-001 — Transformer `PRD.md` §5.1 en feuille de route traçable, accepter les ADR Room/IA/Firebase/FedaPay et protéger `main`.
- [x] PR-V1-GOV-001 — PR `#18` mergée après `quality`, pgTAP et `iOS simulator build` verts.

### Architecture et environnements

- [x] CI-004 — Migrer les actions GitHub vers des versions compatibles Node 24 sans modifier les gates.
- [x] ARCH-001 — Remplacer la construction manuelle par des modules Koin et composition roots Android/iOS.
- [ ] PR-ARCH-001 — Merger la tranche Koin après `quality`, tests du graphe et `iOS simulator build` verts.
- [ ] CI-005 — Rendre Detekt effectif sur les source sets KMP et traiter la convention Compose sans baseline ni `@Suppress`.
- [ ] ARCH-002 — Déplacer l'UI Compose et les tokens Android de `shared` vers `androidApp` sans régression visuelle.
- [ ] ARCH-003 — Introduire les ViewModels par feature, `StateFlow` immuable, intents exhaustifs et effets ponctuels.
- [ ] NAV-001 — Livrer navigation Android et SwiftUI natives avec routes et deep links typés.
- [ ] ENV-001 — Créer et relier Supabase/Firebase staging et production, GitHub Environments et contrats de secrets sans valeur sensible.
- [ ] ANDROID-REL-001 — Ajouter variantes debug/staging/release, versionnement, minification, icônes, splash et signature injectée.
- [ ] IOS-REL-001 — Ajouter configurations Xcode, entitlements, Privacy Manifest, assets et signature injectée.
- [ ] OBS-001 — Intégrer Firebase Android/iOS pour Analytics, Crashlytics, Performance et Remote Config avec consentement.

### Auth et onboarding

- [ ] AUTH-002 — Livrer intro vidéo, reduced-motion, cache Remote Config et navigation invité sur Android/iOS.
- [ ] AUTH-003 — Terminer email OTP, mot de passe, identité, ville/GPS, devise et consentements.
- [ ] AUTH-004 — Ajouter connexion mot de passe, oubli/réinitialisation, déconnexion et écrans SwiftUI équivalents.
- [ ] AUTH-005 — Intégrer Google Android/iOS, Apple iOS, activation Promoteur, ré-authentification et Edge Function `account-delete`.

### Offline, préférences et médias

- [ ] OFFLINE-001 — Installer Room KMP, schémas exportés et DataStore KMP pour préférences légères.
- [ ] SYNC-001 — Persister l'outbox, coalescer Like/Favori, appliquer idempotence, backoff et drain réseau/session.
- [ ] DRAFT-001 — Synchroniser les brouillons avec version optimiste et conservation des deux versions en conflit.
- [ ] MEDIA-001 — Créer buckets/RLS, uploads temporaires, validation, downsampling, dérivés et Edge Function `media-finalize`.

### Explore, recherche, détail et devises

- [ ] CATALOG-002 — Ajouter une vue/RPC de résumé catalogue paginé et supprimer le N+1 média.
- [ ] EXPLORE-002 — Finaliser Explore Android : pagination, refresh, filtres, ville/GPS, sponsors et cache.
- [ ] EXPLORE-IOS-001 — Livrer Explore SwiftUI avec les mêmes états et capacités fonctionnelles.
- [ ] SEARCH-001 — Livrer récents, autocomplétion, résultats, filtres et fallback texte offline.
- [ ] DETAIL-001 — Livrer le DetailSheet paramétrable avec médias officiels, champs typés, carte et billetterie externe.
- [ ] REVIEWS-001 — Ajouter avis paginés, création/édition, photos, likes et réponse Promoteur.
- [ ] ACTIONS-001 — Ajouter partage, itinéraire, contact, signalement, guide, claim et état événement terminé.
- [ ] FX-001 — Livrer `exchange-rates-sync` avec Open Exchange Rates, cache sept jours du dernier taux valide puis repli XOF.

### Profil, paramètres, Social et contribution

- [ ] PROFILE-001 — Livrer profils personnel/public, publications, contenus, favoris, statistiques et édition.
- [ ] SETTINGS-001 — Livrer sécurité, sessions, préférences, thème, langue/devise/date, légal et Danger Zone.
- [ ] SOCIAL-001 — Ajouter schéma/RLS feed, follows, post likes, médias, compteurs et pagination.
- [ ] SOCIAL-002 — Livrer le feed photo/diaporama Android avec mention ≤ 25 %, Like, suivi et partage.
- [ ] SOCIAL-IOS-001 — Livrer le feed SwiftUI avec parité fonctionnelle et accessibilité.
- [ ] SOCIAL-003 — Livrer le composeur, rattachement obligatoire, ordre, alt text, progression/retry et watermark.
- [ ] LISTING-001 — Livrer menu `+`, ListingWizard, polygone Bénin, champs typés, brouillons et preview réelle.
- [ ] MISSING-001 — Livrer « Signaler un lieu manquant » sans droit de création de fiche.

### Modération, traduction et notifications

- [ ] MOD-001 — Livrer `moderate-content` : validation, déduplication/GPS, texte/image, risque et quarantaine fail-closed.
- [ ] MOD-OPS-001 — Ajouter RPC opérateur sécurisés, journal d'audit et recours sans nouveau client applicatif.
- [ ] TRANSLATION-001 — Traduire à l'affichage avec cache serveur en conservant toujours le texte source.
- [ ] NOTIF-001 — Ajouter préférences, tokens device, campagnes, lecture/masquage et RLS.
- [ ] NOTIF-002 — Livrer `notifications-dispatch`, remise FCM/APNs, quotas sponsorisés, silence nocturne, retry et deep links.
- [ ] NOTIF-003 — Livrer le centre et les réglages de notifications Android.
- [ ] NOTIF-IOS-001 — Livrer le centre et les réglages de notifications SwiftUI.

### Organisations, promotion, paiement et IA

- [ ] B2B-001 — Relier fiches, claims, campagnes et budgets aux organisations avec droits RLS cumulatifs.
- [ ] B2B-002 — Ajouter vérification à score, recours/escalade et gestion des membres sans auto-attribution critique.
- [ ] B2B-003 — Livrer dashboard Promoteur Android/iOS : fiches, avis, équipe, stats, facturation, claims et guide.
- [ ] PAYMENT-001 — Ajouter devis serveur, ledger, idempotence, événements webhook et reçus.
- [ ] PAYMENT-002 — Livrer `payment-create-fedapay` et `payment-webhook-fedapay` avec signature, anti-replay et rapprochement.
- [ ] PAYMENT-003 — Livrer promotion/paiement Android/iOS et prouver sandbox puis transactions live MTN/Moov.
- [ ] AI-001 — Ajouter pgvector et indexer uniquement les fiches publiées avec `text-embedding-3-small`.
- [ ] AI-002 — Livrer `ai-discover`, recherche hybride, validation 3–5 fiches et réponses structurées.
- [ ] AI-003 — Ajouter routage OpenAI → Gemini → OpenRouter, quotas, budget et alertes serveur.
- [ ] AI-004 — Livrer assistant Android/iOS et « Surprenez-moi » pondéré sans génération libre.

### Qualification, bêta et publication

- [ ] QUAL-001 — Ajouter tests Compose/Roborazzi, XCTest/XCUITest, contrats Edge Functions et E2E critiques.
- [ ] SEC-001 — Vérifier RLS négative, IDOR, account delete, replay, rate limiting, secrets, médias et migrations.
- [ ] PERF-A11Y-001 — Prouver P75 Explore, AA, TalkBack/VoiceOver, mémoire et consommation data.
- [ ] DOC-001 — Livrer README, index, setup, architecture, data model, testing, environment, deployment et contribution.
- [ ] OPS-001 — Livrer runbooks auth, push, paiement, sauvegarde/PITR, incident et rollback.
- [ ] BETA-001 — Exécuter la bêta 10 Android/5 iOS sur sept jours avec zéro P0/P1 et ≥ 99,5 % sans crash.
- [ ] STORE-ANDROID-001 — Produire AAB signé, fiche Play, privacy/data safety et plan de rollout.
- [ ] STORE-IOS-001 — Produire archive/TestFlight, fiche App Store, privacy et validation de signature.
- [ ] RELEASE-001 — Valider rollback, flags de coupure, sauvegardes/PITR et rollout 5 % → 25 % → 50 % → 100 %.

## Hors V1

- Anglais, vidéo/commentaires Social, voix IA et statistiques avancées : V1.1.
- TikTok et langues PT/DE/ES/IT : V1.2+.
- Réservation de table/chambre et billetterie intégrée : V2+.
- Tout autre client applicatif : exclu du produit.
