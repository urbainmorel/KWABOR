# 0030 — Livrer l’autorité Search en snapshot avant le protocole offline

- **Statut** : accepté
- **Date** : 2026-08-04
- **Décideurs** : Produit Kwabor, Architecture, Data et Sécurité
- **Complète** : ADR-0027, ADR-0028, ADR-0029
- **Remplace** : —

## Contexte et problème

L’ADR-0029 exige une autorité Supabase propriétaire pour l’historique d’un compte, une resoumission
sans doublon, un plafond de 200 entrées, l’effacement unitaire/global et une préférence de
personnalisation désactivée par défaut. Il exige aussi qu’une future synchronisation offline utilise
des mutations idempotentes, des révisions serveur, des tombstones et un watermark d’effacement
global.

HISTORY-001A doit rendre l’autorité serveur sûre avant que Room, l’outbox et la réconciliation de
session existent. Inventer maintenant un format de tombstone ou une durée de watermark figerait un
protocole dont les contraintes offline ne sont pas encore conçues. À l’inverse, présenter une liste
de lignes actives comme un flux incrémental ferait réapparaître des recherches effacées lors d’une
fusion locale naïve.

## Décision

HISTORY-001A expose quatre RPC authentifiés et versionnés : enregistrer, lister le snapshot complet,
effacer une entrée et vider l’historique. Les tables publiques restent sans privilège direct pour les
rôles clients ; les RPC `SECURITY DEFINER`, à `search_path` vide et avec contrôle explicite de
`auth.uid()`, sont l’unique frontière mobile.

- Le texte reçu par l’opération d’enregistrement est borné à 1–120 caractères après trim et refuse
  les caractères de contrôle. Aucune erreur, trace ou métrique ne reprend sa valeur.
- L’unicité porte sur le compte et le texte canonique sensible à la casse. Une resoumission conserve
  l’identifiant et `created_at`, puis actualise `last_submitted_at` avec l’horloge serveur.
- Les mutations d’un compte prennent le même verrou transactionnel que sa suppression de compte.
  L’upsert et l’éviction sont ainsi sérialisés et ne dépassent jamais 200 lignes actives, même entre
  plusieurs appareils.
- La préférence de personnalisation est initialisée à `false`, reste distincte de l’effacement des
  récents et n’a encore aucun RPC d’activation. La rétention glissante de 180 jours, son job et toute
  production de signaux restent absents.
- L’effacement unitaire et global supprime immédiatement le texte. La préparation de suppression du
  compte purge les entrées et la préférence ; la clé étrangère vers Auth avec cascade constitue une
  seconde garantie lors de la suppression finale.

## Frontière de synchronisation

`list_search_history_v1` est un **snapshot complet borné**, jamais un journal de changements. Un
client V1 qui l’utilise doit remplacer atomiquement son miroir de compte par ce snapshot ; il ne doit
pas fusionner les absences avec un cache antérieur.

HISTORY-001A ne définit volontairement ni révision, ni mutation idempotente, ni tombstone, ni
watermark. Avant toute outbox ou relecture offline dans HISTORY-001B, une nouvelle version du
protocole devra décider ensemble :

1. l’identité et la durée de vie des mutations rejouables ;
2. l’ordre total des révisions serveur ;
3. la durée et la forme des tombstones unitaires ;
4. la sémantique du watermark global face à une resoumission hors ligne antérieure ;
5. le remplacement initial d’un miroir V1 par le nouveau protocole.

Cette frontière permet l’usage en ligne et la purge correcte sans prétendre résoudre prématurément
les conflits offline.

## Conséquences

**Positives**

- L’autorité serveur, l’isolation propriétaire, le plafond et la suppression de compte sont testables
  immédiatement.
- Aucun choix implicite de tombstone ne peut provoquer une résurrection silencieuse lors du futur
  raccord Room.
- La liste de 200 lignes maximum reste simple à remplacer sur réseau lent.

**Négatives / compromis assumés**

- Ce contrat ne suffit pas encore à une synchronisation différentielle ou à une outbox offline.
- Un client futur devra migrer vers un RPC versionné distinct avant de rejouer des mutations locales.
- La préférence existe côté serveur mais son activation reste volontairement indisponible.

## Références

- [ADR-0027 — Persistance locale liée à l’appareil](0027-device-bound-local-persistence.md)
- [ADR-0028 — Recherche par mots-clés](0028-versioned-keyword-search-and-bounded-offline-fallback.md)
- [ADR-0029 — Autorité et confidentialité de l’historique](0029-search-history-authority-and-privacy.md)
- [PRD — Recherche et paramètres](../../PRD.md)
