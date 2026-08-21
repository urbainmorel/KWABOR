# Runbook B6.02 — sauvegarde logique staging qualifiée

> État livré : préparation locale fail-closed, non exécutée. Le workflow live reste
> volontairement désactivé tant que les dépendances externes de ce runbook ne sont pas
> provisionnées et revues. Ce document n'autorise aucune restauration destructive sur staging.

## Décision et périmètre

La plus petite preuve B6.02 honnête est une sauvegarde logique ciblée, chiffrée avant toute
persistance, puis réellement restaurée dans une stack Supabase jetable exécutée par GitHub CI.
Le chemin retenu ne dépend ni d'un plan Supabase payant ni du PITR : Supabase recommande aux
projets sans sauvegarde gérée d'utiliser `supabase db dump` et de conserver une copie hors site.
Le guide officiel sépare les rôles, le schéma, les données et l'historique des migrations, puis
restaure avec `psql --single-transaction` et `session_replication_role = replica` :

- [Database Backups — Supabase](https://supabase.com/docs/guides/platform/backups) ;
- [Automated backups using GitHub Actions — Supabase](https://supabase.com/docs/guides/deployment/ci/backups) ;
- [Backup and Restore using the CLI — Supabase](https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore) ;
- [Supabase CLI, `db dump`](https://supabase.com/docs/reference/cli/supabase-db-dump).

Le bundle contient uniquement :

- les rôles logiques exportés par la CLI ;
- schéma et données de `public` et `app_private` ;
- schéma et données de `supabase_migrations` afin de vérifier exactement le préfixe restauré ;
- un manifeste interne de fingerprints.

Le producteur refuse un ciphertext supérieur à 1,9 Go afin de rester sous la limite de lecture de
l'artefact actuellement imposée par le gate beta-004. Une base staging plus grande requiert une
révision explicite des limites producteur/consommateur avant activation, pas un contournement.

Ce n'est pas une sauvegarde complète du projet Supabase. Les objets binaires Storage, la
configuration Auth, les clés API, les Edge Functions et les réglages de plateforme sont hors
périmètre. Supabase précise notamment que les sauvegardes de base ne contiennent pas les objets
Storage eux-mêmes. Leur reprise doit rester couverte par les workflows et runbooks Storage dédiés.
Le GEL marque donc explicitement `type=targeted-logical` et
`managedAuthStorageDataIncluded=false`. Si une migration encore pending modifie directement les
schémas gérés `auth` ou `storage`, cette sauvegarde ne suffit pas à autoriser son apply.

## État de sécurité initial

Le workflow `.github/workflows/closed-beta-staging-database-backup.yml` n'est exécutable qu'en
`workflow_dispatch`, depuis `main`, sur un SHA complet égal au checkout et à une CI `push/main`
réussie. Il partage le groupe de concurrence littéral
`closed-beta-demo-staging-operations` et référence l'Environment `staging`.

La variable `KWABOR_STAGING_BACKUP_LIVE_ENABLED` doit rester absente ou différente de `true`
jusqu'à la fin du provisionnement ci-dessous. Dans cet état :

- `operation=backup` échoue avec `LIVE_BACKUP_DISABLED` avant tout accès à la base ;
- `operation=readiness` ne se connecte pas à PostgreSQL et produit seulement une preuve
  `prepared_not_executable` avec `restorable=false` ;
- aucune commande live, aucun Docker et aucune API de mutation n'ont été exécutés pour préparer
  ces fichiers.

## Dépendances externes bloquantes

Toutes les lignes suivantes sont des gates. Une valeur déclarative ou une simple URI ne suffit
pas : le workflow relit les ressources GitHub par ID, vérifie leurs digests et échoue fermé.

| Gate | Exigence vérifiée | Dépendance/coût possible |
| --- | --- | --- |
| Environment source | `staging`, reviewers requis, auto-review interdite, bypass admin interdit, branches protégées seulement | Les reviewers d'Environment sur dépôt privé dépendent du plan GitHub ; vérifier le plan avant activation. [Référence GitHub](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments) |
| Projet Supabase | project ref staging exact, digest SHA-256 protégé, URL exacte, project ref production distinct, URL PostgreSQL directe ou session pooler exacte | Projet, mot de passe DB et disponibilité staging réels |
| Vault hors dépôt | dépôt GitHub privé distinct par ID de `urbainmorel/KWABOR`, non archivé, Immutable Releases activé | Compte/dépôt de custody, quota de stockage, minutes Actions et droits d'administration GitHub |
| Chiffrement | paire age X25519 ; recipient public et identité de reprise secrète correspondent exactement | Génération/custody hors dépôt ; aucun coût si custody offline, coût éventuel si KMS choisi |
| Escrow | copie de récupération hors dépôt, au moins deux custodians, mode `kms` ou `offline-two-person`, test de reprise datant de 90 jours au plus, validité couvrant la rétention | KMS/compte externe ou procédure offline à deux personnes |
| Token vault | fine-grained PAT limité au seul vault : Metadata read, Administration read, Contents write | Rotation et propriétaire indépendant ; ne jamais utiliser un PAT personnel large |
| Rétention | vault immuable actif, copie chiffrée publiée et re-téléchargée, policy de custody couvrant toute la durée | GitHub ne fournit pas ici un verrou WORM de durée : la gouvernance du vault et l'audit périodique restent obligatoires |

Une release GitHub immuable protège son tag et ses assets contre la modification ou la suppression
individuelle après publication et génère une attestation. La séquence officielle recommandée est
draft → upload de tous les assets → publication :

- [Immutable releases — GitHub](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases) ;
- [REST repositories: check immutable releases](https://docs.github.com/en/rest/repos/repos?apiVersion=2026-03-10#check-if-immutable-releases-are-enabled-for-a-repository) ;
- [REST releases and asset digests](https://docs.github.com/en/rest/releases/releases?apiVersion=2026-03-10).

Le vault doit être administré comme une sauvegarde : suppression de release ou désactivation de
l'immuabilité interdit immédiatement tout nouvel apply reposant sur ses reçus. Si un verrou de
rétention réglementaire non désactivable est requis, ce producteur ne doit pas être activé avant
remplacement du vault par un fournisseur Object Lock approuvé.

## Configuration protégée

Créer ces valeurs dans l'Environment GitHub `staging`, jamais dans un fichier du dépôt.

### Variables

| Nom | Contrat |
| --- | --- |
| `KWABOR_ENVIRONMENT` | exactement `staging` |
| `KWABOR_SUPABASE_URL` | `https://<project-ref>.supabase.co` |
| `KWABOR_SUPABASE_PROJECT_REF` | project ref staging, 20 caractères minuscules/chiffres |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | project ref production distinct |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | SHA-256 minuscule exact du project ref staging |
| `KWABOR_STAGING_BACKUP_AGE_RECIPIENT` | recipient age public exact |
| `KWABOR_STAGING_BACKUP_OFFSITE_RETENTION_DAYS` | entier 180–3650 ; recommandation initiale : `180` |
| `KWABOR_STAGING_BACKUP_MAX_RPO_SECONDS` | entier 60–3600 ; recommandation initiale : `600` |
| `KWABOR_STAGING_BACKUP_MAX_RTO_SECONDS` | entier 60–7200 ; recommandation initiale : `1800` |
| `KWABOR_STAGING_BACKUP_VAULT_REPOSITORY` | `owner/repository` du vault privé distinct |
| `KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_ID` | ID numérique de la release escrow immuable |
| `KWABOR_STAGING_BACKUP_KEY_ESCROW_RELEASE_TAG` | tag exact de la release escrow |
| `KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_ID` | ID numérique de `kwabor-age-key-escrow.json` |
| `KWABOR_STAGING_BACKUP_KEY_ESCROW_ASSET_SHA256` | SHA-256 exact de cet asset |
| `KWABOR_STAGING_BACKUP_LIVE_ENABLED` | absent/`false` pendant la préparation ; `true` seulement après approbation staff de toutes les gates |

### Secrets

| Nom | Contrat |
| --- | --- |
| `KWABOR_STAGING_DATABASE_URL` | connexion staging directe ou session pooler, mot de passe percent-encoded, port 5432, base `postgres` |
| `KWABOR_STAGING_BACKUP_AGE_IDENTITY` | identité age privée de reprise, jamais loggée ni placée dans un artefact |
| `KWABOR_BACKUP_VAULT_TOKEN` | PAT finement limité au vault ; lecture admin pour l'état immuable, écriture Contents pour releases/assets |

Le fichier public `kwabor-age-key-escrow.json`, préalablement publié dans une release immuable du
vault, suit ce contrat minimal. Il ne contient ni identité age, ni secret KMS, ni nom de custodian :

```json
{
  "ageRecipientSha256": "<64 hex>",
  "custodyMode": "offline-two-person",
  "minimumCustodians": 2,
  "recoveryIdentityStoredOffsite": true,
  "recoveryTestedAt": "2026-08-19T12:00:00Z",
  "schemaVersion": 1,
  "status": "active",
  "type": "kwabor-age-key-escrow",
  "validUntil": "2027-08-20T12:00:00Z"
}
```

Les timestamps de l'exemple sont illustratifs. Utiliser les timestamps réels UTC et publier le
fichier canonique avant de renseigner son ID et son digest.

## Exécution autorisée future

### 1. Readiness non mutante

1. Garder `KWABOR_STAGING_BACKUP_LIVE_ENABLED=false`.
2. Identifier un SHA complet actuellement sur `main` et une CI `push/main` réussie pour ce SHA.
3. Lancer `Closed-beta staging database backup` avec :
   `operation=readiness`, le SHA et le run ID CI exacts, confirmation vide.
4. Vérifier que le run réussit, que le diagnostic contient
   `status=prepared_not_executable`, `restorable=false`, et que toutes les preuves d'autorité sont
   liées au même repo/main/SHA/projet.

Cette étape lit GitHub et valide les secrets/configurations sans connexion PostgreSQL et sans
publication de release.

### 2. Activation contrôlée

Après revue explicite du résultat readiness et des coûts/quotas :

1. approuver la policy de rétention du vault et le test d'escrow ;
2. fixer `KWABOR_STAGING_BACKUP_LIVE_ENABLED=true` dans l'Environment protégé ;
3. relancer immédiatement avec `operation=backup` et
   `capture_confirmation=CAPTURE-ENCRYPTED-STAGING-BACKUP` ;
4. ne réutiliser ni ancien SHA, ni ancien CI run ID.

Le workflow effectue alors, uniquement sur le runner GitHub :

1. vérification repo/main/SHA/CI/Environment/cible/vault/escrow ;
2. lectures PostgreSQL avec `default_transaction_read_only=on` ;
3. dumps rôles, `public`, `app_private` et `supabase_migrations` ;
4. calcul des digests normalisés et du fingerprint source ;
5. archive dans `$RUNNER_TEMP`, chiffrement age vers le dossier de preuve, puis suppression des
   dumps et de l'archive en clair avant tout upload ;
6. déchiffrement avec le secret de reprise, restauration `psql --single-transaction` dans une
   stack Supabase Docker jetable GitHub, redump complet et égalité exacte des fingerprints et du
   préfixe de migrations ;
7. mesure du RTO ;
8. publication de trois assets dans une release draft du vault : ciphertext, manifeste public et
   sidecar SHA-256 ; publication immuable, relecture API des trois digests, puis re-téléchargement
   du ciphertext et contrôle SHA-256 ;
9. calcul du RPO observé, de `applyValidUntil`, de `retentionUntil` et de l'expiration estimée de
   l'artefact Actions ; son `expires_at` faisant autorité doit être relu via l'API après upload ;
10. écriture du GEL final avec `restorable=true`, puis sidecar, uniquement si tout est exact.

Le `RPO observé` est l'intervalle entre le début de capture et la publication immuable. Le `RTO`
mesuré commence avant déchiffrement et s'arrête après restore, redump et égalité exacte. La fenêtre
d'apply expire à `captureCompletedAt + maxRpoSeconds`; la rétention du vault expire à
`publishedAt + offsiteRetentionDays` et l'escrow doit rester valide au-delà.

Le run doit échouer fermé si la restauration, un fingerprint, le RTO, le RPO, l'escrow, la
rétention, l'immuabilité, un asset API ou le re-download dérive.

## Preuves produites

La copie de rétention hors dépôt est la release immuable du vault, pas l'artefact Actions. Sa
durabilité dépend aussi de la gouvernance et de l'audit du vault explicités plus haut. Elle contient :

- `*.tar.gz.age`, seul bundle de données uploadé ;
- `VAULT-G5-STAGING-DATABASE-BACKUP.json` ;
- `VAULT-G5-STAGING-DATABASE-BACKUP.json.sha256`.

L'artefact Actions qualifié s'appelle exactement
`kwabor-gel-g5-staging-database-backup-<sha>-<runAttempt>`, dure 90 jours et transporte les preuves
de gate, dont `GEL-G5-STAGING-DATABASE-BACKUP.json` et son sidecar. GitHub expose son ID, son digest
SHA-256 et son expiration via l'API. Il reste supprimable et expire avec le run : il constitue une
preuve courte durée, jamais la sauvegarde durable.

- [Store and share data with workflow artifacts — GitHub](https://docs.github.com/en/actions/tutorials/store-and-share-data) ;
- [Removing workflow artifacts — GitHub](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts) ;
- [REST Actions artifacts — GitHub](https://docs.github.com/en/rest/actions/artifacts?apiVersion=2026-03-10).

Le GEL final lie explicitement : repo et ID canonique, `refs/heads/main`, workflow, run ID/attempt,
SHA, CI `push/main`, Environment, project ref et digest, host DB digesté, fingerprint source et
restauré, digest du préfixe de migrations, PostgreSQL major, recipient age digesté, escrow,
release/assets immuables, redownload, RPO/RTO, rétention et autorité d'expiration. Le timestamp
Actions contenu dans le GEL est explicitement une estimation ; seul `expires_at` de l'API est
opposable au futur apply.

## Gate que beta-004 devra durcir

Ne pas modifier beta-004 dans ce lot. Lors de son intégration, le producteur ne pourra être déclaré
disponible qu'après ajout de tous les contrôles suivants dans
`tools/closed-beta-staging-database.py` et ses tests :

1. passer `BACKUP_PRODUCER_AVAILABLE` à `True` seulement lorsque le workflow B6.02 est sur `main` ;
2. télécharger le ZIP de l'artefact de backup, pas seulement accepter son ID ou une URI ;
3. vérifier via l'API : run réussi, workflow exact, event `workflow_dispatch`, repo/ID, `main`, SHA,
   run attempt, artefact non expiré, nom exact, ID, digest et `expires_at` postérieur à l'apply ;
4. vérifier le GEL et son sidecar : `schemaVersion`, `taskId=B6.02`, `contributesTo=G5`,
   `operation=backup`, `status=succeeded`, `restorable=true`, workflow/run/attempt/ref/repo/SHA/CI ;
5. recalculer `targetDigestSha256` et exiger cible, project-ref digest et PostgreSQL major identiques
   à l'autorité apply ;
6. exiger fingerprint source = fingerprint restauré, `fingerprintMatch=true`, restore
   `verified=true`, frontière `github-actions-disposable-supabase`, digest/count du préfixe de
   migrations exacts ;
7. exiger `type=targeted-logical`, les cinq dump modes attendus et
   `managedAuthStorageDataIncluded=false` ; analyser l'ensemble exact des migrations pending et
   fermer l'apply si l'une mute un schéma géré exclu, jusqu'à extension/requalification du backup ;
8. exiger recipient age digesté, escrow actif/recovery testé, release de backup immuable, ensemble
   exact des trois asset IDs/digests, re-download vérifié et durée de rétention couvrant l'apply ;
9. refuser l'apply après `rpo.applyValidUntil`, si le RTO dépasse sa cible, si l'artefact Actions est
   expiré, ou si le vault/escrow n'est plus vérifiable en lecture ;
10. conserver le même groupe de concurrence et traiter tout état post-mutation ambigu comme no-go.

Tant que ces contrôles ne sont pas intégrés, beta-004 doit continuer à fermer `apply` avec
`BACKUP_PRODUCER_MISSING`. Un GEL copié à la main, une URI déclarée ou un booléen `restorable` isolé
ne constitue jamais une autorité d'apply.

## Incident et reprise

- Ne jamais restaurer ce bundle directement sur staging depuis ce workflow.
- En cas d'échec avant publication, les éventuels dumps clairs restent dans l'espace éphémère du
  runner et le cleanup les supprime ; aucun artefact qualifié n'est créé.
- En cas d'échec après création de la draft, seuls des assets chiffrés/publics peuvent être présents.
  Un custodian du vault inspecte puis supprime manuellement la draft si nécessaire.
- En cas d'échec après publication, conserver la release immuable mais la considérer non qualifiée
  si aucun GEL final vert ne la référence.
- Pour une reprise réelle, créer un environnement jetable approuvé, récupérer ciphertext et
  identité via deux autorités distinctes, vérifier tous les digests, restaurer et refaire les
  fingerprints avant toute décision. Une procédure séparée et approuvée est requise pour une
  restauration vers staging.
