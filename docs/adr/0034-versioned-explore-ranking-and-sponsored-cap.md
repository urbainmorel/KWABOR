# 0034 — Classement Explore v2 et plafond sponsorisé serveur

- **Statut** : accepté
- **Date** : 2026-08-09
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile, Sécurité
- **Complète** : ADR-0018, ADR-0019, ADR-0020, ADR-0028

## Contexte et problème

Le RPC `list_catalog_summaries` V1 fournit déjà une projection publique paginée et un instant
serveur stable pour le sponsoring. Son ordre reste toutefois une infrastructure générique fondée
sur la durée du sponsoring, la note et les likes. Il ne matérialise pas les règles produit propres
aux trois surfaces Explore : popularité des lieux, proximité temporelle des événements, filtres de
prix et de dates, première rangée sponsorisée et plafond de deux cartes sponsorisées parmi six.

Recalculer ces règles dans Android et iOS créerait deux autorités divergentes et rendrait le cache
offline incohérent. Modifier le RPC V1 casserait en revanche les curseurs et les applications déjà
publiées. Le contrat suivant doit donc être versionné, déterministe, compatible avec les RLS et
consommable par les deux clients sans changement SQL supplémentaire.

Le schéma expose déjà `views_count`, `likes_count`, `sponsored_until` et les dates typées de
`event_details`. Il ne possède pas encore de ledger d'impressions, de pacing publicitaire ou de
liaison achevée entre paiement FedaPay et placement Explore.

## Options envisagées

- **Modifier le RPC V1** : réduit le nombre de fonctions, mais invalide ses curseurs et change le
  classement des versions Store existantes.
- **Classer et plafonner dans chaque client** : accélère l'interface, mais duplique l'autorité,
  casse l'ordre du cache et permet des badges sponsorisés divergents.
- **Ajouter un RPC V2 serveur** : conserve V1, centralise le tri, les filtres, le snapshot et le
  plafond, au prix d'un contrat SQL plus explicite.
- **Répéter deux sponsors dans chaque bloc de six** : augmente l'inventaire, mais exige deux flux
  keyset coordonnés, un traitement complexe de leur épuisement et une politique de rotation absente
  du produit.

## Décision

Nous ajoutons `public.list_catalog_summaries_v2` et conservons intégralement
`public.list_catalog_summaries`. Le RPC V2 est `stable`, `security invoker`, possède un
`search_path` vide et n'est directement exécutable que par `anon` et `authenticated`.

### Entrées canoniques

`p_listing_type` est obligatoire : mélanger les trois types rendrait le tri par défaut ambigu. Les
autres paramètres sont la ville, la catégorie, la classe, le tri, les bornes de prix XOF, une
fenêtre événementielle, le curseur et la limite.

Les tris acceptés sont :

- `default`, résolu en `popularity` pour `lieu` et `etablissement`, et en
  `temporal_proximity` pour `evenement` ;
- `popularity`, disponible pour les trois types ;
- `temporal_proximity`, réservé aux événements.

Un tri nul, vide ou inconnu est refusé. Le tri résolu, pas l'alias `default`, entre dans l'empreinte
du curseur.

Les bornes `p_price_min_xof` et `p_price_max_xof` sont inclusives, indépendamment optionnelles,
non négatives ou nulles, et ordonnées si elles sont toutes deux présentes. Elles sont réservées aux
établissements. Dès qu'une borne est active, une fiche sans `price_from_xof` est exclue : une valeur
inconnue ne signifie pas « gratuit ».

Les bornes `p_event_window_start` et `p_event_window_end` sont soit toutes deux nulles, soit toutes
deux présentes, dans la plage temporelle mobile et strictement ordonnées. Elles sont réservées aux
événements et représentent un intervalle UTC demi-ouvert `[début, fin)`. Le raccord mobile traduira
les presets civils du Bénin en instants `Africa/Porto-Novo` explicites avant l'appel. Cette traduction
appartient au drawer avancé EXPLORE-002B2B2 et n'est pas activée par le raccord de base B2B1.

### Snapshot public

La première page fixe `snapshot_at` à l'instant serveur. Les pages suivantes récupèrent cet instant
depuis le curseur. Seules les fiches `publie` dont `published_at` est non nul et inférieur ou égal au
snapshot sont candidates. Les états événementiels et l'éligibilité sponsorisée utilisent ce même
instant.

Le snapshot stabilise les décisions temporelles. Les compteurs de vues et de likes, les dates
d'événement et l'autorité sponsorisée peuvent encore être modifiés entre deux pages. Comme dans
l'ADR-0018, cette cohérence éventuelle est acceptée ; un refresh autoritatif répare le mur. Une
garantie historique stricte demanderait un rang matérialisé versionné.

### Popularité V2

La popularité initiale est :

```text
views_count + 5 × likes_count
```

Le calcul utilise `bigint` avant multiplication. Un like est ainsi un engagement plus fort qu'une
vue, sans rendre les vues négligeables. Les égalités sont départagées par likes décroissants, vues
décroissantes, publication décroissante puis UUID décroissant. Le coefficient `5` appartient au
contrat V2 ; le modifier exige une nouvelle version après mesure et contrôle anti-fraude.

### Temporalité des événements

`effective_end` vaut `coalesce(end_at, start_at)`. Les états sont :

- `ongoing` si `start_at <= snapshot_at` et `snapshot_at < effective_end` ;
- `upcoming` si `snapshot_at < start_at` ;
- `ended` si `snapshot_at >= effective_end`.

Un événement sans fin, ou avec une fin égale au début, est ponctuel : il est à venir avant son début
et terminé dès son début. Le tri `temporal_proximity` place les événements en cours, puis à venir,
puis terminés. Dans chaque phase, la distance temporelle la plus faible vient d'abord, suivie de la
popularité et des départages organiques. La distance mesure le temps restant jusqu'à la fin pour un
événement en cours, le temps avant le début pour un événement à venir et le temps écoulé depuis la
fin pour un événement terminé.

Une durée intersecte la fenêtre demandée si son début précède la fin de fenêtre et si sa fin suit le
début de fenêtre. Un événement ponctuel est inclus uniquement si son début appartient à la fenêtre
demi-ouverte. Les égalités aux bornes ne sont donc ni dupliquées ni interprétées différemment par
plateforme.

### Placement sponsorisé

Une carte peut être un placement sponsorisé V2 seulement si elle est une fiche publiée
`etablissement`, de classe `commercial`, et si `sponsored_until > snapshot_at`. Les lieux, les
événements, les mises en avant éditoriales, les valeurs expirées et les valeurs nulles restent
organiques.

Après application de tous les filtres, au maximum deux candidats éligibles sont choisis selon le
même ordre organique de popularité. Ils occupent les deux premiers slots du résultat complet. Les
autres candidats actifs restent visibles une seule fois dans l'ordre organique avec
`is_sponsored_placement = false`.

Cette interprétation matérialise la « première rangée sponsorisée » et garantit au plus deux badges
dans toute fenêtre de six, y compris à une frontière de page. Elle n'invente ni répétition, ni bid,
ni priorité liée à une date d'expiration. Une rotation payante demanderait un ledger d'impressions,
du pacing et un nouveau contrat.

`sponsored_until` est une projection serveur déjà interdite aux mutations client. Ce lot ne joint
pas `campaigns` ou `payments` : leurs RLS sont privées et leur modèle ne distingue pas encore tous
les canaux publicitaires. Avant une activation en production, le pipeline Promotion/FedaPay devra
être l'unique autorité capable d'alimenter cette projection.

### Projection et curseur

La projection V2 reprend la carte V1 et ajoute l'alt de couverture, le compteur de vues, les dates
d'événement, l'état terminé et `snapshot_at`. Elle n'expose aucun propriétaire, organisation,
chemin Storage, paiement, campagne ou champ de modération.

Le curseur opaque V2 contient sa version, le nom du contrat, le snapshot, l'empreinte complète des
filtres et de la limite, puis toutes les clés du tri : slot sponsorisé, phase, distance, popularité,
likes, vues, publication et UUID. Toutes les clés sont ordonnées dans le même sens et la reprise est
un vrai keyset, jamais un offset. Une ligne sentinelle `limit + 1` signale la page suivante.

Les instants de fenêtre entrent dans l'empreinte sous forme de microsecondes Unix afin qu'un même
instant reste identique quel que soit le fuseau d'affichage de la session PostgreSQL.

Les curseurs V1, malformés, futurs, hors plage, forgés avec des clés incohérentes ou réutilisés avec
un autre filtre, tri, intervalle, prix ou limite sont refusés.

## Raccord mobile EXPLORE-002B2B1

Android et iOS consomment le RPC V2 à travers un port domaine `ExploreCatalogRepository` et un
gateway data dédiés. `CatalogRepository`, le RPC catalogue V1 et Search restent inchangés pour ne
pas coupler leurs contrats ou leurs curseurs au classement Explore.

Le type de fiche est toujours explicite. Le mur courant résout le tri par onglet : popularité pour
les lieux et établissements, proximité temporelle pour les événements. Il transmet les filtres UI
déjà livrés de ville et catégorie. Le contrat KMP conserve un `listingClass` optionnel et typé, mais
aucune surface actuelle ne le renseigne. Les prix et fenêtres événement restent eux aussi nuls tant
que le drawer avancé n'existe pas. Le gateway exige la projection V2 exacte et valide toutes les
lignes, y compris la sentinelle `limit + 1`, avant de tronquer la page. Il rejette notamment les
champs obligatoires absents, les identifiants non canoniques, les doublons, les instants hors contrat
et un snapshot hétérogène.

La première page conserve `snapshot_at` en microsecondes. Un append est accepté seulement si sa
page non vide porte exactement le même snapshot. La validation porte sur le mur cumulé : au plus
deux placements sponsorisés, tous en préfixe avant la première fiche organique. Les clients ne
recalculent ni l'éligibilité, ni le rang, ni le badge à partir de l'horloge appareil.

Room passe en version 3 avec migrations automatiques `1 -> 2 -> 3`. Le cache v2 persiste le snapshot
serveur, l'alt de couverture, les vues, les dates et l'état événement ainsi que le placement
sponsorisé. Les nouvelles colonnes restent nullables afin de conserver les snapshots v1 historiques,
mais une nouvelle écriture v2 exige toutes les métadonnées et invariants du contrat. La lecture ne
retombe sur la clé v1 que si aucun snapshot v2 n'existe ; un échec physique v2 reste une erreur et un
snapshot legacy offline n'autorise pas la pagination.

Les cartes Compose Android et SwiftUI iOS affichent les mêmes projections : « Sponsorisé » remplace
la note, la date événement est visible et « Terminé » reste un état distinct. Le texte alternatif de
couverture participe au résumé accessible sans dupliquer les éléments visuels. Ces vues font
confiance aux champs serveur/cache validés ; elles ne reconstruisent pas la décision sponsorisée.

EXPLORE-002B2B1 ne livre ni drawer avancé, ni multi-ville, ni compteur live, ni recherche filtrée.
Le RPC actuel reçoit une seule ville. Produit doit arbitrer dans EXPLORE-002B2B2 l'éventuelle
extension multi-ville, les presets de dates, l'autorité et le coût du compteur ainsi que le partage
des filtres avec Search avant toute nouvelle surface ou version de contrat.

## Conséquences

**Positives**

- Les deux clients consomment exactement le même classement et le même badge.
- Les versions Store utilisant V1 continuent de fonctionner sans changement.
- Les événements, prix et sponsors sont filtrés avant classement et pagination.
- Le plafond sponsorisé ne redémarre pas à chaque page et aucune fiche n'est dupliquée.
- Le score `bigint`, le snapshot serveur et l'UUID final rendent le contrat explicite et testable.

**Négatives / compromis assumés**

- La formule et le choix des deux sponsors exigent un tri serveur global des candidats filtrés.
- Les métriques mutables restent seulement cohérentes à terme entre pages.
- Deux placements totaux sous-utilisent volontairement l'inventaire tant que le pacing n'existe pas.
- La conversion des presets de dates civils reste dans EXPLORE-002B2B2 après arbitrage Produit.
- `views_count` ne devient utile qu'après livraison de son pipeline serveur anti-abus.

**À revoir si**

- les mesures `EXPLAIN (ANALYZE, BUFFERS)` sur un corpus staging représentatif justifient un index ;
- le produit approuve une rotation, une enchère ou plus de deux placements dans un mur long ;
- FedaPay et les campagnes deviennent l'autorité complète du placement ;
- les métriques doivent être figées strictement entre pages ;
- un nouveau coefficient ou un signal de popularité anti-fraude est approuvé.
