# Suivi opérationnel V1

Ce fichier est le tableau de bord courant de la reprise V1. Le détail historique reste dans `PROJECT_STATE.md` et les tickets dans `BACKLOG.md`.

## État global

| Élément | État |
| --- | --- |
| Date du snapshot | 3 août 2026 |
| Avancement fonctionnel estimé | 25 à 30 % du PRD V1 actuel |
| Préparation production estimée | 15 à 20 % |
| Décision de release | No-go |
| Branche active | `codex/doc-001-documentation-system`, empilée localement sur `codex/actions-001c-detail-deeplink`, sans push |
| PR de stabilisation | `#37`, brouillon empilé sur `#36`, `quality` et `iOS simulator build` verts |
| PR d’architecture | `#36`, brouillon empilé sur `#35`, `quality` et `iOS simulator build` verts |
| PR de sécurité | `#35`, brouillon, `quality` et `iOS simulator build` verts |
| PR média/BRAND-002 | `#38`, brouillon empilé sur `#37`, sept checks verts sur le commit `94a31d5` |
| Retrait média distant | INTRO-STORE-001 implémenté dans la PR brouillon `#43`, empilée sur `#42` ; run `30733200076` vert |
| Explore iOS | EXPLORE-IOS-001 dans la PR brouillon `#44` sur `#43` ; trois revues vertes et run exact-head `30741677132` entièrement vert |
| Détail catalogue | DETAIL-001A dans `#45`, DETAIL-001B Android dans `#46` et DETAIL-IOS-001 SwiftUI dans `#47` ; run exact-head `30780564021` entièrement vert |
| Documentation | DOC-001 terminé localement : neuf documents de référence vérifiés ; aucune CI ni publication déclenchée |
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
- Le générateur est idempotent ; les cas négatifs Android/iOS/XML sont refusés. Spotless, Detekt, lint, `check`, l'APK debug et 292 tests sont verts localement. Le build de preuve injecte uniquement une URL `.invalid` et une clé factice, tandis que `quality` échoue si la matrice requise n'est pas verte.
- Le run de clôture `30661731938` du commit `94a31d5` a produit les neuf cellules API 30/31/36 × `mdpi`/`xhdpi`/`xxxhdpi` et passé ses sept checks. Archives, hashes, dimensions, états et médias sont techniquement conformes 9/9 ; les neuf preuves continues montrent perceptuellement HOME → monogramme → wordmark complet → intro.
- REMOTE-INTRO-001 a été implémenté historiquement dans la PR `#38`, mais ADR-0021 le supersède avant la V1. INTRO-STORE-001 retire URL/téléchargement/cache/purge distants, conserve Remote Config générique et impose une révision embarquée Android/iOS distribuée exclusivement par release Store.
- INTRO-STORE-001 est validé localement par la porte Gradle complète, les vérificateurs média/marque/dépôt et deux revues indépendantes sans P0/P1/P2. La PR brouillon `#43` est publiée sur `#42` et son run GitHub Actions `30733200076` est entièrement vert.
- EXPLORE-IOS-001 partage le runtime Explore avec Android et livre une surface SwiftUI native : grille adaptative, états chargement/vide/offline/erreur, refresh, pagination, ville/GPS, Like/Favori avec soft wall d'authentification et images HTTPS bornées. Recherche, filtres, assistant et détail restent absents tant que leurs contrats ne sont pas livrés.
- La porte locale EXPLORE-IOS-001 est verte avec 298 tests shared et 142 tests Android sans échec. Trois revues indépendantes ne relèvent plus aucun P0/P1/P2 après correction des courses, des limites/annulations image, de Dynamic Type, des bandeaux, du formatage XOF partagé et du timeout simulateur. Le run exact-head `30741677132` exécute le smoke test Room/DataStore, construit les XCFrameworks et les trois configurations Xcode simulateur, et passe `quality`, Supabase ainsi que les preuves Android API 30/31/36.
- Le premier run de matrice `30585538585` a prouvé le blocage effectif de `quality` et le lancement configuré sur les trois APIs. Il a aussi révélé une assertion temporelle trop stricte : le landing onboarding pouvait remplacer l'intro avant la lecture UI à dix secondes. La capture dure désormais quinze secondes et accepte ces deux surfaces configurées tout en refusant toujours l'écran d'indisponibilité.
- Aucun client Web, PWA, WASM ou Desktop détecté.
- DOC-001 fournit le `README`, l'index documentaire et les guides setup, architecture, données,
  tests, environnements, déploiement et contribution. Les liens locaux, chemins et commandes ont été
  contrôlés contre le dépôt ; le guide qualité KMP reflète désormais la CI Java 21 et la gate `check`.

## En cours

### DETAIL-001A — Read model atomique du détail

Objectifs :

- projeter une fiche publiée dans un RPC V1 atomique, versionné et `security invoker` ;
- typer les six variantes sans fuite Supabase/Ktor dans le domaine ;
- aligner strictement contraintes SQL, sérialisation et validation mobile ;
- retirer d'Explore Android les contrôles factices jusqu'à livraison des contrats correspondants.

État : implémentation et revues SQL/Android terminées sans P0/P1/P2. La porte locale
`spotlessCheck detekt check` est verte en 13 min 24 s, avec 311 tests shared et 147 tests Android
sans échec, lint, pureté du domaine, schémas Room et compilations Kotlin iOS sous Windows. Les plans
pgTAP sont exacts à 202 assertions détail et 57 assertions curseur. La stack locale Kwabor n'est
plus active, mais le run exact-head `30759824206` a validé Gradle, Android API 30/31/36, iOS, les
632 assertions SQL standard et les 12 assertions multi-connexion. Les ACL du rôle `dblink`, le
chemin runtime `service_role`, les dépendances PostGIS et les fixtures actives sont désormais
exercés avec des ACL runtime limitées aux validateurs ciblés, sans reprendre les droits
supplémentaires du harnais local. La PR brouillon `#45` est publiée sur `#44` et attend sa revue
humaine. DETAIL-001 demeure ouvert : le sous-lot A ne livrait lui-même aucune surface, mais
DETAIL-001B fournit le DetailSheet Android et DETAIL-IOS-001 sa parité SwiftUI ; les actions réelles
restent à livrer.

### DETAIL-001B — DetailSheet Android connecté

Objectifs :

- ouvrir une fiche depuis Explore dans une sheet globale 92 % mobile / 85 % tablette ;
- rendre les six variantes, images officielles, états d'erreur, horaires et statuts temporels ;
- conserver une surface honnête sans CTA, carte ou action non implémentés ;
- garantir accessibilité, faible hauteur, Unicode et payloads bornés sur Android modestes.

État local : la porte `spotlessCheck detekt check`, le lint, l'APK Android, les compilations iOS,
330 tests shared et 156 tests Android sont verts en 9 min 56 s. Le runtime partagé traite les
réponses obsolètes et actualise les statuts temporels sans réseau. Les bornes
SQL/Kotlin des libellés courts et collections imbriquées portent le plan pgTAP détail à 211
assertions. Le run exact-head `30775732082` valide Gradle, Android API 30/31/36, iOS, les 641
assertions PostgreSQL standard et les 12 assertions concurrentes sur la stack isolée ; le Docker
Kwabor local reste arrêté. Les vérificateurs dépôt, onboarding Store-only et marque sont verts.
Trois revues indépendantes ont été traitées sans P0/P1/P2 résiduel. La PR brouillon `#46` est
publiée au-dessus de `#45` et attend sa revue humaine.

### DETAIL-IOS-001 — DetailSheet SwiftUI natif

Objectifs :

- ouvrir depuis Explore une sheet globale et adaptative, avec parité fonctionnelle Android ;
- rendre les six variantes, les médias officiels, les champs typés et tous les états réseau ;
- borner le pipeline image et couvrir Dynamic Type, VoiceOver et les tailles d'écran ;
- ne montrer aucune action qui ne fonctionne pas réellement.

État : la fiche SwiftUI native est livrée avec galerie, description extensible, prix XOF, horaires,
services, localisation et statuts temporels. La porte locale passe `check`, Spotless, Detekt, lint,
pureté du domaine, schémas Room et compilations Kotlin/Native iOS et Android, avec 330 tests shared
et 156 tests Android sans échec. Trois revues indépendantes ne relèvent plus aucun P0/P1/P2. La PR
brouillon `#47` est publiée au-dessus de `#46` ; son run exact-head `30780564021` valide la qualité
Gradle/Supabase, Android API 30/31/36, les tests Swift, le runtime iOS, les XCFrameworks et les builds
Xcode simulateur Debug/Staging/Release. La preuve VoiceOver sur appareil physique reste requise ; le
thème sombre complet appartient à SETTINGS-001 et les actions/carte/avis restent hors de ce lot.

### INTRO-STORE-001 — Vidéo distribuée par les Stores

Objectifs :

- embarquer un média byte-identique dans Android et iOS ;
- afficher chaque hausse de révision exactement une fois ;
- supprimer le téléchargement et le cache distants sans retirer Remote Config générique ;
- refuser en CI tout changement d'octets sans hausse coordonnée de révision.

État : implémentation, validations locales, deux revues indépendantes, commit, push, publication de
la PR brouillon `#43` et CI GitHub terminés. La revue humaine, les preuves sur appareils physiques et
les validations de provenance/droits restent ouvertes.

### EXPLORE-IOS-001 — Parité native du mur Explore

Objectifs :

- consommer le même runtime KMP et les mêmes états métier qu'Android ;
- conserver une UI iOS native SwiftUI, adaptative et accessible ;
- brancher le cache Room, DataStore, refresh, pagination, localisation et interactions ;
- prouver la persistance réelle sur simulateur macOS et les configurations Xcode de la tranche.

État : implémentation, porte locale, trois revues indépendantes, commit, push, publication de la
PR brouillon `#44`, build Xcode, smoke test simulateur et CI exacte `30741677132` terminés. La revue
humaine et la preuve VoiceOver/appareil physique restent ouvertes.

### BRAND-002 — Fidélité du splash système

Objectifs :

- supprimer tout agrandissement d'un dérivé 108 dp par le système Android ;
- verrouiller le master, la géométrie et le câblage réellement actif ;
- produire des cold starts comparables sur API 30/31/36 et trois densités ;
- confirmer qu'aucun asset ou raccord iOS n'a dérivé.

État : implémentation, validations locales, CI et matrice KVM perceptuelle 9/9 terminées sur le run
`30661731938`. Le contrôle Pixel/Samsung/iOS physique et la confirmation propriétaire du master
restent ouverts avant release.

### STAB-003 — Intégrité d'un clone vierge

Objectifs :

- garder des templates exhaustifs mais sans secret et documenter leur routage réel ;
- permettre les builds locaux non distribuables sans fichier fournisseur Firebase ;
- verrouiller la distribution et les launchers Gradle officiels ;
- refuser en CI tout écart de template, wrapper, ignore ou artefact sensible suivi.

État : implémentation, validations locales, deux revues indépendantes, commit, push, publication et CI GitHub de la PR brouillon empilée `#37` terminés. La revue humaine et la fusion après `#36` restent ouvertes. DOC-001 documente désormais le setup ; la checklist « clone vierge » reste ouverte jusqu'à son exécution reproductible et au provisionnement propriétaire.

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

1. Obtenir la revue humaine puis fusionner la PR `#35`.
2. Retargeter si nécessaire, relire puis fusionner la PR `#36` vers `main`.
3. Obtenir les revues humaines de DETAIL-001A `#45`, DETAIL-001B `#46` et DETAIL-IOS-001 `#47`, puis faire relire la pile dans l'ordre.
4. Exécuter la préflight avant tout déploiement sur une base persistante.
5. Faire valider le parcours compact et la politique de consentement, puis fermer la PR `#34` comme supersédée et reporter manuellement ses portions auth approuvées au-dessus de `#38`.
6. Faire valider le périmètre V1 minimal et la navigation.
7. Livrer ACTIONS-001 sans réintroduire de CTA factice ; conserver DETAIL-001 ouvert jusqu'aux actions réelles.
8. Reprendre EXPLORE-002B2 après validation des règles de classement et de sponsoring.
9. Faire valider les cinq décisions produit d'ACTIONS-001C2 avant d'implémenter le signalement.

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
- Tout changement des octets vidéo exige des actifs Android/iOS byte-identical, l'incrément simultané des deux révisions embarquées et une release Store ; Remote Config reste réservé aux flags UX sûrs et ne transporte aucun média.
- Le détail catalogue public est lu par un RPC atomique versionné, `security invoker` et publié-only ; le domaine reste fermé sur six variantes et ne dépend ni de Supabase ni de Ktor.

## Problèmes rencontrés

- Le conteneur PostgreSQL local `supabase_db_KWABOR` était arrêté avec le code 137. Il a été redémarré sans toucher aux conteneurs d’autres projets.
- Le lint PostgreSQL signale des fonctions fournies par PostGIS ; aucun diagnostic ne concerne `public` ou `app_private`.
- La passe Gradle complète ARCH-004 a duré 9 min 39 s à froid puis 1 min 25 s avec les caches chauds sur le poste Windows.
- Les tests Kotlin iOS sont compilés mais marqués `SKIPPED` sur Windows ; la preuve d’exécution native doit rester une gate macOS.
- Le disque Windows est arrivé à saturation pendant la première revalidation STAB-003. Seuls le cache wrapper temporaire incomplet et les sorties `androidApp/build`/`shared/build`, entièrement régénérables, ont été supprimés ; le wrapper depuis cache vide puis la porte qualité ont ensuite terminé avec succès.
- La première tentative émulateur API 30 de BRAND-002 a été refusée sous le seuil AOSP de 2 Gio libres. Après restitution d'espace, l'AVD a démarré, le build hermétique et l'installation non-streaming ont réussi, et deux défauts du harness Windows ont été corrigés. L'AVD logiciel a toutefois dépassé le budget de cold start (`Status: timeout`, 12,648 s) ; le script a donc refusé la capture au lieu de produire une fausse preuve. La matrice KVM CI reste la preuve multi-API autoritative.
- Le run KVM `30654047648` était vert et ses artefacts intègres, mais sa revue humaine avait rejeté 4/9 cellules : les acquisitions haute résolution pouvaient entourer le bref wordmark ou le monogramme. Le second enregistrement continu conservait en plus l'onboarding déjà consommé par la première passe ; le reset ajouté avant ce flux a permis au run `30661731938` de fermer le trou avec neuf séquences perceptuellement recevables.

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
