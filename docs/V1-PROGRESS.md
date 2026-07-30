# Suivi opérationnel V1

Ce fichier est le tableau de bord courant de la reprise V1. Le détail historique reste dans `PROJECT_STATE.md` et les tickets dans `BACKLOG.md`.

## État global

| Élément | État |
| --- | --- |
| Date du snapshot | 30 juillet 2026 |
| Avancement fonctionnel estimé | 25 à 30 % du PRD V1 actuel |
| Préparation production estimée | 15 à 20 % |
| Décision de release | No-go |
| Branche active | `codex/brand-002-launch-master`, empilée sur `codex/stab-003-repo-integrity` |
| PR de stabilisation | `#37`, brouillon empilé sur `#36`, `quality` et `iOS simulator build` verts |
| PR d’architecture | `#36`, brouillon empilé sur `#35`, `quality` et `iOS simulator build` verts |
| PR de sécurité | `#35`, brouillon, `quality` et `iOS simulator build` verts |
| PR d’authentification parallèle | `#34`, brouillon et non fusionnée |
| Périmètre V1 recommandé | En attente de validation propriétaire |

Le rapport de référence est [l’audit de préparation V1](audits/2026-07-30-v1-production-readiness.md).

## Terminé et prouvé

- Audit croisé du code, des migrations, des tests, de la CI, des configurations mobiles et de la documentation.
- Build Android/KMP/iOS Kotlin réussi.
- `spotlessCheck`, `detekt` et `check` réussis.
- Vérification des assets de marque et du média d’introduction réussie.
- Deux migrations SEC-001A indépendantes appliquées sur la base locale Kwabor.
- Sept suites pgTAP et 316 assertions réussies.
- La nouvelle suite de 74 assertions couvre OAuth, grants exacts, RLS, équipes, claims, signalements, RPC de modération et matrice de fiches ; deux assertions renforcent en plus l’onboarding existant.
- PR brouillon `#35` publiée avec les commits `f6593d4`, `4b9e3fd` et `12ddba2` ; run final `30557976298` vert pour `quality` et le build simulateur iOS.
- ARCH-004 validée localement : `DispatcherProvider` et son implémentation vivent dans `shared.app`, le binding Koin vit dans la composition root et le domaine n'importe plus Coroutines.
- `verifyDomainPurity` refuse le domaine dans un source set plateforme et tout import non Kotlin/non intra-domain ; son test négatif contrôlé et sa passe positive sont prouvés.
- 180 tests partagés, 112 tests JVM Android, compilation Kotlin iOS Simulator, Spotless, Detekt, lint, `check` et APK debug sont verts sur ARCH-004.
- Deux re-revues indépendantes d'ARCH-004 ne relèvent aucun P0/P1/P2.
- Le run GitHub `30564229960` d'ARCH-004 a passé `quality`/pgTAP en 4 min 43 s et le build iOS simulateur en 21 min 59 s.
- STAB-003 validée localement : inventaires et runbooks cohérents, plugins Firebase Android conditionnels, secrets et artefacts mobiles ignorés dans tout sous-dossier, wrapper Gradle 9.4.1 officiel verrouillé et vérificateur d'intégrité branché à la CI.
- Le wrapper a été téléchargé et exécuté depuis un cache vide ; les vérificateurs dépôt/média/marque, Spotless, Detekt, lint, `check`, la compilation Kotlin iOS Simulator et 292 tests Android/shared sont verts. Les APK debug/staging ont été produits sans variable `KWABOR_*` ni fichier Firebase.
- Deux revues indépendantes finales de STAB-003 ne relèvent aucun P0/P1/P2 ; le run `30573401220` de la PR brouillon empilée `#37` a passé `quality`/pgTAP en 4 min 55 s et le build iOS simulateur en 19 min 26 s.
- BRAND-002 corrige le réagrandissement du splash Android : canevas 288 dp séparés du launcher 108 dp, cinq densités dérivées directement du master 1254 px, hashes/géométrie/câblage XML verrouillés et iOS inchangé.
- Le générateur est idempotent ; les cas négatifs Android/iOS/XML sont refusés. Spotless, Detekt, lint, `check`, l'APK debug et 292 tests sont verts localement. Le build de preuve injecte uniquement une URL `.invalid` et une clé factice, tandis que `quality` échoue si la matrice requise n'est pas verte. Les captures API 30/31/36 restent à obtenir et relire en CI.
- Aucun client Web, PWA, WASM ou Desktop détecté.

## En cours

### BRAND-002 — Fidélité du splash système

Objectifs :

- supprimer tout agrandissement d'un dérivé 108 dp par le système Android ;
- verrouiller le master, la géométrie et le câblage réellement actif ;
- produire des cold starts comparables sur API 30/31/36 et trois densités ;
- confirmer qu'aucun asset ou raccord iOS n'a dérivé.

État : implémentation et validations locales terminées. Le workflow d'évidence reproductible est
prêt ; sa matrice, la revue des artefacts et le contrôle perceptuel appareils restent ouverts.

### STAB-003 — Intégrité d'un clone vierge

Objectifs :

- garder des templates exhaustifs mais sans secret et documenter leur routage réel ;
- permettre les builds locaux non distribuables sans fichier fournisseur Firebase ;
- verrouiller la distribution et les launchers Gradle officiels ;
- refuser en CI tout écart de template, wrapper, ignore ou artefact sensible suivi.

État : implémentation, validations locales, deux revues indépendantes, commit, push, publication et CI GitHub de la PR brouillon empilée `#37` terminés. La revue humaine et la fusion après `#36` restent ouvertes. La checklist production « clone vierge et setup documenté » reste ouverte jusqu'à DOC-001 et au provisionnement propriétaire.

### ARCH-004 — Frontière d’exécution du domaine

Objectifs :

- conserver Coroutines et les dispatchers dans la couche application ;
- enregistrer l'implémentation par la composition root Koin, hors des modules data ;
- empêcher la réintroduction d'imports externes ou de sources plateforme dans le domaine.

État : implémentation, validations locales, deux re-revues, commit, push, publication et CI GitHub de la PR brouillon empilée `#36` terminés. La revue humaine et la fusion après `#35` restent ouvertes.

### SEC-001A — Guardrails d’autorisation

Objectifs :

- permettre l’onboarding Google/Apple sans mot de passe Supabase ;
- interdire l’auto-publication Social et les faux compteurs/watermarks ;
- rendre la création et le cycle de vie des membres RPC-only ;
- limiter un Guide aux services de guide et événements ;
- imposer la cohérence catégorie/type/sous-type/classe ;
- empêcher les décisions client sur claims et signalements.

État : implémentation, deux revues techniques, commits, publication et CI GitHub terminés. La revue humaine, la fusion et la préflight de toute base persistante restent ouvertes.

## Prochaines tâches

1. Publier BRAND-002 et obtenir/revoir sa matrice Android API 30/31/36.
2. Obtenir la revue humaine puis fusionner la PR `#35`.
3. Retargeter si nécessaire, relire puis fusionner la PR `#36` vers `main`.
4. Retargeter, relire puis fusionner la PR STAB-003 `#37`, puis BRAND-002.
5. Exécuter la préflight avant tout déploiement sur une base persistante.
6. Clôturer ou fusionner proprement la PR `#34` sans mélanger les branches.
7. Faire valider le périmètre V1 minimal et la navigation.
8. Retirer les CTA/placeholders factices avant d’ajouter de nouveaux écrans.
9. Commencer le résumé catalogue paginé et supprimer le N+1 média.

## Décisions techniques actées pendant la reprise

- Les correctifs de sécurité sont livrés par migration forward-only ; les migrations déployées ne sont pas réécrites.
- Les grants par colonne complètent la RLS pour les champs d’autorité.
- La suspension d’un membre passe par un RPC `SECURITY DEFINER` à `search_path` fermé et autorisation explicite.
- La modération Social passe par un RPC admin explicite ; un client ordinaire et `anon` sont refusés.
- Le hotfix OAuth/ACL est séparé de la validation taxonomique afin qu’une dérive de données ne bloque pas les protections prioritaires.
- La taxonomie d’une fiche est garantie par clé étrangère composite et trigger de rôle.
- Une incohérence de données existante doit faire échouer la migration plutôt qu’être corrigée silencieusement.
- Aucun troisième client applicatif n’est introduit pour l’administration.
- Les dispatchers sont une dépendance de couche application ; le domaine reste Kotlin pur et ne dépend d'aucun SDK asynchrone.
- La gate d'architecture vérifie des règles déterministes d'emplacement et d'import ; une isolation physique de classpath nécessiterait un module et un ADR séparés.

## Problèmes rencontrés

- Le conteneur PostgreSQL local `supabase_db_KWABOR` était arrêté avec le code 137. Il a été redémarré sans toucher aux conteneurs d’autres projets.
- Le lint PostgreSQL signale des fonctions fournies par PostGIS ; aucun diagnostic ne concerne `public` ou `app_private`.
- La passe Gradle complète ARCH-004 a duré 9 min 39 s à froid puis 1 min 25 s avec les caches chauds sur le poste Windows.
- Les tests Kotlin iOS sont compilés mais marqués `SKIPPED` sur Windows ; la preuve d’exécution native doit rester une gate macOS.
- Le disque Windows est arrivé à saturation pendant la première revalidation STAB-003. Seuls le cache wrapper temporaire incomplet et les sorties `androidApp/build`/`shared/build`, entièrement régénérables, ont été supprimés ; le wrapper depuis cache vide puis la porte qualité ont ensuite terminé avec succès.
- La première tentative émulateur API 30 de BRAND-002 a été refusée sous le seuil AOSP de 2 Gio libres. Après restitution d'espace, l'AVD a démarré, le build hermétique et l'installation non-streaming ont réussi, et deux défauts du harness Windows ont été corrigés. L'AVD logiciel a toutefois dépassé le budget de cold start (`Status: timeout`, 12,648 s) ; le script a donc refusé la capture au lieu de produire une fausse preuve. La matrice KVM CI reste la preuve multi-API autoritative.

## Décisions produit en attente

- Périmètre V1 minimal ou maintien du PRD V1 complet.
- Navigation à cinq racines ou navigation réduite sans Social/`+`/Notifications.
- Visibilité invitée de Social si cette verticale reste incluse.
- Administration V1 via Supabase Dashboard + RPC opérateur.
- Villes, catégories, volume et responsables du corpus éditorial.
- Minima Android 26 et iOS 17.

## Reporté après la V1 recommandée

- Social et follows ;
- avis/réponses et profils publics ;
- contribution publique et ListingWizard ;
- dashboard Promoteur, équipes et publicité ;
- paiement FedaPay ;
- notifications marketing ;
- assistant IA ;
- traduction automatique et conversion de devises.

## Checklist de mise en production

### Produit

- [ ] Périmètre V1 et navigation approuvés.
- [ ] Explore, détail et favoris complets sur Android et iOS.
- [ ] Aucun écran ou CTA factice.
- [ ] Corpus éditorial et médias de lancement approuvés.
- [ ] Administration opérateur testée.

### Sécurité et données

- [ ] SEC-001A fusionné et déployé.
- [ ] Préflight des données historiques et restauration de sauvegarde approuvées avant tout déploiement persistant.
- [ ] Matrice RLS/IDOR négative complète.
- [ ] Storage et uploads malveillants testés.
- [ ] Secrets/advisors/rate limits sans critique.
- [ ] Sauvegarde, restauration et rollback prouvés.
- [ ] CGU, confidentialité et licence UGC approuvées.

### Qualité

- [ ] Clone vierge et setup documenté.
- [ ] Build production Android/iOS signé.
- [ ] Tests unitaires, UI, E2E et pgTAP verts.
- [ ] Accessibilité TalkBack/VoiceOver qualifiée.
- [ ] Budgets performance et réseau tenus sur appareils cibles.
- [ ] Gestion d’erreur et offline des parcours critiques qualifiés.

### Environnements et distribution

- [ ] Staging Supabase/Firebase provisionné et qualifié.
- [ ] Production Supabase/Firebase provisionnée.
- [ ] OAuth, SMTP, APNs et certificats prouvés.
- [ ] AAB interne et build TestFlight validés.
- [ ] Métadonnées stores, support et confidentialité publiés.
- [ ] Rollout progressif et kill switch testés.

### Après déploiement

- [ ] Alertes crash/auth/API/data actives.
- [ ] Smoke Android/iOS à 2 h et 24 h.
- [ ] Revue incidents et métriques à J+7.
