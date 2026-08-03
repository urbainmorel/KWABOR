# 0023 — Contrat versionné de découverte des services de guide

- **Statut** : accepté
- **Date** : 2026-08-03
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile
- **Complète** : ADR-0018, ADR-0022

## Contexte et problème

Le DESIGN I15 impose un espace public « Trouver un guide », accessible depuis Accueil et la
recherche, avec des filtres ville, langue et spécialité. La fiche catalogue détaillée sait déjà
représenter un service de guide et ses moyens de contact, mais le résumé catalogue générique ne
porte pas les facettes nécessaires à cette découverte. Assimiler les guides à un quatrième onglet
Explore ou à une sixième destination racine contredirait en outre la navigation produit, limitée à
trois onglets Explore et cinq destinations basses.

La ville recherchée n'est pas nécessairement la ville administrative de la fiche : elle désigne
une destination effectivement couverte par le guide. Les langues et spécialités doivent également
être filtrables par des identifiants stables, sans comparaison libre de libellés ni interprétation
différente entre Android et iOS.

Le premier sous-lot doit donc établir un contrat relationnel et public strict, versionné et
paginable. Les surfaces natives, leur cache offline et leurs politiques de rendu seront livrés dans
un sous-lot ultérieur sur ce contrat.

## Options envisagées

- **Réutiliser uniquement `list_catalog_summaries` et le chip guide** : évite un nouvel endpoint,
  mais ne fournit ni facettes langue/spécialité ni carte guide complète et mélange deux parcours
  produit distincts.
- **Filtrer des tableaux texte libres dans `guide_details`** : réduit le nombre de tables, mais rend
  les identifiants, traductions, contraintes, index et déduplications fragiles.
- **Normaliser les référentiels et exposer deux RPC V1 dédiés** : sépare les données d'autorité de
  leurs libellés d'affichage, rend les filtres déterministes et conserve un contrat mobile stable.

## Décision

Nous retenons un écran public enfant d'**Accueil**, jamais une nouvelle destination racine. La
barre basse conserve exactement ses cinq items ; « Trouver un guide » est poussé dans la pile de
navigation d'Accueil. La recherche pointera vers cette même destination plutôt que de dupliquer le
parcours.

Le contrat public est exposé par deux RPC versionnés :

- `public.list_guide_facets_v1` fournit les destinations, langues et spécialités réellement
  disponibles pour la découverte publique ;
- `public.list_guide_services_v1` retourne les cartes guide filtrées et une pagination keyset.

### Modèle relationnel de découverte

La couverture géographique d'un service est une relation normalisée entre la fiche guide et les
villes de référence. Le filtre ville porte sur cette **destination couverte**, jamais sur
`listings.city_id` par approximation. Une fiche peut couvrir plusieurs destinations et une ville
peut être couverte par plusieurs guides.

Les langues et les spécialités disposent chacune d'un référentiel canonique et d'une relation
normalisée plusieurs-à-plusieurs avec les services de guide. Les clients envoient les identifiants
canoniques ; les libellés localisés restent une donnée de projection. Les tableaux texte historiques
ne constituent pas une autorité de filtrage.

Pendant la transition du `ListingWizard`, ces tableaux historiques restent uniquement une surface
d'écriture de compatibilité. Un trigger privé valide leurs valeurs, refuse toute correspondance
inconnue, ambiguë, dupliquée ou supérieure à vingt éléments, puis synchronise les relations dans la
même transaction. Les rôles `anon` et `authenticated` n'ont aucun droit d'écriture directe sur les
relations normalisées. Une contrainte différée empêche également une écriture privilégiée de valider
une transaction si son état final diverge des tableaux de compatibilité.

Chaque dimension accepte au plus une valeur dans le contrat V1 : une ville, une langue et une
spécialité. Une valeur absente signifie « toutes ». Lorsque plusieurs dimensions sont présentes,
elles sont combinées avec **AND** : par exemple, « Ouidah + portugais + histoire » ne retourne que
les services couvrant Ouidah, parlant portugais et proposant la spécialité histoire.

### Projection publique et sécurité

Les deux RPC sont `stable`, `security invoker`, avec un `search_path` vide. Ils sont exécutables par
`anon` et `authenticated`, mais ne voient que les fiches de variant guide au statut `publie`, avec
`published_at` valide et les relations publiques nécessaires. Une fiche brouillon, en attente,
rejetée, archivée ou incomplète ne contribue ni aux facettes ni aux résultats.

La projection d'une carte contient uniquement les données nécessaires au rendu dédié : identité de
la fiche, couverture et alt text public, destinations, langues, spécialités, note moyenne, nombre
d'avis, tarif indicatif XOF et curseur opaque. La date de publication reste interne au classement
et au curseur ; elle n'est pas exposée comme donnée d'affichage. La projection n'expose aucun
propriétaire, organisation, chemin Storage, rôle interne ni donnée de modération. Les RLS et grants
restent la frontière d'autorité ; `security invoker` ne les contourne pas.

Les identifiants de filtre inconnus, les limites hors contrat et les curseurs malformés ou réutilisés
avec un autre ensemble de filtres sont refusés. Le curseur est opaque et encode la clé de reprise du
tri ainsi que le contexte nécessaire pour empêcher son changement silencieux de requête.

### Classement et pagination

Le classement organique est déterministe, dans cet ordre :

1. `rating_avg` décroissant ;
2. `rating_count` décroissant ;
3. `published_at` décroissant ;
4. `id` comme départage total stable.

La pagination est keyset sur ce tuple complet ; elle n'utilise ni offset ni horodatage technique de
création. Les valeurs sans avis conservent une position déterministe définie par le contrat au lieu
d'être réinterprétées par chaque client.

La surface « Trouver un guide » ne comporte aucun placement sponsorisé. En V1, les placements
payants restent limités aux emplacements Explore prévus et aux notifications sponsorisées. Aucun
score publicitaire, badge jaune ou réordonnancement payant n'entre donc dans ce RPC ou son curseur.

### Consommation mobile

Android Compose Multiplatform et iOS SwiftUI utiliseront une carte guide dédiée, car une carte
catalogue générique ne porte pas correctement langues, zones, note et tarif indicatif. Le tap ouvre
le `CatalogDetail` guide existant et réutilise son contact direct ainsi que les politiques d'URL
externes déjà validées ; les clients ne reconstruisent pas une seconde fiche guide.

La première version de l'interface est en français, tout en conservant des identifiants de
référentiel indépendants de la langue afin de permettre l'i18n ultérieure sans changer le contrat.
La liste d'avis et les réponses ne sont pas ajoutées par cette décision : elles dépendent de
`REVIEWS-001` et seront intégrées au détail guide commun.

## Conséquences

**Positives**

- La navigation reste conforme aux cinq destinations racines et au rôle central d'Accueil.
- Ville, langue et spécialité ont une sémantique identique et testable sur Android, iOS et SQL.
- Les facettes ne proposent pas de valeurs sans résultat public et ne révèlent pas de fiches non
  publiées.
- Le tri keyset est stable face aux égalités et ne dérive pas vers un classement sponsorisé implicite.
- Le détail et le contact guide existants restent la source unique après sélection d'une carte.

**Négatives / compromis assumés**

- Trois relations normalisées et leurs référentiels ajoutent des écritures et des index à maintenir.
- Le contrat V1 n'accepte qu'une valeur par dimension ; une sélection multiple exigera une nouvelle
  version ou une évolution explicitement compatible.
- Le classement dépend de compteurs d'avis fiables alors que la consultation et la gestion complète
  des avis restent dans `REVIEWS-001`.

## Hors de cette décision

- L'implémentation de l'écran Compose et de l'écran SwiftUI.
- Le cache local, le comportement offline, les skeletons, les empty states, l'accessibilité et la
  télémétrie de cette surface ; ils appartiennent aux sous-lots UI/offline suivants de GUIDE-001.
- La création ou l'édition du service par le guide, traitée par le ListingWizard et l'espace guide.
- Les avis, réponses Promoteur/Guide, likes et signalements, traités notamment par `REVIEWS-001`.
- Le sponsoring d'une carte guide dans cette surface.

## À revoir si

- le produit autorise plusieurs villes, langues ou spécialités sélectionnées dans une même
  dimension ;
- un placement sponsorisé est explicitement ajouté à « Trouver un guide » ;
- une nouvelle source d'autorité remplace les référentiels normalisés ;
- une V2 du classement ou du payload devient incompatible avec les deux RPC V1.
