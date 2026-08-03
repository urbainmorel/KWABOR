# Incident Auth, session et suppression de compte

> Runbook OPS-001A, complété par SEC-001F, pour les parcours réellement livrés par
> AUTH-003 à AUTH-005 et SETTINGS-001A. Il couvre Android, iOS, Supabase Auth et la fonction
> `account-delete`. Il n'autorise aucune mutation distante à lui seul.

## But et périmètre

Utiliser ce runbook lorsqu'un utilisateur ne peut plus s'inscrire, se connecter,
récupérer son mot de passe, restaurer ou fermer sa session, ou lorsque la
suppression d'un compte ne se termine pas. Il couvre aussi les incidents de
configuration Google/Apple et le job de réconciliation des suppressions.

Le comportement de référence est décrit dans [l'onboarding mobile](../onboarding.md),
[la configuration des environnements](../environment-configuration.md) et
[l'ADR-0017](../adr/0017-native-federated-auth-promoter-activation-account-deletion.md),
complété par
[l'ADR-0025](../adr/0025-ephemeral-account-deletion-step-up-session.md).
Les environnements Kwabor staging/production ne sont pas encore provisionnés : les
procédures distantes ci-dessous sont donc des gates de mise en service, pas une preuve
qu'elles ont déjà été exécutées.

Ce runbook ne couvre pas encore la 2FA, la gestion des appareils, la révocation
utilisateur de toutes les autres sessions, ni un outil opérateur dédié. Ces éléments
restent ouverts dans SETTINGS-001 et OPS-001.

## Gate de mise en service de la suppression

Le correctif SEC-001F est livré localement. Le client ré-authentifie auprès de Supabase Auth dans
une session éphémère avec `MemorySessionManager`, sans persistance et avec `LogLevel.NONE`. Le
`POST account-delete` accepte un
body JSON contenant exactement `idempotency_key` ; mot de passe, ID token, nonce, email et
fournisseur sont refusés. La preuve envoyée à Functions est uniquement le JWT temporaire dans
l'en-tête `Authorization` standard.

L'ouverture de la suppression aux utilisateurs reste néanmoins **interdite en staging et en
production** tant que :

- les claims `session_id` et `amr` réellement émis par email/mot de passe, Google Android/iOS et
  Apple iOS n'ont pas été capturés de façon sûre sur des comptes synthétiques et validés contre la
  fenêtre de 300 secondes avec 30 secondes de tolérance future ;
- la politique Supabase réelle d'accès, de rétention et d'expurgation des en-têtes d'invocation,
  ainsi que tout Log Drain, n'a pas été documentée, approuvée et testée. Le bearer reste un secret.

Le déploiement staging nécessaire à cette vérification est borné à des comptes synthétiques et ne
constitue jamais une ouverture fonctionnelle aux testeurs ou aux utilisateurs. La preuve conserve
seulement la méthode AMR, ses bornes temporelles et le résultat ; jamais le JWT, le bearer ou le
`session_id` brut.

Une présence confirmée de credentials primaires ou d'un bearer en clair dans les journaux est un
P0 : suspendre la fonction, restreindre les accès, préserver les seules métadonnées nécessaires et
appliquer la procédure sécurité/vie privée de rotation et de notification appropriée.

## Autorité et règles de sécurité

- Le responsable d'incident coordonne, qualifie la sévérité et autorise la sortie.
- L'opérateur Supabase dispose d'un accès nominatif audité au bon projet et au bon
  tier. Une deuxième personne habilitée relit toute mutation.
- Le responsable mobile confirme version, build, plateforme et configuration publique
  embarquée. Il ne collecte jamais le contenu du stockage sécurisé.
- Le responsable sécurité/vie privée rejoint immédiatement tout P0 et toute suppression
  appliquée au mauvais compte.
- L'équipe juridique doit approuver la rétention des tombstones avant d'activer la
  purge de production à 30 jours.

Ne jamais placer dans un ticket, un chat, Git, Analytics ou un export non chiffré :
email, mot de passe, OTP, bearer, refresh token, ID token, nonce, token d'invitation,
clé d'idempotence ou UUID utilisateur. Un UUID pseudonyme reste une donnée restreinte :
il n'est utilisé que dans une console sécurisée et n'apparaît pas dans le compte rendu.

Ne jamais :

- contourner RLS, `verify_jwt=true`, `withSupabase({ auth: 'user' })`, la vérification `getUser()`,
  les contrôles `sub`/`session_id`/AMR ou le nonce ;
- remplacer le RPC atomique `prepare_account_deletion_with_session` par une vérification de session
  séparée de la première mutation ;
- fournir une clé `service_role` à un client ou l'utiliser depuis l'application ;
- effacer directement `auth.users`, `account_deletion_requests` ou un marqueur local ;
- modifier `storage.objects.owner_id` pour forcer une suppression ;
- promouvoir une session Recovery vers une session standard ;
- changer un projet, une audience OAuth ou un tier pour « dépanner » un binaire existant ;
- exécuter une mutation sans sauvegarde vérifiée, preuve avant/après, double revue et
  approbation de production.

## Sévérité

| Niveau | Exemples | Première action |
| --- | --- | --- |
| P0 | Session du mauvais compte, accès privé après changement de compte, suppression du mauvais utilisateur, contournement de réauthentification, secret exposé | Stopper le rollout concerné, préserver les preuves, joindre sécurité/vie privée et ne rien corriger directement dans les données |
| P1 | Connexion indisponible pour plusieurs comptes, restauration bloquée à grande échelle, fournisseur entier indisponible, job de réconciliation absent/échoué, tombstone sans utilisateur Auth au-delà du seuil | Ouvrir l'incident, contenir le déploiement, diagnostiquer le tier et restaurer le chemin autoritatif |
| P2 | Un compte/appareil bloqué, OTP retardé ou limité, conflit de propriété/Storage attendu, déconnexion locale échouée | Aider sans demander de secret, vérifier les journaux agrégés et appliquer le scénario ciblé |

Ne sont pas des incidents à eux seuls : annulation volontaire Google/Apple, mauvais
mot de passe, OTP expiré, réponse Recovery identique pour une adresse connue ou
inconnue, ou blocage de suppression par une propriété d'organisation/un objet Storage.
Ils deviennent un incident si le comportement est incohérent, massif ou impossible à
résoudre par le parcours prévu.

## Les quinze premières minutes

1. Ouvrir un identifiant d'incident et noter en UTC : début, tier, project ref, version,
   build, plateforme, fournisseur et nombre approximatif de comptes touchés.
2. Confirmer qu'il s'agit bien du projet du tier annoncé. Ne copier aucune valeur
   secrète ni aucun payload Auth.
3. Si le problème suit un rollout, suspendre sa progression sans modifier le backend à
   l'aveugle. Un correctif mobile exige un build de version supérieure.
4. Consulter l'état du fournisseur, les journaux Auth, les invocations de la fonction
   et le cron, puis exécuter uniquement les diagnostics `READ ONLY` ci-dessous.
5. Classer le cas : Auth/OTP, Google/Apple, restauration/Recovery, déconnexion ou
   suppression de compte.
6. Pour un P0, préserver immédiatement les artefacts, restreindre l'accès à l'incident
   et obtenir l'avis sécurité/vie privée avant toute autre action.

## Sources d'observation

Dans le Dashboard du tier exact :

- **Authentication > Audit Logs** : rechercher les actions `login`, `logout`,
  `user_recovery_requested`, `user_updated_password`, `token_refreshed`,
  `token_revoked` et `user_deleted`, sans exporter de PII ;
- **Functions > account-delete > Invocations** : utiliser uniquement la liste et ses
  métadonnées agrégées (fenêtre UTC, code HTTP, durée). Ne jamais ouvrir, copier ou
  exporter les en-têtes ou le bearer. Le body SEC-001F ne contient que la clé d'idempotence,
  qui reste une donnée restreinte et ne doit pas être exportée. Le code ne journalise pas le
  détail interne des 503 ; ne pas déduire leur étape exacte sans l'état PostgreSQL. La revue
  des en-têtes et Log Drains utilise uniquement un compte synthétique et une procédure approuvée ;
- **Integrations > Cron** ou les tables `cron.job`/`cron.job_run_details` : confirmer
  présence, activité et dernière exécution ;
- observabilité mobile seulement si le consentement est acquis. Les événements
  actuels ne fournissent pas encore une corrélation Auth opérationnelle complète.

La documentation Supabase de référence décrit les
[journaux d'audit Auth](https://supabase.com/docs/guides/auth/audit-logs), les
[journaux Edge Functions](https://supabase.com/docs/guides/functions/logging), le
[suivi des jobs Cron](https://supabase.com/docs/guides/cron) et les
[portées de déconnexion](https://supabase.com/docs/guides/auth/signout).
La rétention et les champs disponibles dépendent du plan : archiver seulement les
preuves minimales et expurgées prévues par l'incident.

## Diagnostic suppression en lecture seule

Exécuter avec un accès serveur audité. Cette requête ne retourne aucun identifiant :

```sql
begin transaction read only;

show timezone;

select
  count(*) filter (where request.status = 'prepared') as prepared_total,
  count(*) filter (
    where request.status = 'prepared' and account.id is not null
  ) as prepared_with_auth_user,
  count(*) filter (
    where request.status = 'prepared' and account.id is null
  ) as prepared_without_auth_user,
  min(request.prepared_at) filter (
    where request.status = 'prepared' and account.id is null
  ) as oldest_prepared_without_auth_user,
  count(*) filter (
    where request.status = 'completed'
      and request.completed_at < statement_timestamp() - interval '30 days'
  ) as completed_past_retention
from public.account_deletion_requests request
left join auth.users account on account.id = request.user_id;

select
  to_regclass('cron.job') as cron_jobs,
  to_regclass('cron.job_run_details') as cron_runs;

rollback;
```

Si les deux relations Cron existent, exécuter séparément :

```sql
begin transaction read only;

select jobid, jobname, schedule, active, command
from cron.job
where jobname = 'kwabor-account-deletion-reconcile';

select run.jobid, run.status, run.start_time, run.end_time, run.return_message
from cron.job_run_details run
join cron.job job on job.jobid = run.jobid
where job.jobname = 'kwabor-account-deletion-reconcile'
order by run.start_time desc
limit 10;

rollback;
```

Pour un compte précis, remplacer la valeur dans la console sécurisée. La sortie ne
répète pas l'UUID et ne doit pas être exportée avec d'autres données :

```sql
begin transaction read only;

with target as (
  select '<UUID_INCIDENT>'::uuid as user_id
)
select
  exists (
    select 1 from auth.users account where account.id = target.user_id
  ) as auth_user_exists,
  deletion.status,
  deletion.prepared_at,
  deletion.completed_at,
  exists (
    select 1
    from public.organizations organization
    where organization.primary_owner_id = target.user_id
  ) as primary_owner_conflict,
  exists (
    select 1
    from public.organization_members member
    where member.user_id = target.user_id
      and member.role = 'proprietaire'
      and member.status in ('active', 'invited', 'suspended')
  ) as owner_membership_conflict,
  exists (
    select 1
    from storage.objects object
    where object.owner_id::text = target.user_id::text
  ) as owned_storage_objects
from target
left join lateral (
  select request.status, request.prepared_at, request.completed_at
  from public.account_deletion_requests request
  where request.user_id = target.user_id
  order by request.prepared_at desc
  limit 1
) deletion on true;

rollback;
```

## Seuils suppression et cron

Le job versionné s'appelle `kwabor-account-deletion-reconcile` et porte l'expression
`23 3 * * *`, soit 03:23 UTC dans la configuration documentée. La politique initiale
est volontairement conservatrice :

- aucune exécution réussie dans les 26 dernières heures : P1 ;
- job absent ou inactif sans ordonnanceur équivalent versionné : no-go release, puis
  P1 si la suppression est déjà ouverte ;
- au moins un `prepared` sans utilisateur Auth encore présent après une exécution
  réussie : P1 immédiat ;
- un `completed` au-delà de 30 jours après approbation juridique et exécution réussie :
  P1 de purge ;
- un `prepared` dont l'utilisateur Auth existe encore n'est pas réconcilié par le cron.
  L'utilisateur reprend avec une nouvelle session Auth éphémère fraîche, même après redémarrage ou
  expiration. Un échec répété de ce parcours est P1. Ce cas ne doit jamais déclencher une
  suppression DBA.

Le délai de 26 heures correspond à un cycle quotidien complet plus deux heures de
marge opérationnelle. Aucun moniteur distant n'implémente encore ces seuils : la mise
en service reste interdite tant que l'alerte, son destinataire et un exercice staging
ne sont pas prouvés.

La rétention réelle de l'UUID vaut le temps éventuellement passé en `prepared`, puis
30 à presque 31 jours après `completed` avec un cron quotidien. Ne jamais la décrire
comme « exactement 30 jours ».

## Scénario A — email, OTP et mot de passe

1. Déterminer si l'échec touche plusieurs comptes, un seul tier ou une seule version.
2. Dans les Audit Logs, distinguer demande, vérification, login, Recovery, limite de
   débit et changement de mot de passe. Ne jamais demander le code ou le mot de passe.
3. Pour plusieurs emails non reçus, vérifier fournisseur SMTP, quotas, configuration du
   tier et template. Ne pas confirmer si une adresse possède un compte.
4. Pour `OverEmailSendRateLimit`, `OverRequestRateLimit` ou HTTP 429, conserver le
   cooldown et rechercher un volume anormal. Ne pas désactiver la limite en urgence.
5. Pour `InvalidCredentials` ou `EmailNotConfirmed`, proposer le parcours prévu. Un
   opérateur ne réinitialise jamais le mot de passe directement.
6. Pour `OtpExpired`, demander un nouveau code après le cooldown. L'OTP reste composé de
   six chiffres et le message utilisateur reste non technique.
7. Vérifier sur un compte synthétique du tier que login, compte incomplet, compte complet
   et Recovery suivent leurs routes attendues avant de clore.

Après un login email ou une vérification OTP, Supabase peut avoir créé la session avant
qu'une lecture du profil échoue. Une erreur affichée ne prouve donc pas que la session est
absente : tenter la restauration prévue ou une déconnexion locale contrôlée. Ne jamais
effacer manuellement le Keychain, le Keystore ou les marqueurs sécurisés.

La configuration locale fixe notamment OTP à six chiffres, délai de renvoi à 30 secondes
et mot de passe à huit caractères minimum. Elle ne prouve pas la configuration hébergée.

## Scénario B — Google ou Apple

1. Une annulation fournisseur sans session et sans erreur technique est normale.
2. Si un fournisseur entier échoue, comparer le tier du binaire aux clients natifs,
   audiences serveur, certificats/signatures, reversed scheme iOS, entitlement Apple et
   fournisseur activé dans le même projet Supabase.
3. Vérifier que le flux reste natif et que chaque tentative utilise un nouveau nonce.
   Ne jamais copier un ID token ou un nonce pour diagnostiquer.
4. Pour une suppression, le nouvel ID token et son nonce vont uniquement à Supabase Auth dans le
   client éphémère. L'UUID obtenu doit correspondre à la session principale ; un autre UUID est un
   refus attendu, pas une raison d'élargir l'audience.
5. Une erreur de configuration publique embarquée exige un nouveau build du bon tier ;
   ne jamais rediriger un binaire vers un autre projet.
6. Apple peut omettre le nom après la première autorisation : ce n'est pas un incident.

## Scénario C — restauration, Recovery et sessions temporaires

Les états autoritatifs sont :

- `AuthSessionPurpose.Standard` ou `PasswordRecovery` ;
- `AccountSetupStatus.OnboardingRequired` ou `Complete` ;
- Android `AuthSessionRestoreStatus.InProgress`, `Ready` ou `Failed` ;
- Recovery `PasswordUpdateRequired` ou `PasswordUpdatedPendingCleanup`.

Une session n'est authentifiée que si elle est à la fois `Standard` et `Complete`.

1. En cas d'écran de restauration échouée, utiliser uniquement **Réessayer** après avoir
   vérifié le réseau et Supabase. Ne jamais contourner vers l'accès invité ou l'accueil.
2. Une panne après vérification Recovery doit rouvrir le nouveau mot de passe. Une panne
   après mise à jour doit nettoyer session et marqueur au prochain bootstrap sans rejouer
   la modification du mot de passe.
3. Terminer ou annuler Recovery depuis l'application. Ne jamais effacer seulement
   `kwabor.auth.password_recovery` ni le contenu Keychain/Keystore.
4. Une session Promoteur temporaire doit être déconnectée avant la suppression de son
   marqueur. Si ce nettoyage échoue, conserver le blocage fail-closed et réessayer.
5. Au premier lancement iOS, y compris après une réinstallation, l'application tente
   volontairement de nettoyer une ancienne session persistée dans le Keychain avant la
   restauration. Si ce nettoyage échoue, conserver le blocage fail-closed et réessayer ;
   le support n'efface jamais le Keychain manuellement.
6. Un stockage de session illisible est purgé par le client. Une reconnexion est ensuite
   normale ; aucune extraction du stockage sécurisé par le support n'est autorisée.
7. Si la session Recovery ou Promoteur ouvre une destination privée, classer P0.
8. La session de ré-authentification de suppression utilise `MemorySessionManager`, n'est ni
   restaurée ni auto-refreshée et force `LogLevel.NONE`. Elle est nettoyée dans un contexte non
   annulable sur succès, erreur ou annulation ; elle ne doit jamais remplacer la session principale.

Le timestamp d'expiration est transporté par le domaine, mais le dépôt ne prouve pas une
politique proactive complète de refresh UI. Diagnostiquer les événements de refresh réels
et ne pas promettre un comportement multi-appareils non testé.

## Scénario D — déconnexion

La déconnexion utilisateur ordinaire utilise `SignOutScope.LOCAL` : elle ferme uniquement
la session de l'appareil courant. Les autres appareils peuvent rester connectés ; la gestion
des appareils n'est pas encore livrée.

1. Si la déconnexion échoue, conserver l'écran et la session, afficher le message sûr puis
   réessayer. Ne jamais simuler le succès par la seule navigation.
2. Après succès, vérifier la purge des destinations privées, deep links en attente, état
   viewer Like/Favori et piles de navigation sauvegardées.
3. Une suppression de compte utilise au contraire une révocation globale. Les access tokens
   déjà émis peuvent rester valides jusqu'à leur expiration ; les tombstones et contrôles
   serveur doivent néanmoins continuer à refuser les écritures produit.
4. Tout accès privé croisé après déconnexion/changement de compte est P0.

## Scénario E — suppression de compte

### Réponse de la fonction

| HTTP / code | Sens actuel | Action |
| --- | --- | --- |
| `204` | Suppression et marquage terminés, ou retry idempotent déjà terminé | Vérifier Auth absent, session locale effacée et état agrégé sain |
| `400 invalid_request` | Body non JSON, supérieur à 256 octets, clé invalide ou champ autre que `idempotency_key` | Vérifier compatibilité du binaire ; incident P1 si produit par une version publiée |
| `401 unauthorized` | Bearer/contexte signé invalide ou identités vérifiées incohérentes | Recommencer depuis l'application ; ne jamais injecter de bearer opérateur |
| `401 reauthentication_failed` | AMR/session fraîche invalide, `getUser()` absent ou session Auth non vivante au RPC atomique | Refaire la ré-authentification du même compte ; si l'échec se répète sur un compte synthétique, vérifier les claims réels du tier sans les exporter |
| `409 organization_ownership_conflict` | Propriété d'organisation active | Capacité de transfert/fermeture non livrée : escalader produit/sécurité, no-go pour ce compte et aucun SQL direct |
| `409 storage_objects_conflict` | Au moins un objet Storage appartient encore au compte | Procédure opérateur de nettoyage média non livrée : escalader, no-go pour ce compte et ne jamais neutraliser `owner_id` |
| `503 deletion_prepared_retryable` | Préparation faite, révocation/suppression Auth non terminée | Lire le tombstone et l'existence Auth, puis suivre la matrice ci-dessous |
| `503 deletion_completion_pending` | Auth supprimé mais marquage final non confirmé | Attendre/vérifier la réconciliation ; P1 au-delà du seuil |
| `503 temporarily_unavailable` | Erreur fermée non localisée | Corréler invocation, DB et cron ; ne pas inventer l'étape |

Les statuts persistés de `account_deletion_requests` sont uniquement `prepared` et
`completed`. Les conflits ne sont pas stockés et il n'existe ni statut `failed`, ni
compteur de retries, ni `last_error`.

### Matrice de reprise

| Tombstone | Utilisateur Auth | Reprise autorisée |
| --- | --- | --- |
| absent | présent | Recommencer depuis l'application avec confirmation et réauthentification |
| `prepared` | présent, écran/token encore utilisable | Réessayer depuis le même parcours ; une nouvelle clé client retrouve la clé serveur effective |
| `prepared` | présent, après redémarrage/expiration | Se reconnecter au même compte, rouvrir la Danger Zone puis fournir une nouvelle preuve éphémère ; la clé serveur effective est réutilisée |
| `prepared` | absent | Le cron privilégié rejoue le nettoyage puis marque `completed` |
| `completed` | absent | Aucun retry utilisateur ; vérifier purge locale et rétention |
| absent | absent | Le compte n'est pas reprenable par ce mécanisme ; qualifier sécurité/vie privée avant toute action |

La préparation nettoie les données applicatives principales, mais les campagnes et paiements
disparaissent actuellement avec la cascade de suppression Auth, pas pendant l'état intermédiaire
`prepared`. Aucun objet Storage n'est supprimé automatiquement. Le profil est réduit à une sentinelle
pseudonymisée pour que la reconnexion principale restaure l'accès à la Danger Zone ; les noms,
médias, bio, ville et préférences d'origine sont effacés, et la sentinelle est invisible aux autres
lecteurs et non modifiable sous tombstone. Le finalizer refuse de clôturer tant que l'utilisateur Auth
existe, puis rejoue le nettoyage complet après sa disparition. Après disparition de l'utilisateur
Auth, seule la réconciliation serveur est autorisée.

## Réconciliation contrôlée

Le chemin normal est le job versionné. `service_role` peut lire le tombstone, mais n'a
volontairement pas `EXECUTE` sur
`app_private.reconcile_account_deletion_requests()`. La fonction est réservée à son
propriétaire/au cron et traite toutes les lignes dans une transaction. Une seule erreur
peut annuler toute la passe ; elle purge aussi tous les `completed` de plus de 30 jours.

Une exécution manuelle est exceptionnelle. Elle exige :

1. approbation juridique de la rétention, car la purge fait partie du même appel ;
2. sauvegarde restaurable vérifiée et fenêtre de maintenance ;
3. diagnostic agrégé avant exécution et confirmation qu'aucun utilisateur Auth présent
   ne doit être supprimé ;
4. responsable d'incident, opérateur propriétaire de fonction et second relecteur ;
5. conservation des seuls comptes agrégés, timestamps, SHA et résultat.

Après ces cinq gates seulement, l'opérateur PostgreSQL habilité peut exécuter :

```sql
select * from app_private.reconcile_account_deletion_requests();
```

Relancer ensuite les diagnostics `READ ONLY`. Ne jamais appeler directement
`mark_account_deletion_completed`, ne jamais supprimer manuellement l'utilisateur Auth
encore présent et ne jamais masquer un échec du cron par une opération Dashboard non
versionnée. Une correction durable passe par une migration forward-only revue.

## Retour arrière et restauration

- Mobile : suspendre le rollout, conserver artefacts/hashes, corriger puis publier un
  build de version supérieure. Aucun ancien binaire n'est rappelé instantanément.
- Supabase : préférer une migration corrective forward-only. Ne jamais supprimer ou
  réécrire une migration appliquée.
- Une restauration de base peut ressusciter des données déjà supprimées. Toute
  restauration exige un inventaire des suppressions intervenues depuis la sauvegarde et
  leur rejeu contrôlé avant réouverture.
- Une suppression appliquée au mauvais UUID est P0. Ne pas recréer automatiquement le
  compte ni restaurer ses données sans décision sécurité, juridique et propriétaire.
- Si `pg_cron` est absent, un ordonnanceur privilégié équivalent, versionné et testé doit
  exister avant d'exposer la suppression. Aucun fallback de ce type n'est livré actuellement.

## Validation et exercice avant ouverture

Sur stack isolée puis staging, avec comptes synthétiques uniquement :

Les preuves locales SEC-001F sont acquises : tests Kotlin ciblés Android, compilation des tests
Kotlin/Native iOS X64, format/check Deno et 20/20 tests Edge, reset Supabase, lint
`public`/`app_private` et 753 assertions pgTAP. Elles prouvent le contrat du dépôt, pas les claims ni
la politique de journaux d'un projet hébergé.

1. exécuter les tests Edge Function et pgTAP décrits dans le
   [guide de tests](../testing.md#edge-function-account-delete) ;
2. prouver email/mot de passe, OTP, Recovery interrompue/reprise et réponse neutre pour
   une adresse inconnue ;
3. prouver sur comptes synthétiques l'AMR email/mot de passe, Google Android/iOS et Apple iOS avec
   les vrais clients du tier ; chaque JWT doit porter le même `sub`, un `session_id` UUID et une AMR
   finale `password`/`oauth` dans les bornes 300/30 secondes ;
4. couper le réseau pendant une restauration et vérifier le blocage + retry fail-closed ;
5. vérifier qu'une déconnexion ordinaire reste locale et qu'une suppression révoque les
   sessions globalement ;
6. couvrir `ownership_conflict`, `storage_conflict`, retry après redémarrage avec nouvelle session et
   nouvelle clé, puis panne après suppression Auth traitée uniquement par la réconciliation serveur ;
7. confirmer `verify_jwt=true`, le body exact, les refus sans bearer, bearer invalide, identité
   différente, AMR invalide et session absente/révoquée avant la première mutation ;
8. faire approuver et tester la politique d'accès/rétention/expurgation des en-têtes d'invocation et
   des éventuels Log Drains sans exporter de bearer ;
9. prouver présence, activité et exécution réelle du cron, puis l'alerte de 26 heures ;
10. faire approuver la durée et la mention de rétention avant toute purge production ;
11. archiver les preuves expurgées et obtenir la double revue.

La mise en service reste **no-go** tant que les AMR email/Google/Apple, la politique et les tests
d'en-têtes/logs, SMTP, le cron, les alertes, la rétention juridique et les parcours appareils
Android/iOS ne sont pas prouvés.

## Critères de clôture d'un incident

- cause identifiée et périmètre borné ;
- aucun P0/P1 ouvert ;
- smoke synthétique du scénario touché réussi sur le bon tier ;
- état Auth/session conforme et aucun accès privé croisé ;
- agrégats suppression sous seuil et dernière exécution cron réussie si concernée ;
- correctif versionné, relu et accompagné d'une stratégie de rollback ;
- preuves minimales expurgées archivées et actions de suivi inscrites au backlog ;
- revue à 24 heures, puis post-mortem sans blâme pour tout P0/P1.
