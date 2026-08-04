# Déploiement et distribution

> KWABOR produit des artefacts Android/iOS par workflows manuels contrôlés. Le dépôt ne publie pas
> automatiquement dans Google Play, App Store Connect ou un projet Supabase distant.

## État de livraison

| Cible | Automatisation présente | Preuve encore requise |
| --- | --- | --- |
| Android staging | APK minifié par workflow manuel | Projet fournisseur, appareil et revue interne |
| Android production | AAB signé, mapping R8 et SHA-256 | Play App Signing, fiche Store et rollout |
| iOS staging/production | `.xcarchive.zip` signé et checksum | Export IPA, upload TestFlight et App Review |
| Supabase | Migrations/tests versionnés | Projets staging/production, sauvegarde et déploiement contrôlé |

La décision de release V1 reste **no-go** tant que les gates du
[plan de livraison](v1-production-delivery.md) ne sont pas satisfaites.

## Préconditions communes

- commit candidat sur `main`, relu et CI verte ;
- environnement `staging` ou `production` protégé et complet ;
- migrations testées sur une stack isolée, puis préflight sur toute base persistante ;
- version Android/iOS cohérente et supérieure à la dernière version distribuée ;
- CGU, confidentialité, licences média/UGC et formulaires Stores validés ;
- sauvegarde, rollback, observabilité et responsables d'incident identifiés.

## Flux Android

Le workflow `.github/workflows/android-release.yml` accepte `staging` ou `production` et uniquement
depuis `main`.

1. Choisir le tier et fournir `version_code`/`version_name`.
2. Laisser le workflow vérifier Supabase, OAuth, Firebase et, en production, la clé d'upload.
3. Exécuter la gate `check` puis construire l'APK staging ou l'AAB production.
4. Télécharger l'artefact, `mapping.txt` et `KWABOR-SHA256SUMS.txt`.
5. Vérifier le hash et archiver le mapping avec la release.
6. Distribuer l'APK staging uniquement par le canal interne approuvé ; ne jamais le téléverser sur
   Play, car il utilise le certificat debug.
7. Pour la production seulement, téléverser manuellement l'AAB dans la piste Play prévue, puis
   suivre le rollout approuvé.

Procédure détaillée : [android-release.md](android-release.md).

## Flux iOS

Le workflow `.github/workflows/ios-archive.yml` accepte `staging` ou `production` depuis `main`.

1. Fournir version, build number, variables fournisseur et secrets de signature du tier.
2. Le workflow vérifie bundle ID, OAuth, Firebase, APNs et Sign in with Apple.
3. Construire le XCFramework release puis archiver avec le profil manuel injecté.
4. Vérifier signature, entitlements, Privacy Manifest, dSYM et checksum.
5. Télécharger et vérifier le `.xcarchive.zip` signé. Il ne peut pas être envoyé directement à
   TestFlight ou à l'App Store.
6. Implémenter et qualifier l'export App Store/IPA suivi de l'upload dans `STORE-IOS-001` avant toute
   distribution TestFlight ; cette gate n'existe pas encore dans le workflow.

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
