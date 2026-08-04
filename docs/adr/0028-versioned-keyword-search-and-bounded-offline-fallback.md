# 0028 — Versionner la recherche par mots-clés et borner son repli hors ligne

- **Statut** : accepté
- **Date** : 2026-08-04
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile
- **Complète** : ADR-0018, ADR-0019, ADR-0027
- **Remplace** : —

## Contexte et problème

La barre Search doit rechercher les fiches publiées par nom, ville, catégorie et tags, dans la portée
de l’onglet Explore actif ou dans « Tout ». Cette recherche par mots-clés est distincte de
l’Assistant IA : elle doit rester déterministe, ancrée au catalogue et utilisable sur un réseau
lent ou indisponible.

Le RPC de résumé catalogue de l’ADR-0018 ne reçoit pas de texte de recherche. Lui ajouter un
paramètre modifierait l’empreinte et la sémantique de ses curseurs pour tous ses consommateurs. Le
cache Room de l’ADR-0019 contient des résumés Explore régénérables, mais pas l’intégralité des champs
indexés côté serveur, notamment les tags. Il ne peut donc pas promettre une copie complète de la
recherche distante.

L’historique des recherches a une finalité différente : afficher les récents et, après consentement,
produire des signaux bornés pour l’Assistant IA et le fil organique. Il doit conserver uniquement les
requêtes soumises, avec une autorité serveur propriétaire pour les comptes connectés. Ses règles de
rétention, de synchronisation, d’import invité et d’effacement appartiennent à une tranche dédiée et
ne doivent pas être improvisées dans SEARCH-001A. Cet ADR ne fixe donc ni durée de rétention, ni
quota, ni valeur par défaut du consentement de personnalisation.

## Options envisagées

- **Filtrer côté client les pages Explore** : n’interroge qu’un sous-ensemble déjà chargé, rend le
  nombre de résultats trompeur et ne fournit aucune pagination de recherche fiable.
- **Modifier le RPC catalogue existant** : réutilise un endpoint, mais change rétroactivement le
  contrat et les curseurs des consommateurs Explore.
- **Introduire immédiatement un index local complet** : peut assurer une meilleure parité hors
  ligne, mais exige d’enrichir le résumé, de migrer Room et de définir la synchronisation d’un corpus
  plus large que le cache Explore.
- **Créer un RPC versionné et relire le cache Explore comme repli borné** : sépare le nouveau contrat
  distant, livre un mode dégradé utile sans migration locale et rend ses limites explicites.

## Décision

Nous retenons un contrat distant versionné avec un repli local borné et explicitement partiel.

### Contrat distant

- `public.search_catalog_summaries_v1` est un RPC `security invoker`, accessible uniquement aux
  rôles mobiles `anon` et `authenticated`. Il conserve les RLS et filtre toujours les fiches
  `publie` possédant une date de publication, quel que soit le rôle appelant.
- Le texte est canonisé et validé aux deux frontières : après trim, il contient de 1 à 120
  caractères et aucun caractère de contrôle. Les identifiants de filtres, la limite et le curseur
  sont également bornés avant tout accès aux données.
- Le document indexé d’une fiche couvre son nom et ses tags. Des index séparés couvrent les noms de
  villes et les termes de catégories. Chaque mot soumis doit correspondre à au moins une de ces
  sources ; une requête peut donc combiner des mots trouvés dans plusieurs champs.
- Le serveur et le repli local replient les mêmes diacritiques, traitent la ponctuation comme un
  séparateur et exigent des tokens complets. Une sous-chaîne ne constitue donc pas un mot trouvé.
- Le RPC retourne uniquement le résumé plat nécessaire aux cartes. La couverture, les filtres, le
  placement sponsorisé et l’ordre restent des autorités serveur ; le client ne les recalcule pas.
- La pagination keyset reprend l’ordre total du catalogue et une ligne sentinelle. Le curseur opaque
  versionné contient l’instant serveur, l’empreinte canonique de la requête et des filtres, puis les
  clés de tri. Ce token autoportant n’est pas signé et ne prouve pas sa provenance : un curseur mal
  formé, daté dans le futur ou réutilisé avec une autre recherche est refusé, tandis qu’un appelant
  qui fabrique des clés valides ne peut que sauter des résultats déjà lisibles dans sa propre page.
  Le curseur n’est pas une frontière d’autorisation : RLS et le filtre publié restent appliqués à
  chaque page.
- Le domaine KMP expose des modèles Kotlin purs. La couche data seule connaît le RPC et mappe son
  DTO. Android Compose et iOS SwiftUI consomment le même runtime, ses états immuables et ses effets,
  sans partager leur UI.

### Repli hors ligne

- La recherche tente d’abord le réseau. Seule une indisponibilité réseau transitoire autorise le
  repli Room ; une erreur de validation, d’autorisation ou un payload incohérent n’est jamais masqué
  par le cache.
- Le repli relit le cache de cartes Explore et ses référentiels existants, sans nouvelle table ni
  changement de schéma. Le filtrage local s’exécute sur un dispatcher injecté, conserve uniquement
  les fiches publiées, applique la portée, déduplique les identifiants et utilise un espace de
  curseurs local distinct.
- Une lecture locale examine au maximum 3 200 candidats. Cette borne protège les appareils modestes
  et n’est pas présentée comme le nombre total de résultats du catalogue. Une capacité dépassée ou
  un stockage illisible produit une erreur locale typée.
- Le cache ne contient qu’un sous-ensemble des fiches déjà reçues par Explore. Il couvre le nom, les
  identifiants et libellés disponibles de ville et de catégorie, mais pas les tags des fiches. Une
  recherche fondée uniquement sur un tag peut donc réussir en ligne et ne rien retourner hors
  ligne. L’UI doit signaler la source hors ligne et ne jamais présenter ce résultat partiel comme
  exhaustif.

### Soumission et confidentialité

- Une frappe modifie seulement l’état éphémère du champ. Le réseau, le repli et l’événement
  `search_query` ne sont déclenchés qu’après une soumission valide.
- Le texte brut de la requête n’entre ni dans les paramètres Analytics ni dans les logs. SEARCH-001A
  ne persiste aucune requête dans Room, DataStore ou Supabase.
- Cette absence de persistance ne remet pas en cause l’historique durable demandé. Une tranche
  HISTORY distincte devra stocker seulement les requêtes soumises, avec RLS propriétaire pour un
  compte connecté, stockage local pour un invité, import explicite, effacement unitaire/global et
  contrôle de personnalisation séparé.

## Conséquences

**Positives**

- Android et iOS partagent un contrat de recherche stable sans modifier le RPC Explore existant.
- Le serveur recherche tout le catalogue publié et conserve l’autorité du classement sponsorisé.
- Le repli local reste utile sur réseau intermittent sans ajouter prématurément un second corpus
  persistant.
- Les erreurs de sécurité, de contrat et de stockage restent visibles et typées.
- Aucune frappe ni requête brute n’est ajoutée aux données d’observabilité.

**Négatives / compromis assumés**

- La recherche hors ligne est limitée aux cartes déjà mises en cache et n’a pas la parité des tags.
- Il s’agit d’une recherche lexicale par mots-clés, pas d’une recherche sémantique ou
  conversationnelle.
- La borne de candidats impose un repli en erreur si le cache local dépasse sa capacité prévue.
- Les recherches récentes, l’autocomplétion, les filtres avancés et l’Assistant IA restent hors de
  SEARCH-001A.
- Toute évolution incompatible du classement, des champs ou du curseur exige un nouveau contrat
  versionné.

**À revoir si**

- le produit exige une parité hors ligne des tags ou un corpus local plus large que le cache
  Explore ;
- les mesures de staging montrent que PostgreSQL FTS et les index actuels ne tiennent plus les
  objectifs de latence ;
- la politique HISTORY est approuvée et introduit son autorité serveur, son miroir local et ses
  règles d’effacement ;
- un classement personnalisé est demandé : il doit rester séparé du sponsorisé et soumis au
  consentement prévu.

## Références

- [PRD — Recherche et historique](../../PRD.md)
- [Design — Écran Search](../../DESIGN.md)
- [ADR-0018 — Résumé catalogue public paginé par curseur et RPC](0018-cursor-paginated-catalog-summary-rpc.md)
- [ADR-0019 — Mur Explore offline-first](0019-explore-offline-first-wall.md)
- [ADR-0027 — Persistance locale liée à l’appareil](0027-device-bound-local-persistence.md)
