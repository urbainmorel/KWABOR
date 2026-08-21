# Base Supabase staging — migration et qualification protégées

Ce runbook couvre `plan`, le premier `apply` sur un projet staging réellement vierge, et `verify`
pour les migrations versionnées Kwabor. Il ne crée pas le projet Supabase, n’importe pas le catalogue
de démonstration et ne ferme pas seul G5. Le reçu est une contribution
`taskId=B6.01.database-migrations`, `contributesTo=G5`, avec `gateClosed=false`.

Le seul exécuteur autorisé est le workflow manuel
`.github/workflows/closed-beta-staging-database.yml`. Aucun de ces contrôles ne doit être lancé
contre un projet distant depuis un poste local. Docker local, reset, seed, `supabase link`, réparation
d’historique et cible production sont interdits.

## Garanties d’autorité

- dépôt canonique `urbainmorel/KWABOR`, `workflow_dispatch` et `refs/heads/main` uniquement ;
- `expected_sha` complet, minuscule, égal au SHA dispatché et au checkout ;
- `validated_ci_run_id` obligatoire : run exact de `.github/workflows/ci.yml`, événement `push`,
  branche `main`, même SHA, état `completed`, conclusion `success` ;
- Environment `staging` sans bypass administrateur, avec reviewer, `prevent_self_review=true` et
  branches protégées uniquement ;
- cible liée par URL publique, project ref et SHA-256 du project ref ; staging et production sont
  valides et distincts ;
- URI PostgreSQL issue uniquement du secret de l’Environment, avec parseur strict et environnement
  enfant expurgé des overrides libpq/Supabase ;
- groupe de concurrence statique exact `closed-beta-demo-staging-operations`, commun aux futurs
  workflows Database, Storage et catalogue staging ; cette chaîne est une dépendance inter-patch ;
- Supabase CLI épinglée à `2.111.0`, actions GitHub épinglées par commit, artefacts conservés 90 jours.

## Configuration de l’Environment `staging`

| Protection | Valeur exigée |
| --- | --- |
| Admin bypass | désactivé |
| Deployment branches | protected branches uniquement |
| Required reviewers | au moins un reviewer indépendant |
| Prevent self-review | activé |

Variables existantes :

| Variable | Contrat |
| --- | --- |
| `KWABOR_ENVIRONMENT` | exactement `staging` |
| `KWABOR_SUPABASE_URL` | exactement `https://<staging-ref>.supabase.co` |
| `KWABOR_SUPABASE_PROJECT_REF` | project ref staging, 20 minuscules alphanumériques |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | project ref production distinct |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | SHA-256 minuscule du project ref staging |

Secret existant :

| Secret | Contrat |
| --- | --- |
| `KWABOR_STAGING_DATABASE_URL` | URI staging percent-encodée, session `5432`, base `postgres` |

Formes acceptées :

```text
postgresql://postgres:<PASSWORD_PERCENT_ENCODED>@db.<staging-ref>.supabase.co:5432/postgres
postgresql://postgres.<staging-ref>:<PASSWORD_PERCENT_ENCODED>@<region>.pooler.supabase.com:5432/postgres
```

Tout autre schéma, port, utilisateur, base, hôte, fragment ou paramètre de requête est rejeté,
notamment `host`, `hostaddr`, `service` et `sslmode`. Ne jamais copier l’URI dans un input, une
issue, un commentaire ou un artefact.

Calcul du digest public, sans retour ligne :

```bash
printf '%s' '<staging-ref>' | sha256sum | cut -d' ' -f1
```

## Preuve `fresh empty staging`

L’exception sans backup est volontairement plus stricte qu’une allowlist Supabase versionnée. Une
allowlist des objets initiaux évoluerait avec la plateforme et pourrait accepter silencieusement un
objet nouveau. Le runner exige donc **zéro relation, zéro type applicatif et zéro routine** dans les
deux schémas applicatifs exacts `public` et `app_private`. Tables, tables partitionnées, vues, vues
matérialisées, séquences et foreign tables sont comptées via `pg_class`. Tous les types présents dans
`pg_type` sont comptés, notamment enum, domaine, composite autonome, base, range/multirange, shell
et type tableau généré. Seuls les row types implicitement liés à une table, partition, vue, vue
matérialisée ou foreign table déjà comptée sont exclus ; un composite autonome `relkind='c'` reste
bloquant. `public` doit exister exactement une fois ; `app_private` peut être absent avant la première
migration.

Compromis assumé : une extension, un type ou un objet légitime préinstallé dans `public` bloque aussi
l’exception. Il faut alors produire le backup restaurable B6.02 ; on ne relâche pas la preuve.

Une requête SQL agrégée, read-only et sans ligne utilisateur prend un même snapshot pour prouver :

- historique `supabase_migrations.schema_migrations` vide ou table encore absente ;
- zéro relation, type applicatif et routine dans `public` / `app_private` ;
- présence des trois tables système minimales `auth.users`, `storage.objects`, `storage.buckets` ;
- `0 auth.users`, `0 storage.objects`, `0 storage.buckets` ;
- zéro ligne dans les surfaces Auth pertinentes disponibles : identities, sessions, refresh tokens,
  MFA, one-time tokens, flow state, audit log, SAML relay, SSO, OAuth/OIDC et WebAuthn ;
- zéro ligne dans les surfaces Storage pertinentes disponibles : prefixes, multipart uploads et
  surfaces vectorielles, en plus des objects/buckets.

Seuls les compteurs, leur SHA-256 et le booléen `freshEmptyEligible` sont archivés. Aucune PII ni
ligne métier ne sort de la base. Le compteur `applicationTypeCount` est inclus dans chaque preuve
fresh-empty et donc dans les reçus GEL `plan`, `prepared_not_executable` et `apply`. Une seconde
requête read-only liste séparément les versions afin de prouver que l’historique distant est l’exact
préfixe ordonné du manifeste local. Le nombre de versions doit correspondre au compteur pris dans le
snapshot agrégé.

Cette preuve `zero-objects-and-types-public-app-private-v2` borne naturellement l’exception au
premier apply réussi : dès qu’une migration est inscrite ou qu’un objet/type applicatif existe,
`freshEmptyEligible=false` et B6.02 devient obligatoire. Tout artefact de plan émis avec l’ancienne
preuve v1 doit être remplacé par un nouveau `plan` avant `apply`.

## Opération `plan`

Identifier d’abord le SHA courant de `main` et le run CI `push/main/success` de ce même SHA, puis :

```bash
gh workflow run closed-beta-staging-database.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=plan \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-ci-run-id>
```

Après approbation de l’Environment, `plan` :

1. archive l’historique distant et prouve qu’il est un préfixe local exact ;
2. exécute `supabase db push --dry-run --db-url '<protected-staging-uri>'`, sans seed ;
3. exécute la preuve agrégée `fresh empty` après le dry-run ;
4. vérifie la cohérence du compteur d’historique avec la liste distante ;
5. émet le reçu GEL et son sidecar SHA-256.

Un plan peut être vert tout en indiquant `freshEmptyEligible=false` : il décrit alors correctement un
staging non vierge, mais il n’autorise pas l’exception sans backup. Conserver le `run-id`,
l’`artifact-id` et le digest brut de 64 hexadécimaux renvoyé par `upload-artifact`. L’API GitHub expose
le même digest sous `sha256:<digest>` ; le runner exige les deux représentations exactes.

## Opération `apply` — premier staging vierge uniquement

Le workflow actuel n’accepte aucun backup : les trois inputs `backup_*` sont réservés à B6.02 et
doivent rester vides. Si la base n’est pas strictement vierge, ne pas tenter de contourner ce chemin ;
la tranche B6.02 doit fournir un producteur restaurable audité avant un apply ultérieur.

Avant approbation, confirmer que ce projet neuf n’est routé vers aucun client, job, webhook ou
opérateur SQL. Le groupe GitHub sérialise les workflows Kwabor, mais ne peut pas bloquer une écriture
directe externe entre le dernier snapshot et la commande CLI ; le gel opérationnel du projet est donc
une précondition explicite de l’exception.

Déclencher `apply` avec l’autorité exacte du plan :

```bash
gh workflow run closed-beta-staging-database.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=apply \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-ci-run-id> \
  -f apply_confirmation=APPLY-EXACT-STAGING-MIGRATIONS \
  -f validated_plan_run_id=<plan-run-id> \
  -f validated_plan_artifact_id=<plan-artifact-id> \
  -f validated_plan_artifact_digest=<plan-artifact-raw-sha256>
```

Après approbation indépendante, le runner :

1. relit par l’API GitHub le run de plan et sa métadonnée d’artefact ;
2. vérifie dépôt, workflow, run, attempt, SHA, branche, statut, expiration, nom, taille et digest ;
3. vérifie le ZIP, le reçu interne, son sidecar, la cible, le run CI, le manifeste, la liste pending et
   la preuve `fresh empty` ;
4. exige un plan vierge : historique distant vide et toutes les migrations locales pending ;
5. relit l’historique distant et exige le digest exact du plan ;
6. rejoue un dry-run sans seed ;
7. reprend le snapshot agrégé `fresh empty` juste avant mutation et exige le même digest de compteurs ;
8. exécute uniquement `supabase db push --yes --db-url '<protected-staging-uri>'` ;
9. archive la liste des migrations, les lints `public`/`app_private`, les advisors sécurité/performance,
   un second dry-run et la comparaison exacte de l’historique local/distant.

Toute non-vacuité, dérive ou absence de preuve read-only avant l’étape 8 produit
`status=prepared_not_executable`, `mutationState=not_started`,
`retryDisposition=BACKUP_B6_02_REQUIRED`. La base n’est pas mutée.

## Réconciliation après erreur ou timeout

Le runner ne réessaie jamais `db push`. Après un code non nul ou un timeout, il relit l’historique :

| Preuve read-only | Reçu | Action opérateur |
| --- | --- | --- |
| aucune migration appliquée **et** base toujours strictement vierge | `failed`, `not_committed` | nouveau plan et nouvelle approbation requis |
| liste distante complète et exactement égale au manifeste local | qualification post-apply, puis `succeeded`, `committed`, `EXECUTED_RECOVERED` | conserver l’erreur récupérée dans le reçu |
| préfixe partiel, historique inconnu, objet sans historique ou réconciliation impossible | `indeterminate`, `mutationState=indeterminate`, `DO_NOT_RETRY` | geler les writers et investiguer humainement |

Si la liste est complète mais qu’une qualification échoue, le reçu reste rouge avec
`mutationState=committed_unqualified` et `DO_NOT_RETRY`. Ne jamais reset, réparer l’historique ou
relancer aveuglément.

## Opération `verify`

```bash
gh workflow run closed-beta-staging-database.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=verify \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-ci-run-id>
```

`verify` archive :

1. `supabase migration list` contre l’URI explicite ;
2. lint `public`, puis `app_private`, échec dès warning ;
3. advisors sécurité, échec dès warning ;
4. advisors performance, warnings archivés sans bloquer ;
5. nouveau `db push --dry-run` ;
6. historique read-only et comparaison exacte, ordonnée, sans pending migration.

Une version distante inconnue, absente, dupliquée ou une sortie illisible échoue fermement.

## Artefact et reçu GEL

L’artefact contient la provenance CI, la protection de l’Environment, l’identité publique de cible,
le manifeste local, la version CLI, les sorties expurgées, les preuves d’historique/fraîcheur, la
preuve de plan validée pour `apply`, la réconciliation éventuelle, le reçu GEL et son sidecar.

Les échecs de préflight produisent aussi un reçu non sensible. L’upload utilise `if: always()` et
`if-no-files-found: error`. Le reçu est lié au repository, au run/attempt, à `expected_sha`, au run CI,
à l’opération, à la cible et aux digests de toutes les preuves. Champs sensibles, URI PostgreSQL,
tokens et JWT sont refusés.

Vérifier un artefact téléchargé sans publier son contenu :

```bash
sha256sum --check GEL-G5-STAGING-DATABASE.json.sha256
```

## Références vérifiées

- [Supabase CLI — référence](https://supabase.com/docs/reference/cli/supabase-orgs-list)
- [Supabase — migrations de base](https://supabase.com/docs/guides/deployment/database-migrations)
- [Supabase — tests et lint](https://supabase.com/docs/guides/local-development/cli/testing-and-linting)
- [Supabase — changelog breaking changes](https://supabase.com/changelog?types=breaking-change)
- [Supabase — advisors sécurité/performance](https://supabase.com/docs/guides/database/database-advisors)
- [GitHub — protection des Environments](https://docs.github.com/en/rest/deployments/environments)
- [GitHub REST — artefacts Actions](https://docs.github.com/en/rest/actions/artifacts)
- [GitHub REST — runs de workflow](https://docs.github.com/en/rest/actions/workflow-runs)
- [GitHub `upload-artifact` — outputs](https://github.com/actions/upload-artifact#outputs)
- [PostgreSQL — fonctions XML](https://www.postgresql.org/docs/current/functions-xml.html)
- [PostgreSQL — catalogue `pg_type`](https://www.postgresql.org/docs/current/catalog-pg-type.html)

Les aides intégrées `supabase db push --help`, `supabase db query --help`,
`supabase migration list --help`, `supabase db lint --help` et `supabase db advisors --help` ont été
revérifiées. Toute montée de version exige les tests statiques, un nouveau `plan` et une nouvelle
approbation avant `apply`.
