# BACKLOG.md — Kwabor

Feuille de route et gates : [docs/v1-production-delivery.md](docs/v1-production-delivery.md).

## En cours

- [x] AUDIT-V1-001 — Auditer l’état réel Android/iOS/Supabase, reconstruire le chemin critique et publier `docs/audits/2026-07-30-v1-production-readiness.md`.
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

### Stabilisation du dépôt

- [ ] STAB-001 — Fusionner ou fermer proprement la PR parallèle `#34`, puis resynchroniser l'état documentaire après décision.
- [ ] STAB-002 — Retirer les messages techniques et désactiver visuellement toute action sans implémentation réelle après validation des racines V1.
- [x] STAB-003 — Corriger les templates et runbooks locaux, permettre le build sans fichier fournisseur, verrouiller le wrapper Gradle et refuser en CI les secrets ou artefacts mobiles suivis.
- [ ] PR-STAB-003 — PR brouillon empilée `#37` publiée avec `quality` et `iOS simulator build` verts ; obtenir la revue humaine puis la fusionner après `#36`.

### Architecture et environnements

- [x] CI-004 — Migrer les actions GitHub vers des versions compatibles Node 24 sans modifier les gates.
- [x] ARCH-001 — Remplacer la construction manuelle par des modules Koin et composition roots Android/iOS.
- [x] PR-ARCH-001 — PR `#20` mergée après `quality`, tests du graphe et `iOS simulator build` verts.
- [x] CI-005 — Rendre Detekt effectif sur les source sets KMP et traiter la convention Compose sans baseline ni `@Suppress`.
- [x] PR-CI-005 — PR `#21` mergée après `quality`, pgTAP, tests communs et `iOS simulator build` verts.
- [x] ARCH-002 — Déplacer l'UI Compose et les tokens Android de `shared` vers `androidApp` sans régression visuelle.
- [x] PR-ARCH-002 — PR `#22` mergée après tests JVM, `quality`, pgTAP et `iOS simulator build` verts.
- [x] ARCH-003 — Introduire les ViewModels Auth/Explore, `StateFlow` immuable, intents exhaustifs et effets ponctuels.
- [x] PR-ARCH-003 — PR `#23` mergée après tests de flux, `quality`, pgTAP et `iOS simulator build` verts.
- [x] ARCH-004 — Sortir `DispatcherProvider` du domaine, déplacer son binding dans la composition root et verrouiller les imports/emplacements du domaine via `verifyDomainPurity`.
- [ ] PR-ARCH-004 — PR empilée `#36` publiée avec CI verte ; obtenir la revue humaine puis la fusionner après la PR de sécurité `#35`.
- [x] NAV-001 — Livrer navigation Android et SwiftUI natives avec routes et deep links typés.
- [x] PR-NAV-001 — PR `#24` mergée après `quality`, pgTAP et `iOS simulator build` verts.
- [ ] ENV-001 — Créer et relier Supabase/Firebase staging et production, GitHub Environments et contrats de secrets sans valeur sensible.
  - [x] ENV-001A — Livrer les contrats/injections sans secret et protéger les GitHub Environments staging/production.
  - [ ] ENV-001B — Créer les projets Supabase/Firebase dans les organisations choisies par le propriétaire et renseigner leurs variables/configurations ; aucun CDN ni IAM de publication n'est requis pour l'intro embarquée.
- [x] ANDROID-REL-001 — Ajouter variantes debug/staging/release, versionnement, minification, icônes, splash et signature injectée.
- [x] PR-ANDROID-REL-001 — PR `#26` mergée après `quality`, pgTAP et `iOS simulator build` verts.
- [x] IOS-REL-001 — Ajouter configurations Xcode, entitlements, Privacy Manifest, assets et signature injectée.
- [x] PR-IOS-REL-001 — PR `#27` mergée après `quality`, pgTAP et les trois configurations simulateur vertes.
- [ ] OBS-001 — Intégrer Firebase Android/iOS pour Analytics, Crashlytics, Performance et Remote Config avec consentement.
  - [x] OBS-001A — Livrer les SDK natifs, contrats typés, consentement refusé par défaut, injection de configuration et gates CI sans secret versionné.
  - [ ] OBS-001B — Provisionner les projets Firebase staging/production et vérifier Analytics, Crashlytics, Performance et la capacité Remote Config générique sur appareils avec consentement, valeurs sûres et révocation ; aucun canal média n'y est autorisé.
- [x] PR-OBS-001A — PR `#28` livrée après tests ciblés, `quality`, pgTAP et les trois configurations iOS simulateur vertes.

### Auth et onboarding

- [x] AUTH-002 — Livrer intro vidéo embarquée, reduced-motion et navigation invité sur Android/iOS.
- [x] PR-AUTH-002 — PR `#29` mergée après vérification média, `quality`, pgTAP et les trois configurations `iOS simulator build` vertes.
- [x] REMOTE-INTRO-001 — Implémentation historique livrée dans `#38`, désormais remplacée par ADR-0021 et non retenue comme état cible V1.
- [ ] PR-REMOTE-INTRO-001 — PR brouillon empilée `#38` publiée sur `#37`, run final `30661731938` vert ; ne pas livrer le canal distant sans la tranche de retrait Store-only au-dessus de la pile.
- [x] INTRO-STORE-001 — Retirer URL/téléchargement/cache/quarantaine/purge distants, conserver Remote Config pour les flags sûrs, versionner l'intro embarquée sur Android/iOS et imposer une release Store pour tout changement vidéo.
  - [x] Verrouiller la révision initiale `1`, l'égalité Android/iOS et le couplage octets/révision contre la base Git.
  - [ ] Confirmer provenance, droits de diffusion et approbation éditoriale de chaque média avant build Store.
  - [ ] Prouver première lecture, non-répétition, upgrade vers une révision supérieure, offline et reduced-motion sur les deux plateformes.
- [ ] PR-INTRO-STORE-001 — PR brouillon empilée `#43` publiée sur `#42` ; validations locales, deux revues indépendantes et run GitHub Actions `30733200076` verts ; obtenir la revue humaine avant fusion.
- [x] AUTH-003 — Terminer email OTP, mot de passe, identité, ville/GPS, devise et consentements.
- [x] AUTH-004 — Ajouter connexion mot de passe, oubli/réinitialisation, déconnexion et écrans SwiftUI équivalents.
- [x] PR-AUTH-004 — PR `#31` mergée après seconde `quality`/pgTAP verte et compilation SwiftUI Debug/Staging/Release verte.
- [x] BRAND-001 — Restaurer le wordmark officiel complet au lancement Android/iOS, assurer la continuité jusqu'à la première frame et verrouiller l'intégrité des assets en CI.
- [ ] BRAND-002 — Remplacer le monogramme Android de lancement rééchantillonné par la source haute définition du dépôt, verrouiller hash/géométrie, faire confirmer le master officiel et fournir les captures multi-API Android ainsi que le contrôle iOS.
  - [x] Séparer le canevas launcher 108 dp du splash 288 dp et générer les cinq densités directement depuis le master 1254 px.
  - [x] Verrouiller dimensions, hashes, géométrie, cercle sûr et câblage XML actif ; prouver l'idempotence et les refus négatifs.
  - [ ] Faire confirmer par le propriétaire de marque que `kwabor_icone_app.png` est le master officiel, ou fournir son remplacement avant validation perceptuelle finale.
  - [x] Produire et auditer les neuf captures CI API 30/31/36 en `mdpi`/`xhdpi`/`xxxhdpi` — run `30661731938` vert et intégrité technique 9/9.
  - [x] Produire une matrice perceptuellement recevable : les neuf preuves continues du run `30661731938` montrent HOME → monogramme → wordmark complet → intro.
  - [ ] Valider perceptuellement le raccord complet sur appareils Android Pixel/Samsung et iOS.
- [x] AUTH-005 — Intégrer Google Android/iOS, Apple iOS, activation Promoteur, ré-authentification et Edge Function `account-delete`.
  - [x] Implémentation fonctionnelle et documentation terminées sur la branche atomique.
  - [x] Validations locales finales, reset Supabase, pgTAP/Deno, gates Gradle et compilation Kotlin iOS Simulator terminés.
  - [x] Build Xcode macOS, PR et CI validés avant fusion.

### Offline, préférences et médias

- [x] OFFLINE-001 — Installer Room KMP, schémas exportés et DataStore KMP pour préférences légères.
  - [x] Implémenter le schéma Room v1 Explore, la rétention bornée, l'auto-réparation et les builders Android/iOS.
  - [x] Implémenter DataStore pour ville Explore, locale et devise, avec erreurs de stockage typées et cycle de vie Koin paresseux.
  - [x] Valider 218 tests shared, 160 tests Android, les gates globales, les APK debug/staging minifié et KSP sur les trois cibles iOS.
  - [x] Publier la PR empilée `#40` et obtenir les gates GitHub Actions `quality`/iOS vertes sur le run `30705934250`.
- [ ] PR-OFFLINE-001 — Obtenir la revue humaine de la PR brouillon empilée `#40`, puis la fusionner après `#39`.
- [ ] SYNC-001 — Persister l'outbox, coalescer Like/Favori, appliquer idempotence, backoff et drain réseau/session.
- [ ] DRAFT-001 — Synchroniser les brouillons avec version optimiste et conservation des deux versions en conflit.
- [ ] MEDIA-001 — Créer buckets/RLS, uploads temporaires, validation, downsampling, dérivés et Edge Function `media-finalize`.

### Explore, recherche, détail et devises

- [x] CATALOG-002 — Ajouter un RPC de résumé catalogue paginé par curseur et supprimer le N+1 média.
- [ ] PR-CATALOG-002 — PR brouillon empilée `#39` publiée avec CI verte ; obtenir la revue humaine avant fusion.
- [ ] EXPLORE-002 — Finaliser Explore Android : pagination, refresh, filtres, ville/GPS, sponsors et cache.
  - [x] EXPLORE-002A — Livrer le mur offline-first, la pagination/déduplication, le refresh non destructif,
    la ville persistée/GPS, les référentiels Room v2 et les catégories serveur réelles.
  - [ ] EXPLORE-002B — Versionner les tris/filtres prix-date-événement et le plafond sponsorisé côté serveur,
    puis les brancher sans classement client divergent.
    - [x] EXPLORE-002B1 — Ajouter `event_details`, ses invariants parent/enfant, ses grants/RLS,
      un seed canonique et sa couverture pgTAP sans modifier le classement catalogue existant.
    - [ ] PR-EXPLORE-002B1 — PR brouillon empilée `#42` publiée sur `#41`, run `30729830885`
      entièrement vert ; obtenir la revue humaine, puis la fusionner en respectant l'ordre de la pile.
    - [ ] EXPLORE-002B2 — Figer la popularité, les placements sponsorisés et les intervalles de dates,
      puis livrer le RPC/cursor v2 et les contrats mobile correspondants.
  - [x] PR-EXPLORE-002A — PR brouillon `#41` publiée sur `#40` ; le run `30723036248` a passé
    `quality`, le build simulateur iOS et les preuves de lancement Android API 30/31/36.
- [x] EXPLORE-IOS-001 — Livrer Explore SwiftUI avec les mêmes états et capacités fonctionnelles que le mur Android disponible.
  - [x] Partager le runtime intents/état/effets, brancher le cache Room, la pagination, le refresh, la ville/GPS et les interactions avec soft wall d'authentification.
  - [x] Livrer la grille SwiftUI native, ses états chargement/vide/offline/erreur, l'image pipeline borné et les politiques d'accessibilité/adaptation.
  - [x] Ajouter un smoke test iOS simulateur qui rouvre Room et DataStore sur disque, le brancher à la CI macOS et borner son exécution.
  - [x] Passer la porte locale complète et trois revues indépendantes sans P0/P1/P2 résiduel.
- [ ] PR-EXPLORE-IOS-001 — PR brouillon `#44` publiée sur `#43` ; le run exact-head `30741677132` a passé `quality`, le build Xcode simulateur et les preuves Android API 30/31/36. Obtenir la revue humaine.
- [ ] SEARCH-001 — Livrer récents, autocomplétion, résultats, filtres et fallback texte offline.
- [ ] DETAIL-001 — Livrer le DetailSheet paramétrable avec médias officiels, champs typés, carte et billetterie externe.
  - [x] DETAIL-001A — Livrer le read model atomique publié-only `get_catalog_detail_v1`, ses champs typés,
    contraintes/ACL, son mapping KMP strict et les garde-fous honnêtes du mur Android.
  - [ ] PR-DETAIL-001A — PR brouillon `#45` publiée sur `#44` ; le run exact-head `30759824206`
    valide Gradle, Android API 30/31/36, iOS, les 632 tests SQL standard et les 12 tests concurrents.
    Obtenir la revue humaine.
  - [x] DETAIL-001B — Livrer le DetailSheet Android Compose connecté au read model, sans stub ni CTA factice.
    - [x] Brancher les six variantes, les médias officiels, les champs typés, les horaires, les prix XOF,
      les lieux et les états chargement/introuvable/offline/erreur.
    - [x] Rafraîchir localement les statuts temporels, borner les libellés courts SQL/Kotlin et couvrir
      concurrence, Unicode, médias, accessibilité et politique d'actions par tests.
  - [ ] PR-DETAIL-001B — PR brouillon `#46` publiée au-dessus de `#45` ; le run exact-head
    `30775732082` valide Gradle, Android API 30/31/36, iOS, les 641 tests SQL standard et les
    12 tests concurrents. Obtenir la revue humaine.
  - [x] DETAIL-IOS-001 — Livrer l'écran détail SwiftUI natif avec parité fonctionnelle et accessibilité.
  - [ ] PR-DETAIL-IOS-001 — PR brouillon `#47` publiée au-dessus de `#46` ; le run exact-head
    `30780564021` valide Gradle, Supabase, Android API 30/31/36, les tests Swift, le runtime iOS,
    les XCFrameworks et les configurations Xcode simulateur Debug/Staging/Release. Obtenir la revue
    humaine et la preuve VoiceOver sur appareil.
- [ ] REVIEWS-001 — Ajouter avis paginés, création/édition, photos, likes et réponse Promoteur.
- [ ] ACTIONS-001 — Ajouter partage, itinéraire, contact, signalement, guide et claim.
  - [x] ACTIONS-001A — Livrer les actions externes réellement disponibles sur Android/iOS : itinéraire,
    téléphone, WhatsApp, site, email, menu et billetterie, avec désactivation des événements terminés.
  - [ ] PR-ACTIONS-001A — PR brouillon `#48` publiée au-dessus de DETAIL-IOS-001 ; Android API 30/31
    et iOS sont verts. La preuve brute API 36 reste à rejouer après accord explicite sur le correctif
    minimal du harnais, sans relâcher les critères d'acceptation.
  - [x] GUIDE-001B — Livrer localement la découverte publique « Trouver un guide » : contrat RPC
    versionné, filtres destination/langue/spécialité, pagination, cartes et ouverture du détail sur
    Android Compose et iOS SwiftUI, sans nouvel onglet racine ni sponsoring.
  - [ ] PR-GUIDE-001B — Valider la migration sur une stack Supabase isolée et les sources Swift sous
    Xcode/macOS, puis publier la branche empilée après décision sur la CI API 36.
  - [x] ACTIONS-001C1 — Livrer localement la route interne stricte de fiche Android/iOS : validation
    UUID partagée, dernier lien valide restaurable, attente intro/bootstrap/E3, ouverture Accueil puis
    détail, acquittement conditionnel et invalidation lors des resets sensibles.
  - [ ] PR-ACTIONS-001C1 — Installation, résolution et intents froid/chaud validés sur l'AVD Android
    API 30 ; valider encore les PolicyTests, `onOpenURL` sous Xcode/macOS et le parcours connecté sur
    appareils configurés avant toute publication de la branche.
  - [ ] ACTIONS-001C — Livrer partage et signalement réels avec contrats serveur et parcours natifs.
    - [ ] ACTIONS-001C2 — Après validation des décisions de
      [`l'audit du 3 août`](docs/audits/2026-08-03-actions-001c-reporting-readiness.md), livrer le
      signalement fortement typé d'une fiche : RPC idempotent, RLS/ACL, UI Android/iOS et analytics
      sans PII.
  - [ ] ACTIONS-001D — Livrer le claim sécurisé relié aux organisations vérifiées et aux droits RLS.
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
  - [x] SEC-001A — Fermer les contournements OAuth/onboarding, Social, membres, claims, signalements et classification des fiches ; hotfix ACL séparé de la taxonomie et 316 assertions pgTAP locales vertes.
  - [x] SEC-001B — Faire deux revues techniques, publier la PR brouillon `#35` et obtenir `quality` + `iOS simulator build` verts.
  - [ ] SEC-001C — Obtenir la revue humaine puis fusionner la PR `#35`.
  - [ ] SEC-001D — Exécuter la préflight des données historiques, prouver sauvegarde/restauration et approuver toute quarantaine avant déploiement persistant.
  - [ ] SEC-001E — Qualifier Storage, rate limiting, secrets, advisors et tests IDOR restants sur staging.
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
