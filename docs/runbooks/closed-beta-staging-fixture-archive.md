# Runbook — archivage contrôlé des quatre fixtures staging

> Statut : implémentation prête, aucune mutation live exécutée. L’opération doit précéder la
> publication des 60 fiches de la bêta fermée. Elle est bloquée tant qu’un reçu B6.02 restaurable,
> non expiré et lié au même SHA n’existe pas.

## But et périmètre

Le projet Supabase désormais retenu pour `staging` contient quatre anciennes fixtures techniques
publiées. Le corpus ADR-0036 doit produire exactement 60 fiches publiées, pas 64. Le workflow
`.github/workflows/closed-beta-staging-fixture-archive.yml` neutralise donc uniquement ces quatre
lignes par un archivage logique :

| UUID | Slug attendu |
| --- | --- |
| `00000000-0000-4000-8000-000000000101` | `porte-du-non-retour-ouidah` |
| `00000000-0000-4000-8000-000000000102` | `marche-dantokpa-cotonou` |
| `00000000-0000-4000-8000-000000000103` | `table-locale-cotonou` |
| `00000000-0000-4000-8000-000000000104` | `festival-culturel-ouidah-test` |

La seule mutation autorisée est `status = 'archive', published_at = null`. Aucun `DELETE`, reset,
seed, réparation d’historique ou modification d’enfant n’est exécuté. L’événement `…0104` est
archivé avant son lieu `…0101` afin de respecter le trigger existant qui interdit d’archiver un
lieu encore utilisé par un événement actif.

## Garanties fail-closed

Chaque lecture utilise un snapshot `REPEATABLE READ READ ONLY`. L’application utilise une
transaction `SERIALIZABLE`, un advisory lock dédié et des locks de table bornés. Le workflow partage
exactement le groupe de concurrence `closed-beta-demo-staging-operations` avec les migrations,
Storage et le catalogue.

Avant toute mutation, l’outil exige simultanément :

- dépôt `urbainmorel/KWABOR`, `refs/heads/main`, SHA complet dispatché = checkout = SHA GitHub ;
- CI `push/main` verte pour ce SHA exact ;
- Environment `staging` sans bypass admin, limité aux branches protégées, avec relecteur indépendant
  et auto-approbation interdite ;
- project ref staging lié à l’URL API, au digest protégé et au session pooler TLS port 5432 ; project
  ref Production valide et distinct ;
- reçu B6.02 du même SHA/CI/cible, `restorable=true`, restauration réelle verte, fingerprint source =
  restauration, artifact et escrow non expirés, RPO/RTO encore valides ;
- quatre UUID/slug exacts, empreinte SHA-256 de tous leurs champs métier exacte, aucune collision de
  slug, statut homogène `publie` ou `archive` et cohérence de `published_at` ;
- aucune autre fiche publiée ;
- inventaire des FK publiques et des triggers `listings` identique à l’audit ; toute évolution de
  schéma impose une nouvelle revue au lieu d’élargir silencieusement le périmètre.

Le premier plan est lui aussi lié au baseline live audité, et ne constitue pas une confiance au
premier état observé :

| Preuve auditée | Valeur exacte attendue |
| --- | --- |
| Contenu métier agrégé des quatre fiches | `420ca974ca1471e336f88761c9ca50ebdd92c3e8ca1f4c584aa129c8b77a8bb3` |
| Fermeture enfants | `14` lignes, `bfdaea9f1926cb2a86b88cb2b633fa39957b542b1c3cdbe2d553e1cf088b94d7` |
| `created_at` des quatre fiches | `18e3adf43fcbf9bd74585d91817303a1816bbf4f0998fb6bee9afdcf4b9bba86` |
| Cycle de vie publié initial | `563590d13fc324cd9d5e5f6634c342620640fc5bc531e56c420eec13a139a1d4` |
| Inventaire FK publiques | `65`, `7741df4e3c035770253e401cd398d9e9967b58a7ec56d84b9de992bdf9483f19` |
| Inventaire triggers `listings` | `7`, `763a9d05798821363e2c78cf0f1c946164e192fdcd1005372c8f88cc886286ef` |

Le hash de cycle de vie publié n’est exigé que pour l’état source : `updated_at` change
légitimement pendant l’archivage. Tous les autres hashes restent identiques en reprise archivée.

L’empreinte relationnelle couvre les enfants directs et la fermeture connue : médias, amenities,
détails typés, événement/lieu, favoris, likes, claims, campagnes et paiements, invitations,
notifications, publications et médias sociaux, chambres, paliers de billets, villes/langues/spécialités
de guide. Seuls les digests et compteurs sont conservés ; aucun contenu, e-mail, UUID utilisateur,
secret ou URI de base ne passe dans le GEL. L’empreinte et le nombre de lignes doivent rester
strictement identiques avant/après.

## Préconditions opérateur

1. Le SHA doit être fusionné sur `main` et le run CI `push` de ce SHA doit être entièrement vert.
2. Les variables/secrets protégés de `staging` décrits dans le runbook B6.02 doivent être complets.
3. Exécuter B6.02 `backup`, pas seulement `readiness`, et relever dans le résumé du run :
   `backup_run_id`, `backup_artifact_id` et le SHA-256 brut de l’artefact.
4. Suspendre tout writer externe vers staging : application, import, webhook et session SQL
   opérateur. Le groupe GitHub sérialise les workflows Kwabor mais ne bloque pas un writer externe.
5. Garder le gel jusqu’à la fin de `verify` et ne pas lancer le corpus démo avant cette preuve.

Si `rpo.applyValidUntil`, l’artefact ou l’escrow B6.02 expire à n’importe quelle étape, recapturer une
sauvegarde complète. Ne jamais prolonger une date dans un reçu.

## Séquence plan → confirmation → apply → verify

Les trois opérations réutilisent le même SHA, le même run CI et le même reçu B6.02.

### 1. Plan non mutatif

```bash
gh workflow run closed-beta-staging-fixture-archive.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=plan \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<push-main-ci-run-id> \
  -f backup_run_id=<b602-run-id> \
  -f backup_artifact_id=<b602-artifact-id> \
  -f backup_artifact_digest=<b602-artifact-sha256>
```

Le résultat attendu est `status=succeeded`, `mutationState=not_started`, `state.mode=published`.
`state.mode=archived` est accepté uniquement pour une reprise idempotente déjà achevée. Relever dans
le résumé du run le `plan_run_id`, le `plan_artifact_id` et le digest brut de l’artefact.

### 2. Revue et confirmation

Un relecteur indépendant vérifie le GEL du plan, les quatre identités, `otherPublishedListings=0`,
les empreintes de contenu/enfants, la référence B6.02 et sa date limite. L’approbation de
l’Environment est distincte de la phrase de confirmation.

### 3. Apply exact

```bash
gh workflow run closed-beta-staging-fixture-archive.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=apply \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<push-main-ci-run-id> \
  -f apply_confirmation=ARCHIVE-EXACT-FOUR-STAGING-FIXTURES \
  -f backup_run_id=<b602-run-id> \
  -f backup_artifact_id=<b602-artifact-id> \
  -f backup_artifact_digest=<b602-artifact-sha256> \
  -f plan_run_id=<plan-run-id> \
  -f plan_artifact_id=<plan-artifact-id> \
  -f plan_artifact_digest=<plan-artifact-sha256>
```

L’outil relit et vérifie le ZIP du plan, son metadata GitHub, son sidecar, le digest de l’état et le
reçu B6.02. Il accepte soit l’état planifié, soit l’état final déjà archivé pour une reprise
idempotente. Toute autre différence de contenu, enfant, statut, schéma ou fiche publiée ferme
l’opération avant mutation.

Le résultat normal est `transition=archived-exact-four`, `mutationState=committed` et
`state.mode=archived`. `transition=already-archived` est un succès idempotent. Après une perte de
connexion, l’outil relit l’état : il qualifie seulement un commit exact ; tout état ambigu produit
`APPLY_INDETERMINATE`, `retryDisposition=DO_NOT_RETRY`.

### 4. Vérification indépendante

```bash
gh workflow run closed-beta-staging-fixture-archive.yml \
  --repo urbainmorel/KWABOR \
  --ref main \
  -f operation=verify \
  -f expected_sha=<exact-main-sha> \
  -f validated_ci_run_id=<push-main-ci-run-id> \
  -f backup_run_id=<b602-run-id> \
  -f backup_artifact_id=<b602-artifact-id> \
  -f backup_artifact_digest=<b602-artifact-sha256>
```

Le résultat attendu est `transition=verified-already-archived`, quatre lignes archivées, zéro fiche
publiée et les mêmes empreintes contenu/enfants. Conserver l’approbation, le run URL et l’artefact.

## Reçu GEL et conservation

Un run réussi publie pendant 90 jours exactement :

- `GEL-G5-STAGING-FIXTURE-ARCHIVE.json` ;
- `GEL-G5-STAGING-FIXTURE-ARCHIVE.json.sha256` ;
- `STAGING-FIXTURE-STATE.json`.

Le résumé Actions expose l’ID et le digest de l’artefact. Le GEL contribue à G5 mais ne ferme pas à
lui seul la gate ni la décision de release. Un run en échec publie au minimum un reçu rouge hashé ;
il n’est jamais réutilisable comme plan.

## Échec, reprise et suite

- `OTHER_PUBLISHED_LISTING_FOUND` : arrêter ; identifier l’auteur de la publication. Ne pas élargir
  la liste cible.
- `FIXTURE_CONTENT_DRIFT`, `PUBLIC_FK_SCHEMA_DRIFT` ou `LISTING_TRIGGER_DRIFT` : réauditer staging et
  le SHA courant ; ne pas mettre à jour les hashes sans revue du contenu et des migrations.
- `STAGING_OPERATION_LOCKED` ou timeout de lock : laisser l’autre opération finir, puis relancer un
  plan frais.
- `APPLY_NOT_COMMITTED` : aucune mutation qualifiée ; produire un nouveau plan avant toute reprise.
- `APPLY_INDETERMINATE` : ne pas relancer. Inspecter le GEL, faire une lecture indépendante et décider
  entre preuve de l’état final et restauration B6.02.

Ce workflow n’expose volontairement aucune opération de désarchivage. Une restauration hébergée ou
un retour des fixtures à `publie` exige un runbook séparé, une approbation explicite et la sauvegarde
B6.02. Après `verify` vert, lancer la séquence Storage/catalogue du runbook
`closed-beta-catalog-release.md` ; son contrôle global doit alors constater exactement 60 fiches
publiées.
