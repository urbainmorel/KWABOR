# Base Supabase staging — migration et qualification protégées

Ce runbook couvre la planification et la qualification des migrations versionnées de Kwabor sur un
projet Supabase **staging vierge ou déjà aligné**, ainsi que le chemin `apply` préparé mais désactivé
tant que la preuve de sauvegarde restaurable n’a pas de producteur audité. Il ne crée aucun projet,
n’importe pas le catalogue de démonstration et ne ferme pas à lui seul la gate G5. Le reçu produit
est une preuve contributive au GEL G5, avec `gateClosed=false`.

Le seul exécuteur autorisé est le workflow manuel
`.github/workflows/closed-beta-staging-database.yml`. Ne pas exécuter ce chemin depuis un poste
local : le projet interdit Docker local et toute opération distante doit rester sous approbation de
l’Environment GitHub `staging`.

## Garanties du chemin

- dépôt canonique `urbainmorel/KWABOR`, événement manuel et `refs/heads/main` uniquement ;
- `expected_sha` complet égal au SHA sélectionné et au checkout ;
- `validated_ci_run_id` obligatoire, pointant exactement vers un run `ci.yml` terminé avec succès,
  déclenché par `push`, sur `main`, pour le même SHA ;
- Environment `staging` sans bypass administrateur, limité aux branches protégées, avec au moins
  un reviewer et `prevent_self_review=true` ;
- identité Supabase liée par le triplet URL publique / project ref / SHA-256 du project ref ;
- project refs staging et production valides et distincts ;
- URI PostgreSQL lue uniquement depuis le secret protégé, jamais depuis un input ;
- aucune liaison persistante de projet, aucun reset, aucune réparation d’historique et aucun seed ;
- sortie CLI capturée, expurgée et archivée sans afficher l’URI PostgreSQL ;
- concurrence statique `closed-beta-demo-staging-operations` : une seule opération destructive ou
  qualificative de la démo staging peut être active à la fois. Cette chaîne est une dépendance
  d’intégration inter-patch et doit rester strictement identique dans les workflows Database,
  Storage et catalogue.

## Configuration obligatoire de l’Environment `staging`

Les règles GitHub doivent être configurées avant tout run :

| Protection | Valeur exigée |
| --- | --- |
| Admin bypass | désactivé |
| Deployment branches | protected branches uniquement |
| Required reviewers | au moins un reviewer indépendant |
| Prevent self-review | activé |

Variables existantes à renseigner dans l’Environment :

| Variable | Contrat |
| --- | --- |
| `KWABOR_ENVIRONMENT` | exactement `staging` |
| `KWABOR_SUPABASE_URL` | exactement `https://<staging-ref>.supabase.co` |
| `KWABOR_SUPABASE_PROJECT_REF` | project ref staging, 20 caractères minuscules alphanumériques |
| `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF` | project ref production distinct, même format |
| `KWABOR_STAGING_PROJECT_REF_SHA256` | SHA-256 minuscule du project ref staging, sans retour ligne |

Secret existant :

| Secret | Contrat |
| --- | --- |
| `KWABOR_STAGING_DATABASE_URL` | URI PostgreSQL staging percent-encodée, port session `5432`, base `postgres` |

Deux formes de connexion sont acceptées :

```text
postgresql://postgres:<PASSWORD_PERCENT_ENCODED>@db.<staging-ref>.supabase.co:5432/postgres
postgresql://postgres.<staging-ref>:<PASSWORD_PERCENT_ENCODED>@<region>.pooler.supabase.com:5432/postgres
```

Le parseur rejette tout autre schéma, port, utilisateur, nom de base ou hôte. Il rejette aussi les
fragments et tous les paramètres de requête, notamment `host`, `hostaddr`, `service` et
`sslmode`. Les variables libpq et Supabase susceptibles de substituer une cible sont retirées de
l’environnement enfant. Encoder chaque caractère réservé du mot de passe selon RFC 3986 ; ne
jamais copier l’URI dans un input, un commentaire, une issue ou un artefact.

Calculer le digest public sans retour ligne :

```bash
printf '%s' '<staging-ref>' | sha256sum | cut -d' ' -f1
```

## Préparer l’exact-head et le plan

1. Identifier le SHA courant de `main` qui doit être qualifié.
2. Identifier le run `CI` issu d’un `push` sur `main`, vert pour ce SHA. Un run de PR, un rerun sur
   un autre SHA ou un workflow manuel ne convient pas.
3. Exécuter `plan`, conserver son `run-id`, son `artifact-id` et le digest exact
   `<64-hex-minuscules>` renvoyé par la sortie `artifact-digest` de l’action épinglée. L’API REST
   représente le même digest sous la forme `sha256:<64-hex-minuscules>` ; le validateur exige cette
   correspondance exacte.
4. Vérifier le sidecar du reçu téléchargé. Le plan doit être `status=succeeded`, porter le même SHA,
   le même run CI, le même digest de cible et le même manifeste de migrations que la future demande.
5. Faire approuver toute opération par une personne différente de l’initiateur du run.

Une URI saisie manuellement n’est jamais une autorité. Pour la preuve de plan active, le workflow
relit le run et la métadonnée d’artefact par l’API GitHub, vérifie dépôt, workflow, run, SHA, branche,
statut, nom, expiration, taille et digest, puis valide le reçu interne et son sidecar dans le ZIP
immuable. Le même validateur contractuel existe et est testé pour un futur artefact backup, mais
aucune preuve backup n’est acceptée ni téléchargée tant que son producteur audité n’existe pas.

## Opération `plan`

Déclencher d’abord un plan :

```bash
gh workflow run closed-beta-staging-database.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=plan \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-ci-run-id>
```

Après approbation, le workflow commence par une requête SQL strictement read-only de l’historique
distant. L’absence de la table d’historique sur un projet vierge vaut une liste vide ; toute autre
sortie illisible échoue fermement. L’historique distant doit être exactement un préfixe ordonné du
manifeste local. Le reçu archive `pendingCount`, la liste pending et son SHA-256, puis le workflow
exécute uniquement l’équivalent protégé de :

```bash
supabase db push --dry-run --db-url '<protected-staging-uri>'
```

Il n’ajoute ni rôles, ni seed, ni migrations absentes de l’historique versionné. Télécharger
l’artefact `kwabor-gel-g5-staging-database-plan-*`, vérifier le fichier
`GEL-G5-STAGING-DATABASE.json.sha256`, puis faire relire `PLAN-PENDING-CHECK.json` et
`PLAN-DRY-RUN.txt`. Un plan vert constate ce que la CLI appliquerait ; il ne qualifie pas encore la
base.

## Opération `apply` — `PREPARED_NOT_EXECUTABLE`

`apply` est volontairement désactivé dans cette tranche. Le dépôt ne contient aucun workflow
producteur audité de sauvegarde logique Supabase staging (`B6.02`) capable d’émettre un artefact
restaurable et son reçu interne. Le runner ne construit donc aucune commande `db push --yes`.

Les inputs préparés sont :

- `validated_plan_run_id`, `validated_plan_artifact_id`,
  `validated_plan_artifact_digest=<64-hex-minuscules>` ;
- `backup_run_id`, `backup_artifact_id`, `backup_artifact_digest=<64-hex-minuscules>` ;
- la confirmation exacte `APPLY-EXACT-STAGING-MIGRATIONS`.

Une demande syntaxiquement complète valide d’abord l’artefact de plan par l’API GitHub et son ZIP,
puis produit un reçu rouge `status=prepared_not_executable`,
`executionDisposition=PREPARED_NOT_EXECUTABLE`, `mutationState=not_started` et
`errorCode=BACKUP_PRODUCER_MISSING`. Elle ne modifie jamais la base.

Ne pas déclencher `apply` avant l’intégration d’un producteur B6.02 dans un patch dédié. Ce futur
producteur devra être manuel, protégé par le même Environment et le même groupe de concurrence,
émettre un nom d’artefact exact, un digest GitHub et un reçu interne prouvant le même SHA, le même
run CI, le même digest de cible et `restorable=true`. L’activation devra aussi implémenter le contrat
suivant : dès que la commande mutative commence, tout échec ou timeout place d’abord
`mutationState=indeterminate`, lance une réconciliation read-only de l’historique et conserve un
reçu `status=indeterminate` si cette réconciliation est impossible. Un simple échec « retryable »
est interdit après le début d’une mutation.

## Opération `verify`

`verify` ne demande aucune confirmation ni autorité d’artefact `apply` :

```bash
gh workflow run closed-beta-staging-database.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=verify \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<successful-ci-run-id>
```

La qualification `verify` archive les contrôles suivants. Cette même séquence deviendra obligatoire
après `apply` uniquement lorsque celui-ci sera activé par une tranche ultérieure :

1. `supabase migration list` contre l’URI staging explicite ;
2. lint `public`, niveau warning, échec dès warning ;
3. lint `app_private`, niveau warning, échec dès warning ;
4. advisors sécurité, échec dès warning ;
5. advisors performance, warnings archivés sans bloquer, conformément à la CI ;
6. nouveau `db push --dry-run` ;
7. requête strictement read-only de l’historique `supabase_migrations.schema_migrations` ;
8. comparaison exacte et ordonnée avec les versions des fichiers locaux.

La dernière comparaison échoue fermement si une migration locale manque à distance, si une version
distante est inconnue, si une version est dupliquée ou si la sortie CLI n’est pas interprétable.
Un simple code retour vert du dry-run ne remplace donc pas la preuve d’absence de pending migration.

## Artefact et reçu GEL

L’artefact est conservé 90 jours. Il contient selon l’opération :

- provenance CI expurgée ;
- preuve expurgée de protection de l’Environment ;
- identité staging publique, jamais l’URI PostgreSQL ;
- manifeste des migrations locales et SHA-256 de chaque fichier ;
- version CLI épinglée ;
- sorties expurgées du plan et/ou de la qualification ;
- contrôle de concordance de l’historique ;
- preuve de plan validée et disposition `PREPARED_NOT_EXECUTABLE` pour toute préparation `apply` ;
- `GEL-G5-STAGING-DATABASE.json` et son sidecar SHA-256.

Les refus de préflight pris en charge par le runner produisent eux aussi un reçu non sensible avec
`executionDisposition=REJECTED_PREFLIGHT` et `mutationState=not_started`. L’upload est exécuté avec
`if: always()` et `if-no-files-found: error` : l’absence de preuve n’est jamais transformée en
warning vert.

Le reçu lie l’opération au repository, au run et à son attempt, à `expected_sha`, au run CI validé,
à la cible staging et aux digests de toutes les preuves. Il porte
`taskId=B6.01.database-migrations`, `contributesTo=G5` et interdit les champs ou valeurs sensibles.
`status=succeeded` et `qualification=verified` prouvent ce workflow ; `gateClosed=false` rappelle
que Storage, catalogue 60/180, smokes, rollback/réimport et restauration restent à prouver avant de
fermer G5.

Vérifier localement le reçu téléchargé sans afficher son contenu dans des logs publics :

```bash
sha256sum --check GEL-G5-STAGING-DATABASE.json.sha256
```

## Réaction à un échec

- Ne jamais contourner l’Environment, changer la cible ou réparer l’historique dans le même run.
- Télécharger l’artefact d’échec et partir de `errorCode` puis du premier fichier de preuve rouge.
- Si le code indique un drift SHA, CI, Environment ou target, corriger la configuration et créer une
  nouvelle preuve avant toute relance.
- Dans cette version, `mutationState` reste toujours `not_started` pour `apply`. Si un futur patch
  active la mutation, tout état `indeterminate`, `partially_committed` ou `committed_unqualified`
  impose de geler les writers et de suivre le processus de reprise ; ne pas relancer ni effacer la
  base.
- Si les versions locales et distantes divergent, faire une revue humaine des historiques. Ce
  workflow ne modifie jamais artificiellement la table d’historique.

## Références vérifiées

- [Supabase CLI — `db push`](https://supabase.com/docs/reference/cli/v0/supabase-migration#supabase-db-push)
- [Supabase — migrations de base](https://supabase.com/docs/guides/deployment/database-migrations)
- [Supabase — tests et lint](https://supabase.com/docs/guides/local-development/cli/testing-and-linting)
- [Supabase — changelog breaking changes](https://supabase.com/changelog?types=breaking-change)
- [GitHub — protection des Environments](https://docs.github.com/en/rest/deployments/environments)
- [GitHub REST — artefacts Actions](https://docs.github.com/en/rest/actions/artifacts)
- [GitHub REST — runs de workflow](https://docs.github.com/en/rest/actions/workflow-runs)
- [GitHub `upload-artifact` — outputs ID, URL et digest](https://github.com/actions/upload-artifact#outputs)
- [PostgreSQL — fonctions XML read-only utilisées pour l’historique vierge](https://www.postgresql.org/docs/current/functions-xml.html)

Le workflow épingle Supabase CLI `2.111.0`, la même version que la CI du dépôt. Les commandes et
leurs flags ont été vérifiés avec l’aide intégrée de la CLI ; toute montée de version exige de
rejouer les tests statiques et une opération `plan` avant `apply`.
