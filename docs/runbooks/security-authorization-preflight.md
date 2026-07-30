# Préflight SEC-001 — autorisations et données historiques

## But

Ce runbook définit deux gates distinctes pour une base Supabase persistante.
La sauvegarde et l’export en lecture seule précèdent le hotfix d’autorisation
`20260730140225`, qui doit être appliqué sans attendre une décision métier sur
les anciennes lignes. Leur revue et la remise à zéro de toute dérive taxonomique
précèdent ensuite `20260730140300`. Le hotfix ferme les écritures non autorisées
futures, mais ne peut pas distinguer automatiquement une ancienne décision
opérateur légitime d’une valeur forgée par un client avant le correctif.

Les environnements distants Kwabor ne sont pas encore provisionnés. Cette
préflight n’a donc été exécutée que sur la base locale de test, dont les fixtures
sont recréées et couvertes par pgTAP.

## Responsables et preuves

- Exécutant : responsable Supabase disposant d’un accès serveur audité.
- Relecteur : une seconde personne habilitée, distincte de l’exécutant.
- Approbateur métier : propriétaire produit pour les membres d’organisation et
  modérateur désigné pour Social, réclamations et signalements.
- Preuves à conserver : sauvegarde horodatée, export chiffré des lignes
  examinées, décision par ligne, SHA des migrations, sortie de la CI et résultat
  du smoke test. Ne jamais déposer de PII dans Git ou dans les logs CI.

## 1. Préconditions

1. Geler temporairement les opérations d’administration concernées.
2. Créer et vérifier une sauvegarde restaurable.
3. Confirmer le SHA déployé et l’ordre des deux migrations indépendantes.
4. Exécuter et exporter les requêtes suivantes avec une connexion serveur en transaction
   `READ ONLY`.
5. Appliquer immédiatement
   `20260730140225_security_authorization_guardrails.sql` après la sauvegarde et
   l’export. Une ligne à examiner ou une dérive taxonomique ne bloque pas ce
   hotfix.

## 2. Audit en lecture seule

```sql
begin transaction read only;

-- Tous les membres historiques : identité, rôle, cycle de vie et timestamps
-- étaient exposés par des droits plus larges.
select
  member.id,
  member.organization_id,
  member.user_id,
  member.role,
  member.status,
  member.invited_by,
  member.accepted_at,
  member.suspended_at,
  member.created_at,
  member.updated_at,
  organization.primary_owner_id
from public.organization_members member
join public.organizations organization
  on organization.id = member.organization_id
order by member.organization_id, member.created_at, member.id;

-- Tous les posts historiques : statut, watermark, compteur et timestamps
-- étaient contrôlables par le client, même quand leurs valeurs semblent sûres.
select
  post.id,
  post.author_id,
  post.listing_id,
  post.moderation_status,
  post.watermark_applied,
  post.likes_count,
  post.created_at,
  post.updated_at
from public.social_posts post
order by post.created_at, post.id;

-- Toutes les réclamations historiques, y compris celles dont les champs
-- d’autorité semblent conserver leurs valeurs par défaut.
select
  claim.id,
  claim.listing_id,
  claim.claimant_id,
  claim.status,
  claim.decision_reason,
  claim.created_at,
  claim.updated_at
from public.claims claim
order by claim.created_at, claim.id;

-- Tous les signalements historiques, y compris ceux dont les champs d’autorité
-- semblent conserver leurs valeurs par défaut.
select
  report.id,
  report.reporter_id,
  report.status,
  report.assigned_admin_id,
  report.created_at,
  report.updated_at
from public.missing_place_reports report
order by report.created_at, report.id;

-- Toute ligne retournée bloquera la migration taxonomique fail-closed.
select
  listing.id,
  listing.category_id,
  listing.type as listing_type,
  listing.subtype as listing_subtype,
  listing.listing_class,
  category.listing_type as category_type,
  category.subtype as category_subtype,
  category.default_listing_class as category_class
from public.listings listing
left join public.categories category
  on category.id = listing.category_id
where category.id is null
   or row(
     listing.type,
     listing.subtype,
     listing.listing_class
   ) is distinct from row(
     category.listing_type,
     category.subtype,
     category.default_listing_class
   )
order by listing.id;

rollback;
```

## 3. Décision et remédiation

Pour chaque ligne remontée, enregistrer une décision `légitime`, `à corriger` ou
`à mettre en quarantaine` avec sa preuve. L’absence de preuve n’est jamais
interprétée comme une validation.

Une remédiation modifiant les données doit être préparée dans un script séparé,
revue par les deux responsables, testée sur une restauration de sauvegarde et
approuvée par le propriétaire métier. La stratégie conservatrice est de retirer
les droits actifs ou la visibilité publique jusqu’à validation ; aucune
suppression définitive ne fait partie de cette préflight.

## 4. Gate de la migration taxonomique et sortie

1. Confirmer que le hotfix `20260730140225` est appliqué et que ses smoke tests
   sont verts, indépendamment de l’état de la taxonomie.
2. Examiner les anciennes lignes d’autorité et exécuter uniquement une
   remédiation approuvée.
3. Vérifier que la requête de dérive taxonomique retourne zéro ligne.
4. Appliquer `20260730140300_listing_taxonomy_guardrails.sql`.
5. Exécuter `supabase db lint` et `supabase test db` contre l’état migré.
6. Vérifier les ACL `anon`/`authenticated`, un onboarding Google ou Apple, une
   modération Social admin et une suspension de membre sur staging.
7. Lever le gel uniquement après approbation du relecteur et archivage des
   preuves.

La gate du hotfix est rouge uniquement si la sauvegarde/export échoue, si
`20260730140225` échoue ou si ses smoke tests échouent. La gate de
`20260730140300` reste rouge si une ligne d’autorité n’a pas de décision, si la
taxonomie dérive, si la migration ou un test échoue, ou si la sauvegarde n’a pas
été restaurée avec succès au moins une fois.
