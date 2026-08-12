# Déploiement et distribution

> KWABOR produit des artefacts Android/iOS par workflows manuels contrôlés. Android peut publier
> explicitement un AAB staging dans Google Play Internal après une seconde approbation protégée ;
> aucun workflow ne réalise de rollout public ni de déploiement Supabase implicite.

## État de livraison

| Cible | Automatisation présente | Preuve encore requise |
| --- | --- | --- |
| Android bêta staging | AAB signé, mapping, SHA-256 et provenance ; job Play Internal séparé | Environments protégés, traitement Play et appareils réels |
| Android public production | Hors workflow G6, aucune publication automatisée | Requalification complète après la bêta fermée |
| iOS bêta staging | archive signée, IPA, dSYM, SHA-256 et provenance ; upload TestFlight interne séparé | Environments protégés, traitement TestFlight et appareils réels |
| iOS public production | Hors workflow G6, aucune soumission App Store automatisée | Requalification complète après la bêta fermée |
| Supabase | Migrations/tests versionnés | Projets staging/production, sauvegarde et déploiement contrôlé |

La bêta fermée reste **no-go** tant que G6 et G7 du
[plan de bêta](closed-beta-delivery-plan.md) ne sont pas satisfaites. La release V1 publique reste
séparément soumise au [plan de livraison production](v1-production-delivery.md).

## Préconditions communes

- commit candidat sur `main`, relu et CI verte ;
- Environments `staging`, `play-internal` ou `production` requis par la plateforme, protégés et
  complets ;
- migrations testées sur une stack isolée, puis préflight sur toute base persistante ;
- version Android/iOS cohérente et supérieure à la dernière version distribuée ;
- CGU, confidentialité, licences média/UGC et formulaires Stores validés ;
- sauvegarde, rollback, observabilité et responsables d'incident identifiés.

## Flux Android

Le workflow `.github/workflows/android-release.yml` est staging-only, manuel et limité au dépôt
canonique sur `main`. Il sépare la construction B7.02 de la publication B7.04 :

1. Fournir `expected_sha`, `version_code` et `version_name`. Laisser
   `publish_to_play_internal=false` pour produire uniquement l'archive.
2. Le job `build` exige que `expected_sha` soit le SHA sélectionné de `main` et qu'un run `CI`
   déclenché par `push` soit vert sur ce SHA exact.
3. Après approbation de l'Environment `staging`, vérifier que seules les branches protégées sont
   admises, sans politique personnalisée hybride, avec reviewer sans auto-approbation et
   interdiction du bypass administrateur.
4. Vérifier les identités Supabase/Firebase staging, construire `bundleStaging`, retirer sa signature
   debug puis signer l'AAB avec la clé d'upload protégée.
5. Archiver l'AAB, `mapping.txt`, `KWABOR-SHA256SUMS.txt` et
   `KWABOR-ANDROID-PROVENANCE.json`, avec URL et digest Actions.
6. Si une publication a été demandée, attendre l'approbation distincte de l'Environment
   `play-internal` et la confirmation `PUBLISH-EXACT-AAB-TO-PLAY-INTERNAL`.
7. Le job `publish` retélécharge l'artefact du même run, revérifie provenance, hashes et signature,
   puis téléverse exclusivement vers `tracks: internal`. Il n'accède à aucun backend production et
   ne peut effectuer aucun rollout public.
8. Vérifier ensuite le traitement Play, l'installation depuis la liste fermée et consigner les
   preuves dans le GEL ; un upload accepté ne ferme pas G6 à lui seul.

Procédure détaillée : [android-release.md](android-release.md).

## Flux iOS

Le workflow `.github/workflows/ios-archive.yml` est staging-only, manuel et sépare deux opérations.

1. `archive-only` exige `expected_sha`, un run CI `main` exact et l'Environment `staging` protégé.
2. Il vérifie les identités staging, construit le XCFramework release, archive avec signature
   manuelle, exporte l'IPA interne et produit xcarchive, dSYM, hashes et provenance.
3. `upload-testflight-internal` exige le `archive_run_id` de cette archive, le même
   `validated_ci_run_id`, une confirmation explicite et l'Environment `testflight-internal` protégé.
4. Le job retélécharge l'artefact immuable, revérifie SHA, signature, profil et backend staging,
   téléverse vers App Store Connect, attend le traitement puis l'associe uniquement au groupe de
   testeurs internes autorisé.
5. Aucun chemin de ce workflow ne soumet l'application à l'App Review ni ne publie une version
   publique.

Procédure détaillée : [ios-release.md](ios-release.md).

## Déploiement Supabase

Le dépôt ne contient pas de workflow de déploiement automatique vers une base KWABOR distante.
Avant toute migration persistante :

1. confirmer le project ref et le tier ;
2. réaliser et vérifier une sauvegarde/restauration ;
3. exécuter la [préflight d'autorisation](runbooks/security-authorization-preflight.md) ;
4. appliquer d'abord sur staging ;
5. exécuter pgTAP, smoke mobile et contrôles RLS/grants ;
6. obtenir l'approbation avant production ;
7. surveiller erreurs, auth et intégrité des données après application.

Toute anomalie de connexion, restauration ou suppression suit le
[runbook Auth/session/suppression](runbooks/auth-session-account-deletion-incident.md).

Les migrations sont forward-only. Ne jamais utiliser `git reset`, une suppression de migration ou
un reset Supabase pour « annuler » un changement déjà appliqué.

## Vidéo d'introduction

La vidéo est un asset embarqué byte-identique sur Android et iOS. Tout changement d'octets exige :

1. provenance, droits et approbation éditoriale ;
2. hausse coordonnée de révision Android/iOS ;
3. vérificateur média vert et preuves sur les deux plateformes ;
4. nouveaux artefacts signés ;
5. nouvelle publication via Google Play et l'App Store.

Aucun CDN, téléchargement distant ou Remote Config média n'est autorisé. Voir le
[runbook Store-only](runbooks/onboarding-video-store-release.md).

## Smoke checks avant rollout

- installation propre puis upgrade depuis la version précédente ;
- intro, onboarding, session, déconnexion et suppression de compte ;
- Explore online/offline, pagination, détail et liens internes ;
- Like/Favori et actions externes avec session valide ;
- aucune configuration de staging dans un artefact production ;
- Analytics/Crashlytics uniquement après consentement ;
- TalkBack/VoiceOver, mémoire, réseau dégradé et absence de P0/P1.

## Rollback

### Application mobile

Arrêter le rollout, conserver les artefacts/hashes, désactiver seulement les capacités disposant
d'un kill switch sûr, puis publier une version corrective avec un numéro supérieur. Un Store ne
garantit pas le retour immédiat de tous les appareils à un ancien binaire.

### Backend

Préférer une migration corrective forward-only. Si l'intégrité ou la sécurité est compromise,
isoler les écritures concernées, appliquer le
[runbook Auth/session/suppression](runbooks/auth-session-account-deletion-incident.md) lorsque ce
périmètre est touché et restaurer uniquement depuis une sauvegarde prouvée avec approbation explicite.

## Après déploiement

- smoke à 2 h et 24 h sur Android/iOS ;
- suivi crash/auth/API et données ;
- contrôle des métriques produit sans PII ;
- revue d'incidents et métriques à J+7 ;
- progression 5 % -> 25 % -> 50 % -> 100 % uniquement après chaque gate.

Étape suivante : [relire les gates de production](v1-production-delivery.md#gates-de-production).
