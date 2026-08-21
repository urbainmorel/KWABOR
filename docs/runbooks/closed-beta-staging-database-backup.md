# Runbook B6.02 — sauvegarde staging chiffrée et restaurée

> Statut : implémentation prête, exécution live fermée tant que la paire age, sa preuve
> d’escrow et le project ref Production distinct ne sont pas provisionnés dans
> l’Environment GitHub `staging`. Aucun Docker local et aucune mutation du projet hébergé ne
> sont autorisés par ce runbook.

## Résultat attendu

B6.02 produit une sauvegarde logique ciblée, chiffrée avant tout upload, puis réellement
restaurée dans un PostgreSQL Supabase jetable sur un runner GitHub-hosted. Elle autorise le
consommateur B6.01 uniquement si toutes les preuves suivantes restent exactes :

- dépôt canonique, `refs/heads/main`, SHA complet, CI `push/main` verte et Environment protégé ;
- projet staging exact, project ref Production distinct, session pooler port 5432 et
  `sslmode=require` ;
- un unique snapshot PostgreSQL `REPEATABLE READ READ ONLY`, exporté par
  `pg_export_snapshot()` et importé à la fois par le dump et par la preuve Auth/Storage vide ;
- dump unique et cohérent de `public`, `app_private` et `supabase_migrations`, avec rôles séparés ;
- chiffrement age X25519 avant la frontière d’artefact ; aucun SQL, tar ou clé privée dans
  `build/closed-beta-staging-database-backup-evidence` ;
- déchiffrement avec l’identité de reprise, restauration Supabase éphémère, contraintes et FK
  toutes validées, sans `session_replication_role=replica` ;
- fingerprint SQL logique source exactement égal au fingerprint restauré ;
- RPO et RTO sous leurs seuils ; reçu GEL, sidecar SHA-256, digest et expiration réelle de
  l’artefact relus via l’API GitHub.

Cette approche suit les recommandations Supabase de dump logique pour les projets Free et de
connexion via session pooler :

- [Database Backups](https://supabase.com/docs/guides/platform/backups) ;
- [Automated backups using GitHub Actions](https://supabase.com/docs/guides/deployment/ci/backups) ;
- [Backup and Restore using the CLI](https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore) ;
- [Supabase CLI `db dump`](https://supabase.com/docs/reference/cli/supabase-db-dump).

## Périmètre exact

Le ciphertext contient :

- `roles.sql`, export officiel `supabase db dump --role-only` ;
- `database.sql`, dump unique `public`, `app_private`, `supabase_migrations` ;
- `PAYLOAD-MANIFEST.json`, digests, fingerprint, préfixe de migrations et preuve de snapshot.

Le catalogue exact des tables hébergées Auth et Storage est verrouillé par le SHA du runner : une
table inconnue, manquante ou renommée impose une nouvelle revue. Les données client de toutes les
tables connues doivent être strictement vides dans le même snapshot, notamment MFA AMR, SAML,
OAuth client state et buckets Analytics/Vector. `auth.users`, `storage.objects` et
`storage.buckets` sont obligatoirement présents. Le producteur ferme l’exécution
avec `MANAGED_AUTH_STORAGE_NOT_EMPTY` dès qu’une ligne client existe.

La configuration Auth, les secrets de plateforme, les Edge Functions et les objets binaires
Storage ne sont pas inclus. Cette limitation est sûre pour cette promotion uniquement parce que
les données Auth/Storage sont prouvées vides. Une analyse lexicale SQL étant contournable
(`ONLY`, `search_path`, SQL dynamique, `COPY`), le consommateur n'accepte que les migrations
pending revues puis épinglées par version et SHA-256. Tout fichier inconnu ou modifié impose une
nouvelle revue B6.02 avant apply.

## Conservation et limite assumée

La sauvegarde durable de ce lot est l’artefact GitHub Actions privé de 90 jours. Aucun vault
externe ni option Supabase payante n’est créé. GitHub expose son ID, son digest SHA-256, son état
`expired` et `expires_at` ; le workflow les relit après upload et B6.01 les relit avant apply.

L’artefact reste supprimable par un administrateur et n’est pas WORM. Il convient à la bêta
fermée avec données démo reconstructibles, mais pas à une obligation réglementaire ou à une
rétention Production. Toute exigence de conservation plus forte impose une décision séparée et
un stockage immuable approuvé.

## Configuration protégée

Toutes les valeurs vont dans l’Environment GitHub `staging`, jamais dans le dépôt ni un input de
dispatch.

### Variables

| Nom | Valeur/contrat |
| --- | --- |
| `KWABOR_ENVIRONMENT` | `staging` |
| `KWABOR_SUPABASE_URL` | `https://<staging-ref>.supabase.co` |
| `KWABOR_SUPABASE_PROJECT_REF` | project ref staging exact |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | project ref Production distinct |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | SHA-256 du project ref staging |
| `KWABOR_STAGING_BACKUP_AGE_RECIPIENT` | recipient public age X25519 |
| `KWABOR_STAGING_BACKUP_ESCROW_MODE` | exactement `offline-two-person` |
| `KWABOR_STAGING_BACKUP_ESCROW_RECIPIENT_SHA256` | SHA-256 du recipient conservé hors dépôt |
| `KWABOR_STAGING_BACKUP_ESCROW_TESTED_AT` | test réel de récupération UTC, moins de 90 jours |
| `KWABOR_STAGING_BACKUP_ESCROW_VALID_UNTIL` | UTC, au moins 90 jours après capture |
| `KWABOR_STAGING_BACKUP_MAX_RPO_SECONDS` | 60–3600 ; valeur initiale recommandée `1800` |
| `KWABOR_STAGING_BACKUP_MAX_RTO_SECONDS` | 60–7200 ; valeur initiale recommandée `1800` |
| `KWABOR_STAGING_BACKUP_RETENTION_DAYS` | exactement `90` |
| `KWABOR_STAGING_BACKUP_LIVE_ENABLED` | `false` pendant la préparation, puis `true` après revue |

### Secrets

| Nom | Contrat |
| --- | --- |
| `KWABOR_STAGING_DATABASE_URL` | `postgresql://postgres.<ref>:<mot-de-passe-percent-encoded>@<session-pooler>:5432/postgres?sslmode=require` |
| `KWABOR_STAGING_BACKUP_AGE_IDENTITY` | identité privée age correspondant au recipient ; copie de reprise hors dépôt sous contrôle de deux personnes |

L’identité privée ne doit pas être générée dans les logs Actions. La générer sur un poste de
confiance, conserver une copie de reprise hors ligne sous contrôle de deux custodians, tester un
cycle chiffrement/déchiffrement, puis renseigner les trois variables d’escrow avec les valeurs
réelles. L’identité GitHub et la copie d’escrow doivent dériver le même recipient.

## Séquence d’exécution

### 1. Readiness sans accès mutatif

Garder `KWABOR_STAGING_BACKUP_LIVE_ENABLED=false`, puis lancer depuis le SHA exact sur `main` :

```bash
gh workflow run closed-beta-staging-database-backup.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=readiness \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-push-main-ci-run-id>
```

Le run valide CI, Environment, cible TLS, recipient, identité et escrow. Il produit
`status=prepared_not_executable`, `restorable=false`. Une valeur manquante produit un GEL rouge
avec un code stable, jamais un succès partiel.

### 2. Gel opérationnel

Avant capture :

1. arrêter tout writer applicatif, import, webhook et accès SQL opérateur vers staging ;
2. confirmer qu’Auth et Storage n’ont encore aucune donnée client ;
3. confirmer la protection Environment et la CI du même SHA ;
4. fixer `KWABOR_STAGING_BACKUP_LIVE_ENABLED=true` ;
5. ne pas démarrer en parallèle les workflows base, catalogue ou Storage : le groupe de
   concurrence GitHub partagé les sérialise, mais ne peut bloquer un writer externe.

### 3. Capture qualifiée

```bash
gh workflow run closed-beta-staging-database-backup.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=backup \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-push-main-ci-run-id> \
  -f capture_confirmation=CAPTURE-ENCRYPTED-STAGING-BACKUP
```

Le runner :

1. valide autorité GitHub, cible session pooler TLS, age et escrow ;
2. exporte les rôles ;
3. ouvre le snapshot, prouve Auth/Storage vides et l’historique de migrations, puis réalise le
   dump unique avec le même snapshot ;
4. calcule le fingerprint source, archive dans `$RUNNER_TEMP`, chiffre vers le dossier de preuve,
   supprime immédiatement SQL et tar clairs ;
5. déchiffre dans `$RUNNER_TEMP`, démarre un Supabase PostgreSQL 17 jetable, restaure sans
   neutraliser les contraintes, puis exige un inventaire non vide et une empreinte exacte des
   contraintes/FK identiques au snapshot source avant de redumper ;
6. exige fingerprint source = restauré, RPO/RTO verts ; la limite d'apply RPO reste ancrée à
   `snapshotEstablishedAt` et ne redémarre jamais après la restauration ; puis écrit GEL + sidecar ;
7. upload seulement ciphertext/GEL/sidecar pendant 90 jours et relit digest/expiration API.

Conserver le `run-id`, l’`artifact-id` et le digest SHA-256 brut affichés dans le résumé GitHub.
Ils deviennent les trois inputs `backup_*` de B6.01.

## Preuves et décision

L’artefact qualifié s’appelle exactement
`kwabor-gel-g5-staging-database-backup-<sha>-<run-attempt>` et contient :

- un unique `*.tar.gz.age` ;
- `GEL-G5-STAGING-DATABASE-BACKUP.json` ;
- `GEL-G5-STAGING-DATABASE-BACKUP.json.sha256`.

Un backup est utilisable seulement si le run est vert et si B6.01 confirme à nouveau : run,
workflow, repo, main, SHA, CI, target, ID/digest/nom/expiration de l’artefact, sidecar, ciphertext,
snapshot partagé, managed-data vide, préfixe distant, fingerprint, contraintes, age/escrow,
RPO/RTO et scope des migrations pending.

## Échecs et reprise

- Le producteur écrit toujours un reçu GEL rouge quand il contrôle l’échec ; `restorable=false`.
- Aucun SQL clair n’est dans le dossier uploadé, même après échec.
- Après timeout brutal, le step `always()` arrête l’ID exact du Supabase jetable ; aucun reset ou
  restore n’est exécuté contre staging.
- Un run rouge, un artefact expiré/supprimé, un escrow périmé, un fingerprint divergent ou une
  donnée Auth/Storage non vide impose une nouvelle capture complète.
- Ne jamais restaurer automatiquement ce bundle sur staging. Une reprise réelle vers un projet
  hébergé exige un runbook et une approbation distincts.
