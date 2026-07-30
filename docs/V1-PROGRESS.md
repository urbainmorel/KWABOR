# Suivi opérationnel V1

Ce fichier est le tableau de bord courant de la reprise V1. Le détail historique reste dans `PROJECT_STATE.md` et les tickets dans `BACKLOG.md`.

## État global

| Élément | État |
| --- | --- |
| Date du snapshot | 30 juillet 2026 |
| Avancement fonctionnel estimé | 25 à 30 % du PRD V1 actuel |
| Préparation production estimée | 15 à 20 % |
| Décision de release | No-go |
| Branche active | `codex/sec-001-authorization-guardrails` |
| PR de stabilisation | `#35`, brouillon, `quality` et `iOS simulator build` verts |
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
- PR brouillon `#35` publiée avec les commits `f6593d4` et `4b9e3fd` ; run CI `30556043063` vert pour `quality` et le build simulateur iOS.
- Aucun client Web, PWA, WASM ou Desktop détecté.

## En cours

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
2. Exécuter la préflight avant tout déploiement sur une base persistante.
3. Clôturer ou fusionner proprement la PR `#34` sans mélanger les branches.
4. Faire valider le périmètre V1 minimal et la navigation.
5. Retirer les CTA/placeholders factices avant d’ajouter de nouveaux écrans.
6. Commencer le résumé catalogue paginé et supprimer le N+1 média.

## Décisions techniques actées pendant la reprise

- Les correctifs de sécurité sont livrés par migration forward-only ; les migrations déployées ne sont pas réécrites.
- Les grants par colonne complètent la RLS pour les champs d’autorité.
- La suspension d’un membre passe par un RPC `SECURITY DEFINER` à `search_path` fermé et autorisation explicite.
- La modération Social passe par un RPC admin explicite ; un client ordinaire et `anon` sont refusés.
- Le hotfix OAuth/ACL est séparé de la validation taxonomique afin qu’une dérive de données ne bloque pas les protections prioritaires.
- La taxonomie d’une fiche est garantie par clé étrangère composite et trigger de rôle.
- Une incohérence de données existante doit faire échouer la migration plutôt qu’être corrigée silencieusement.
- Aucun troisième client applicatif n’est introduit pour l’administration.

## Problèmes rencontrés

- Le conteneur PostgreSQL local `supabase_db_KWABOR` était arrêté avec le code 137. Il a été redémarré sans toucher aux conteneurs d’autres projets.
- Le lint PostgreSQL signale des fonctions fournies par PostGIS ; aucun diagnostic ne concerne `public` ou `app_private`.
- La passe Gradle complète dure environ quinze minutes sur le poste Windows.
- Les tests Kotlin iOS sont compilés mais marqués `SKIPPED` sur Windows ; la preuve d’exécution native doit rester une gate macOS.

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
