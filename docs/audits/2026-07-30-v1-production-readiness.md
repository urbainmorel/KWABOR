# Audit de préparation V1 — 30 juillet 2026

Ce document décrit l’état réellement vérifié de Kwabor et propose le chemin minimal vers une V1 Android/iOS exploitable au Bénin.
Il conserve les constats de la baseline auditée ; leur clôture postérieure est suivie dans [`docs/V1-PROGRESS.md`](../V1-PROGRESS.md).

## Synthèse

| Indicateur | État vérifié |
| --- | --- |
| Avancement fonctionnel V1 PRD actuel | **25 à 30 %** |
| Backend Supabase du périmètre PRD actuel | **environ 30 %** |
| Préparation réelle à la production | **15 à 20 %** |
| Android | Auth/onboarding solides, Explore partiel, trois racines encore factices |
| iOS | Auth/onboarding solides, parcours touristique principal absent |
| Supabase | 23 tables publiques avec RLS, mais couverture métier encore incomplète |
| Qualité automatisée | Gradle vert, 316 assertions pgTAP vertes après SEC-001A |
| Distribution | Fondations Android/iOS présentes, aucun AAB/IPA signé et qualifié |
| Décision de lancement | **No-go** tant que les bloqueurs de ce document ne sont pas fermés |

L’estimation n’est pas dérivée du nombre de cases cochées dans `BACKLOG.md`. Elle pondère les parcours réellement utilisables sur Android et iOS, le backend, les contenus, l’exploitation, la sécurité et les preuves de distribution.

## Méthode et sources

L’audit a confronté :

- le code de `shared`, `androidApp`, `iosApp` et `supabase` ;
- `PRD.md`, `DESIGN.md`, les ADR, `PROJECT_STATE.md` et `BACKLOG.md` ;
- les workflows GitHub Actions et les scripts de qualification ;
- les migrations, seeds, policies RLS, RPC et tests pgTAP ;
- les tests Kotlin/Android, le projet Xcode et les configurations de release ;
- l’état Git et la PR `#34` au 30 juillet 2026.

Validations exécutées pendant l’audit :

```powershell
.\gradlew.bat check :androidApp:assembleDebug :shared:compileKotlinIosSimulatorArm64 --console=plain
python tools\verify-onboarding-media.py
python tools\verify-brand-assets.py
supabase migration up --local
supabase test db
supabase db lint --local --level warning
.\gradlew.bat spotlessCheck detekt check --console=plain
git diff --check
```

Le lint PostgreSQL ne remonte aucun diagnostic sur les schémas applicatifs `public` et `app_private`. Ses sorties restantes proviennent des fonctions fournies par l’extension PostGIS.

## Stack et architecture détectées

| Couche | Réalité du dépôt |
| --- | --- |
| Domaine partagé | Kotlin Multiplatform, modèles et contrats Kotlin purs par feature |
| Android | Compose Multiplatform, Navigation Compose, ViewModels et Koin |
| iOS | SwiftUI natif, bridge vers le framework KMP `Shared`, cible iOS 17 |
| Données | Supabase Kotlin/PostgREST/Auth, Ktor, DTO et mappers explicites |
| Backend | PostgreSQL/Supabase, RLS, RPC PL/pgSQL, une Edge Function de suppression de compte |
| Observabilité | Firebase Analytics, Crashlytics, Performance et Remote Config, consentement refusé par défaut |
| Images/média | Coil côté Android et média d’introduction embarqué ; pipeline Storage métier absent |
| Qualité | Spotless, Detekt, lint Android, tests Kotlin, pgTAP et CI macOS |
| Distribution | Variants Android et configurations Xcode, workflows manuels d’artefacts |

Le découpage `presentation -> domain -> data` est globalement respecté. L’exception notable est `DispatcherProvider` dans le domaine : il importe Coroutines et instancie directement `Dispatchers.Default/Main`. Il doit être déplacé hors du domaine et injecté.

Aucune cible applicative Web, PWA, WASM ou Desktop n’est présente. Les occurrences du mot « Web » concernent les clients OAuth serveur, les webhooks ou la documentation qui interdit un nouveau client.

## État fonctionnel réel

| Domaine | Statut | Preuve et limite actuelle |
| --- | --- | --- |
| Scaffold KMP Android/iOS | Terminé et vérifié | Builds Kotlin Android/iOS et CI macOS verts |
| Design system et identité | Terminé et vérifié | Tokens, wordmark et vérificateurs d’assets présents ; BRAND-002 reste ouvert pour le master du monogramme Android |
| Intro et onboarding | Terminé et vérifié | Android/iOS natifs, média embarqué, reprise et consentements |
| Auth email/mot de passe/recovery | Terminé et vérifié | Parcours, stockage sécurisé, tests Kotlin et bridge Swift |
| Google Android/iOS et Apple iOS | À corriger | Code et CI présents, mais onboarding fédéré bloqué dans `main` et fournisseurs réels non provisionnés ; correctif SEC-001A non fusionné |
| Suppression de compte | Terminé mais non vérifié | Edge Function et tests existent ; cron, alertes, rétention juridique et preuve staging manquent |
| Explore Android | Partiellement développé | Grille virtualisée et données Supabase ; pagination, refresh, vraie ville/GPS, recherche et filtres complets manquent |
| Explore iOS | Non commencé | Les racines SwiftUI affichent encore l’état de fondation |
| Recherche | Partiellement développé | Repository partagé présent ; aucun parcours complet Android/iOS |
| Filtres catalogue | À corriger | Plusieurs chips sélectionnables ne déclenchent aucun filtre réel |
| Détail d’une fiche | Non commencé | Contrat repository disponible, aucune route/présentation Android/iOS livrée |
| Like/Favori | Partiellement développé | Mutations et état optimiste présents ; queue mémoire uniquement, aucun écran Favoris |
| Profil et sécurité | Partiellement développé | Session, déconnexion et suppression ; profil public, édition, contenus et préférences absents |
| Social | Hors périmètre V1 recommandé | Schéma et modèles partiels ; feed, composeur, média et parité iOS absents |
| Contribution de fiche | Hors périmètre V1 recommandé | Contrats partiels ; wizard, brouillons persistés, upload et modération absents |
| Notifications | Hors périmètre V1 recommandé | Fondations/permissions seulement ; tokens, dispatcher, centre et réglages absents |
| Organisations et équipes | Partiellement développé | Modèle, data et RPC ; aucune UI opérateur ou promoteur exploitable |
| Promotion et paiement | Hors périmètre V1 recommandé | ADR seulement ; aucun ledger, webhook FedaPay ou parcours mobile |
| Assistant IA | Hors périmètre V1 recommandé | ADR seulement ; aucun service, orchestration ou UI |
| Administration | Non commencé | Aucun tableau de bord. Le Supabase Dashboard n’a pas encore de RPC opérateur ni runbook sécurisé |
| Offline | Non commencé | Pas de Room/DataStore métier ; outbox Like/Favori non persistée |
| Média catalogue | Non commencé | Aucun bucket, upload, validation, dérivé ou CDN applicatif |
| Contenus de démonstration | À corriger | 5 villes, 7 catégories, 4 fiches ; URLs média `example.invalid` et aucun corpus éditorial de lancement |
| Observabilité réelle | Terminé mais non vérifié | SDK et consentement présents ; projets Firebase et preuves appareils manquent |
| Release Android | Terminé mais non vérifié | Workflow et signature injectée ; aucun AAB signé qualifié/store |
| Release iOS | Terminé mais non vérifié | Workflow d’archive ; aucun certificat/profil réel, IPA ou TestFlight |
| Tests unitaires | Terminé et vérifié | 287 tests Kotlin/Android observés sans échec lors de l’audit initial |
| Tests base de données | Terminé et vérifié | 316 assertions pgTAP après SEC-001A |
| Tests UI/E2E | Non commencé | Aucun test Compose UI, XCTest/XCUITest ou parcours cross-platform |
| Documentation d’exploitation | Partiellement développé | Runbooks auth/release/observabilité présents ; README racine, testing, data model, incident et restauration manquent |

Il n’existe pas d’application TypeScript. La seule surface TypeScript est l’Edge Function Deno de suppression de compte : ses 19 tests sont verts dans la CI actuelle, mais Deno n’est pas installé sur le poste Windows audité.

Les exigences navigateur « ordinateur », SEO et page 404 ne s’appliquent pas aux clients Android/iOS natifs. Elles ne justifient pas l’introduction d’une cible Web. Les tailles tablette, les liens profonds invalides et les écrans d’erreur mobiles restent en revanche obligatoires.

## Incohérences et dette principales

### Expérience produit

- `Social`, `+`, `Notifications` et plusieurs racines iOS affichent un libellé technique « Socle applicatif en place ».
- Sur Android, recherche, filtres avancés, clic fiche et bouton IA paraissent actifs mais leurs callbacks restent sans effet.
- Treize chips Explore sont visibles, mais seulement cinq valeurs sont réellement traduites en filtre.
- L’activation Promoteur revient à l’accueil sans utiliser sa destination organisation/fiche.
- Les six locales structurelles renvoient actuellement du français ; les applications forcent le français.

### Architecture et données

- `SupabaseCatalogDataSource` effectue une requête média séquentielle par fiche : N+1 bloquant à l’échelle.
- Aucune pagination cursorisée, cache de lecture, déduplication d’appels en vol ou reprise durable n’est livrée.
- `DispatcherProvider` enfreint la pureté du domaine et code les dispatchers en dur.
- Les fiches, médias et contenus d’exploitation n’ont aucun pipeline de qualité.

### Livraison et opérations

- `.env.example` ne couvre pas toutes les variables Firebase, Google iOS et signature Android.
- Le wrapper Gradle n’a pas de `distributionSha256Sum`.
- Il n’existe ni verrouillage/vérification des dépendances Gradle, ni Dependabot/Renovate, ni CODEOWNERS.
- Le scheme Xcode ne contient aucune action de test et la CI ne lance pas explicitement les tests iOS KMP.
- Les workflows de release n’ont jamais été prouvés avec les secrets, certificats et stores réels.

## Bloqueurs de production

| Niveau | Bloqueur | État |
| --- | --- | --- |
| P0 | Onboarding Google/Apple impossible pour un nouveau compte sans mot de passe Supabase | Corrigé localement par SEC-001A, non fusionné |
| P0 | Un auteur pouvait insérer un post Social déjà publié avec faux compteur/watermark | Corrigé localement par SEC-001A, non fusionné |
| P0 | Un Gestionnaire pouvait réassigner ou activer directement un membre subordonné | Corrigé localement par SEC-001A, non fusionné |
| P0 | Type, sous-type, classe et catégorie d’une fiche pouvaient diverger ; Guide trop privilégié | Corrigé localement par SEC-001A, non fusionné |
| P0 | Claims et signalements acceptaient des champs de décision à l’insertion | Corrigé localement par SEC-001A, non fusionné |
| P0 | Le parcours touristique Android/iOS n’existe pas de bout en bout | Ouvert |
| P0 | Aucun moyen sécurisé et documenté de gérer le contenu de production | Ouvert |
| P0 | Aucun environnement staging/production réellement provisionné et qualifié | Intervention propriétaire requise |
| P0 | Aucun corpus éditorial, média réel ni documents juridiques de lancement | Intervention produit/juridique requise |
| P1 | N+1 média, absence de pagination/cache/offline | Ouvert |
| P1 | Aucun test UI/E2E, accessibilité appareil ou performance représentative | Ouvert |
| P1 | Aucun AAB/IPA signé et aucun passage Play/TestFlight | Intervention propriétaire requise |
| P1 | Sauvegarde, restauration, alertes et rollback non prouvés | Ouvert |

## Périmètre V1 recommandé

Le PRD actuel appelle « V1 » un périmètre beaucoup plus large : Social, notifications, contribution, organisations, promotion, paiement et IA. Pour livrer rapidement une version commercialement cohérente, il faut approuver une réduction formelle du périmètre et la tracer dans le PRD, le DESIGN, le backlog et un ADR.

### Parcours principal proposé

1. L’utilisateur lance l’application et continue en invité.
2. Il découvre un catalogue éditorial du Bénin par ville, catégorie et type.
3. Il recherche et filtre des fiches réellement paginées.
4. Il ouvre une fiche détaillée avec galerie, description, horaires, prix XOF, contact, itinéraire et lien de réservation/billetterie externe.
5. Il s’authentifie uniquement pour sauvegarder un favori ou gérer son compte.
6. L’équipe Kwabor publie et corrige les contenus via des RPC opérateur audités et le Supabase Dashboard, sans créer un troisième client applicatif.

### Inclus dans la V1 minimale

- Explore Android et SwiftUI iOS en parité ;
- recherche, filtres, pagination, refresh et états fiables ;
- détail de fiche et actions contact/itinéraire/lien externe ;
- favoris persistés et écran Favoris ;
- compte, session, récupération et suppression ;
- contenus éditoriaux réels pour les villes de lancement ;
- média optimisé via Supabase Storage ;
- opérateur contenu/modération sécurisé et journalisé ;
- cache de lecture/offline minimal ;
- observabilité, sécurité, accessibilité, performance et stores.

### Après la V1

- feed Social, follows, composeur et watermark UGC ;
- avis utilisateurs, réponses et profils publics ;
- contribution publique et ListingWizard ;
- dashboard Promoteur, équipes, budgets et publicité ;
- paiements FedaPay ;
- notifications marketing/push ;
- assistant IA et mode Surprise ;
- conversion de devises, traduction automatique et six langues.

## Backlog priorisé

Les tâches détaillées existantes restent dans `BACKLOG.md`. Les lots ci-dessous forment le chemin critique recommandé et doivent être découpés en PR atomiques.

### Phase 1 — Stabilisation du dépôt

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| STAB-001 | Fusionner ou fermer proprement la PR `#34`, puis synchroniser `PROJECT_STATE.md`, `BACKLOG.md` et `docs/V1-PROGRESS.md` | CI verte, revue humaine | Critique / faible | Branche propre, état documentaire exact, checks GitHub verts | Oui |
| SEC-001A | Fusionner les migrations d’autorisation et de taxonomie, leurs tests et la préflight historique | STAB-001 si conflit avec `#34` | Critique / élevée | Migrations fraîches, 316 pgTAP, SQLSTATE négatifs, grants exacts, sauvegarde/audit avant base persistante | Oui |
| STAB-002 | Retirer les messages techniques et désactiver visuellement toute action sans implémentation réelle | Décision sur les racines V1 | Critique / moyenne | Aucun CTA factice ; tests ViewModel/UI et revue Android/iOS | Oui |
| STAB-003 | Corriger `.env.example`, `iosApp/README.md`, intégrité wrapper Gradle et résidus ignorés | Aucun | Élevée / faible | Clone vierge documenté, build sans secret, checksum wrapper | Oui |

### Phase 2 — Finalisation de l’architecture

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| ARCH-004 | Déplacer l’implémentation de `DispatcherProvider` hors du domaine et injecter dispatchers/horloge | Aucun | Élevée / moyenne | Aucun import Coroutines/plateforme dans le domaine ; tests déterministes | Oui |
| ARCH-005 | Installer Room KMP/DataStore et définir cache, outbox et migrations locales | ADR-0011 | Élevée / élevée | Schémas exportés, migration testée, aucune collection mutable exposée | Oui |
| ARCH-006 | Créer un contrat de résumé catalogue paginé commun Android/iOS | SEC-001A | Critique / élevée | DTO séparé, mapper, curseur stable, tests repository | Oui |

### Phase 3 — Parcours utilisateur principal

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| JOURNEY-001 | Terminer Explore Android : recherche, filtres, ville, pagination, refresh et erreurs | ARCH-006 | Critique / élevée | Aucun contrôle mort ; parcours invité testé | Oui |
| JOURNEY-002 | Livrer Explore SwiftUI avec parité fonctionnelle | ARCH-006 | Critique / élevée | Même contrat/state, VoiceOver, tests bridge/Swift | Oui |
| JOURNEY-003 | Livrer détail fiche Android/iOS et actions sûres | JOURNEY-001/002, MEDIA-001 | Critique / élevée | Galerie, horaires, XOF, contact, itinéraire, liens validés | Oui |
| JOURNEY-004 | Persister favoris et livrer l’écran Favoris Android/iOS | ARCH-005, AUTH-006 | Élevée / moyenne | Optimiste + rollback, reprise offline/login, tests | Oui |
| JOURNEY-005 | Finaliser profil/sécurité minimal et supprimer les racines hors V1 | Décision navigation | Élevée / moyenne | Aucun placeholder ; session, recovery, delete vérifiés | Oui |

### Phase 4 — Contenus et données touristiques

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| DATA-001 | Valider taxonomie, villes et champs éditoriaux du lancement | Décision villes/catégories | Critique / moyenne | Dataset versionné, contraintes DB, revue éditoriale | Oui |
| DATA-002 | Importer un corpus réel sans PII ni URL factice | DATA-001, ADMIN-001 | Critique / élevée | Minimum éditorial approuvé, doublons/GPS contrôlés | Oui |
| MEDIA-001 | Créer buckets, RLS, upload temporaire, validation et dérivés | SEC-002 | Critique / élevée | Formats/tailles/ownership testés, images réelles optimisées | Oui |
| LEGAL-001 | Publier CGU, confidentialité et licence UGC approuvées | Juridique | Critique / moyenne | Révisions actives, hashées, accessibles et acceptées | Oui |

### Phase 5 — Administration

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| ADMIN-001 | Créer RPC opérateur pour fiche, média, claim et signalement | SEC-001A | Critique / élevée | Rôle Admin vérifié, transitions fermées, audit append-only | Oui |
| ADMIN-002 | Documenter l’exploitation via Supabase Dashboard sans nouveau client | ADMIN-001 | Critique / moyenne | Runbook création/revue/publication/rollback testé par un opérateur | Oui |
| ADMIN-003 | Ajouter file de modération, recours et preuves de décision | ADMIN-001 | Élevée / élevée | Aucun changement d’autorité direct ; tests IDOR | Oui |

### Phase 6 — Authentification et autorisations

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| AUTH-006 | Provisionner et prouver email, Google et Apple sur staging | Projets/clients OAuth/SMTP | Critique / élevée | Nouveau compte et compte existant sur appareils réels | Oui |
| AUTH-007 | Prouver session, ré-authentification, suppression et cron de réconciliation | AUTH-006 | Critique / élevée | Scénarios interruption/retry, alertes et rétention validés | Oui |
| AUTHZ-001 | Rejouer la matrice rôles × ressources avec tests négatifs | ADMIN-001 | Critique / élevée | Aucun accès horizontal ou élévation de rôle | Oui |

### Phase 7 — Responsive et accessibilité

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| A11Y-001 | Qualifier petits/grands téléphones et tablettes Android/iOS | Parcours principal complet | Élevée / moyenne | Aucun débordement, rotation/tailles dynamiques maîtrisées | Oui |
| A11Y-002 | AA, TalkBack, VoiceOver, focus, cibles tactiles et reduced motion | A11Y-001 | Critique / moyenne | Audit automatisé + manuel des parcours critiques | Oui |

### Phase 8 — Performance

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| PERF-001 | Supprimer le N+1 média avec RPC/vue de résumé | ARCH-006 | Critique / élevée | Une requête paginée par page, plan SQL mesuré | Oui |
| PERF-002 | Cache, downsampling, lazy-load et budgets réseau/mémoire | MEDIA-001, ARCH-005 | Élevée / élevée | P75 Explore < 1,5 s sur réseau/appareil cible | Oui |
| PERF-003 | Profiler cold start, scroll et détail sur appareils low/mid-range | PERF-001/002 | Élevée / moyenne | Zéro jank critique/OOM, rapport reproductible | Oui |

### Phase 9 — Sécurité

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| SEC-002 | RLS/IDOR négative exhaustive, Storage, validation payload/deep links | ADMIN-001, MEDIA-001 | Critique / élevée | Tests anon/user/rôles/service, Supabase advisors sans critique | Oui |
| SEC-003 | Rate limits, anti-abus, secrets, journaux sans PII et dépendances | Environnements staging | Critique / élevée | Tests de quota/replay, scan secrets/dépendances | Oui |
| SEC-004 | Sauvegarde, PITR, restauration et rollback de migration | Projet Supabase qualifié | Critique / élevée | Restauration chronométrée et preuve documentée | Oui |

### Phase 10 — Tests

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| TEST-001 | Ajouter Compose UI et tests visuels ciblés | Parcours Android complet | Critique / élevée | Explore, détail, favoris, auth et erreurs couverts | Oui |
| TEST-002 | Ajouter XCTest/XCUITest et exécuter les tests iOS KMP en CI | Parcours iOS complet | Critique / élevée | Scheme TestAction réel, simulateurs verts | Oui |
| TEST-003 | E2E staging des parcours critiques et contrats Edge Functions | Staging, AUTH-006 | Critique / élevée | Matrice invité/auth/admin, offline et reprise | Oui |
| TEST-004 | Ajouter couverture informative et seuils sur modules critiques | TEST-001/002 | Moyenne / moyenne | Rapport CI sans baseline masquant les erreurs | Non |

### Phase 11 — Préproduction

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| PREPROD-001 | Provisionner staging Supabase/Firebase et appliquer les migrations fraîches | Comptes propriétaire | Critique / élevée | Environnement reproductible, smoke et advisors verts | Oui |
| PREPROD-002 | Produire AAB/IPA internes signés et exécuter une bêta réelle | PREPROD-001, stores/certificats | Critique / élevée | 10 Android, 5 iOS, 7 jours, zéro P0/P1 | Oui |
| PREPROD-003 | Go/no-go sécurité, performance, accessibilité, juridique et contenu | Toutes phases | Critique / moyenne | Checklist signée avec preuves et rollback | Oui |

### Phase 12 — Mise en production

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| PROD-001 | Préparer fiches stores, confidentialité, support et métadonnées | Juridique/marketing | Critique / moyenne | Play/App Store sans placeholder, revue approuvée | Oui |
| PROD-002 | Déployer DB/Storage/Functions puis publier builds signés | PREPROD-003 | Critique / élevée | Smoke production, checksums, versions et rollback prêts | Oui |
| PROD-003 | Activer un rollout progressif piloté par Remote Config | PROD-002 | Critique / moyenne | 5 % puis paliers validés, kill switch testé | Oui |

### Phase 13 — Vérifications après déploiement

| ID | Objectif et modules | Dépendances | Priorité / complexité | Acceptation et tests | Bloque |
| --- | --- | --- | --- | --- | --- |
| POST-001 | Surveiller crash, auth, latence, erreurs API et intégrité données | PROD-003 | Critique / moyenne | Seuils/alertes actifs, revue à 2 h et 24 h | Oui |
| POST-002 | Vérifier parcours réels Android/iOS et support | POST-001 | Critique / moyenne | Smoke opérateur, comptes neufs, favoris et suppression | Oui |
| POST-003 | Revue à J+7 et décision de généralisation | POST-001/002 | Élevée / faible | Rapport incidents, métriques et actions tracées | Non |

## Ordre d’exécution

```text
SEC-001A + clôture PR #34
  -> suppression des faux parcours
  -> architecture catalogue/cache
  -> Explore + détail + favoris Android/iOS
  -> contenus + média + opérateur
  -> auth réelle staging
  -> sécurité/performance/accessibilité/tests
  -> bêta signée
  -> production progressive
  -> surveillance J+7
```

Chaque flèche représente une gate. Les travaux de phase suivante peuvent être parallélisés uniquement lorsqu’ils ne contournent pas la gate précédente.

## Premiers fichiers concernés

La première stabilisation sans décision produit touche :

- `supabase/migrations/20260730140225_security_authorization_guardrails.sql` ;
- `supabase/migrations/20260730140300_listing_taxonomy_guardrails.sql` ;
- `supabase/tests/security_authorization_guardrails_test.sql` ;
- `supabase/tests/complete_user_onboarding_test.sql` ;
- `docs/runbooks/security-authorization-preflight.md` ;
- `docs/V1-PROGRESS.md` ;
- `BACKLOG.md` ;
- `PROJECT_STATE.md`.

La tranche suivante, après validation du périmètre/navigation, touchera principalement :

- `androidApp/.../KwaborApp.kt` et les actions Explore ;
- `iosApp/Kwabor/ContentView.swift` ;
- `shared/.../catalog` ;
- une nouvelle migration de résumé catalogue ;
- les tests Android, communs, Swift et pgTAP associés.

## Décisions humaines nécessaires

1. Approuver la V1 minimale ou maintenir le périmètre PRD complet.
2. Décider si la navigation conserve cinq racines ou masque Social, `+` et Notifications en V1.
3. Confirmer la visibilité de Social pour un invité si Social reste au périmètre.
4. Valider l’usage du Supabase Dashboard + RPC opérateur comme administration V1.
5. Choisir les villes, catégories, volume et responsables éditoriaux du lancement.
6. Confirmer que `kwabor_icone_app.png` (1254 × 1254) est le master haute définition officiel du
   symbole « K » ou fournir son remplacement officiel avant validation perceptuelle finale.
7. Valider CGU, confidentialité, licence UGC et rétention de suppression.
8. Provisionner les organisations/plans Supabase et Firebase, OAuth, SMTP, APNs, stores, certificats et clé Android.
9. Confirmer Android 26 minimum et iOS 17 minimum.
10. Nommer les bêta-testeurs et le responsable du go/no-go.

## Suite immédiate

Fusionner SEC-001A après CI GitHub, puis prendre la décision de périmètre/navigation. Aucun développement des verticales reportées ne doit démarrer avant cette décision.

Voir aussi [le suivi opérationnel V1](../V1-PROGRESS.md) et [le backlog détaillé](../../BACKLOG.md).
