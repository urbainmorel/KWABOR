# 0025 — Prouver la suppression de compte par une session éphémère fraîche

- **Statut** : accepté
- **Date** : 2026-08-03
- **Décideurs** : Architecture, Mobile, Sécurité
- **Complète et remplace partiellement** : ADR-0017 pour la ré-authentification de suppression

## Contexte et problème

La première implémentation d'`account-delete` transmettait le mot de passe, ou l'ID token social et
son nonce, dans le corps JSON de l'Edge Function. La documentation d'observabilité Supabase décrit
le corps des invocations comme consultable, sans contrat documenté garantissant l'expurgation de
ces champs. Même sans preuve d'une exposition réelle dans un projet Kwabor, ce transport est
incompatible avec le principe de l'ADR-0017 qui interdit les secrets d'authentification dans les
logs et bloque donc staging et production.

Ré-authentifier avec le client Supabase principal n'est pas une alternative sûre : `signInWith`
importe immédiatement la nouvelle session. Une identité différente, une erreur ou un arrêt du
processus entre cette mutation et la comparaison d'identité pourrait remplacer la session durable
du compte courant.

La seule signature d'un JWT ne suffit pas non plus pour une suppression irréversible. Un access
token peut rester cryptographiquement valide après une déconnexion ; Supabase recommande, pour les
actions qui exigent cette garantie, de corréler son claim `session_id` avec `auth.sessions`.

## Options envisagées

- **Conserver les credentials dans le body et compter sur une expurgation d'observabilité** : peu de
  changements, mais aucune garantie Supabase documentée ne ferme le risque.
- **Ré-authentifier avec le client mobile principal** : évite un second client, mais peut remplacer
  la session persistée avant que l'identité soit validée et rend les reprises non déterministes.
- **Créer une session Supabase éphémère et utiliser son JWT frais comme preuve** : davantage de
  contrats et de nettoyage, mais aucun credential primaire ne traverse l'Edge Function et la preuve
  reste vérifiable par les autorités Auth et Database.

## Décision

Nous retenons une **session Supabase Auth éphémère, isolée et non persistée**, parce qu'elle permet à
Supabase Auth de vérifier le secret primaire tout en donnant à l'Edge Function une preuve signée,
récente, liée à une session vivante et au même utilisateur.

Le data layer partagé crée un client dédié à chaque tentative. Ce client installe uniquement Auth
et Functions, désactive chargement, sauvegarde, auto-refresh et callbacks de cycle de vie, utilise
un `MemorySessionManager`, exige une session Auth valide pour Functions et désactive entièrement les
logs SDK. Il n'utilise jamais le gestionnaire sécurisé du client principal.

Pour un compte email, l'adresse provient de la session principale déjà restaurée et le mot de passe
est envoyé uniquement à Supabase Auth. Pour Google ou Apple, le nouvel ID token et son nonce sont
envoyés uniquement à Supabase Auth. Après connexion, l'identifiant de la session éphémère doit être
strictement égal à celui de la session principale ; une session principale absente, une identité
différente ou une session éphémère absente échoue en mode fermé.

Le même client éphémère invoque ensuite `account-delete`. Son body JSON possède exactement un champ,
`idempotency_key`; mot de passe, ID token, nonce, email et fournisseur y sont interdits. Le JWT de la
session éphémère est porté uniquement par l'en-tête d'autorisation standard de Functions.

L'Edge Function conserve `verify_jwt=true` et `withSupabase({ auth: "user" })`. Le handler vérifie
d'abord cumulativement :

1. `userClaims.id`, `jwtClaims.sub` et l'utilisateur retourné par `getUser()` désignent le même UUID ;
2. `session_id` est un UUID ;
3. `amr` est un tableau non vide dont chaque entrée possède une méthode chaîne et un timestamp entier ;
4. l'entrée AMR la plus récente, avec la dernière position comme départage, vaut `password` ou
   `oauth`, date d'au plus cinq minutes et ne dépasse pas l'horloge serveur de plus de trente secondes.

Une AMR absente, malformée, issue d'OTP, magic link, recovery ou token refresh, ancienne ou trop
future est refusée. Ces claims proviennent exclusivement du JWT vérifié par `@supabase/server` ; ils
ne sont ni reconstruits depuis le body ni décodés depuis un token fournisseur.

La première mutation passe obligatoirement par
`public.prepare_account_deletion_with_session(uuid, uuid, uuid)`, fonction `SECURITY DEFINER` avec
`search_path=''`. Dans la même transaction, elle sélectionne `auth.sessions` par `id` et `user_id`,
refuse un éventuel `not_after` atteint, verrouille la ligne `FOR KEY SHARE`, puis seulement délègue
à la préparation idempotente existante. Le verrou est conservé jusqu'au commit : une déconnexion ne
peut donc pas supprimer la session entre le contrôle et la première mutation. Un contrôle booléen
séparé est interdit car il réintroduirait cette course.

L'exécution de ce RPC est révoquée à `public`, `anon` et `authenticated`, puis accordée explicitement
à `service_role`. Il ne crée ni ne modifie aucun objet dans le schéma Auth. Une session absente,
destinée à un autre utilisateur ou dépassant `not_after` lève une erreur d'autorisation sans
préparer la suppression.

Après cette préparation autorisée et atomique, l'orchestration idempotente existante reste
applicable : révocation globale, nouvelle préparation privilégiée, suppression Auth puis marquage
`completed`. La session temporaire et ses ressources sont nettoyées dans un contexte non annulable, sur succès,
erreur métier, erreur réseau ou annulation. Un succès serveur efface aussi le marqueur Recovery et
la session principale, même si un nettoyage secondaire échoue.

La préparation anonymise les invitations, supprime les données applicatives rejouables et neutralise
les attributions de fiches. Elle remplace le profil par une sentinelle pseudonymisée : seul l'UUID et
le fait que l'onboarding était complet restent utiles au routage, tandis que nom, médias, bio, ville,
préférences et horodatages antérieurs sont effacés. La policy RLS masque cette sentinelle aux autres
lecteurs et le tombstone fait échouer les gardes d'écriture produit, y compris la mise à jour du
profil et Storage. Le finalizer refuse `completed` tant que `auth.users` contient encore
l'utilisateur ; après suppression Auth, il rejoue le nettoyage complet puis clôt le tombstone.

Un tombstone `prepared` avec utilisateur Auth encore présent est donc repris après reconnexion du
même compte principal, puis nouvelle connexion éphémère depuis la Danger Zone. Si l'utilisateur Auth
a été supprimé avant le marquage final, aucune preuve de session ne peut légitimement être recréée :
seule la réconciliation serveur idempotente marque alors l'opération terminée. Une suppression DBA
manuelle d'un utilisateur encore présent reste interdite.

## Références

- [Claims JWT Supabase Auth](https://supabase.com/docs/guides/auth/jwt-fields)
- [Sessions Supabase Auth](https://supabase.com/docs/guides/auth/sessions)
- [Authentification des Edge Functions](https://supabase.com/docs/guides/functions/auth)
- [Fonctions PostgreSQL et `SECURITY DEFINER`](https://supabase.com/docs/guides/database/functions)

## Conséquences

**Positives**

- Aucun mot de passe, ID token ou nonce n'entre dans le body ou le code de l'Edge Function.
- Une tentative ne peut pas remplacer ou persister silencieusement la session mobile principale.
- Un JWT révoqué après déconnexion ne peut pas lancer une suppression malgré une signature valide.
- Les retries après préparation conservent une route mobile déterministe : reconnexion au même compte,
  sentinelle pseudonymisée privée en lecture seule, puis nouvelle preuve éphémère depuis la Danger Zone.
- Les règles AMR sont identiques à celles déjà utilisées pour l'activation Promoteur.

**Négatives / compromis assumés**

- Chaque tentative ouvre une seconde session Auth très courte et doit la nettoyer explicitement.
- Le chemin email dépend de l'adresse encore portée par la session principale restaurée.
- Une sentinelle de profil pseudonymisée subsiste en lecture propriétaire jusqu'à la suppression Auth ;
  sa confidentialité publique et l'interdiction de toute mutation reposent sur les gardes RLS testés
  avec le tombstone.
- Un crash après suppression Auth et avant `completed` reste traité par le réconciliateur serveur.
- Les valeurs AMR réellement émises pour email, Google et Apple doivent être prouvées sur staging ;
  aucune méthode inattendue ne sera ajoutée à l'allowlist pour faire passer un test.
- La rétention et l'expurgation réelles des en-têtes d'invocation Supabase restent une gate staging
  distincte avant activation de la fonction sur un environnement partagé ou de production.

**À revoir si**

- Supabase modifie le contrat de `session_id`, `amr`, `auth.sessions` ou `@supabase/server` ;
- un fournisseur d'identité supplémentaire est ajouté ;
- la suppression devient différée ou nécessite une conservation légale différente ;
- Supabase fournit une primitive de step-up serveur qui garantit le même niveau sans transporter le
  secret primaire vers l'Edge Function.
