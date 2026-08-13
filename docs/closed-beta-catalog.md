# Catalogue de la bêta fermée

> Contrat éditorial et technique du corpus de démonstration exigé par ADR-0036.

## En un coup d'œil

| Élément | Contrat |
| --- | --- |
| Environnement | Staging uniquement |
| Fiches publiées | Exactement 60 |
| Répartition | 15 lieux, 15 événements, 15 hôtels, 15 restaurants |
| Villes | Cotonou, Ouidah, Porto-Novo ; cinq fiches de chaque famille par ville |
| Médias | Trois JPEG officiels par fiche, soit 180 |
| Statut actuel | Manifeste, 180 médias, seed et workflows prêts pour CI/staging |
| Source de vérité | `demo/catalog/v1/manifest.json` |

Le seed canonique de quatre fiches reste la fixture des tests historiques. Le catalogue bêta est un
lot distinct et explicitement activé ; un reset ou un déploiement standard ne doit pas le charger.

## Principes éditoriaux

- Les 60 fiches portent les tags `demo-kwabor` et `contenu-fictif`.
- Leur description se termine par `Contenu fictif créé pour la bêta fermée Kwabor.`
- Les hôtels et restaurants utilisent des noms fictifs suffixés `Démo`. Les événements sont
  identifiés par la disclosure persistante, les tags démo et leur description contractuelle.
- Aucun propriétaire, organisation, marque, campagne, sponsor, avis ou badge vérifié n'est simulé.
- Les notes sont absentes, `rating_count = 0` et aucune table d'avis n'est alimentée.
- Les identités techniques requises par les contraintes utilisent uniquement le domaine réservé
  `.test`. L'application masque leurs actions de contact.
- Les lieux patrimoniaux réels peuvent conserver un nom factuel, mais leur représentation visuelle
  est signalée comme générée et ne vaut pas photographie documentaire.
- Une bannière persistante sur les surfaces de la cohorte indique `Données fictives — bêta fermée`.

## Matrice du corpus

| Ville | Lieux | Événements | Hôtels | Restaurants | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Cotonou | 5 | 5 | 5 | 5 | 20 |
| Ouidah | 5 | 5 | 5 | 5 | 20 |
| Porto-Novo | 5 | 5 | 5 | 5 | 20 |
| **Total** | **15** | **15** | **15** | **15** | **60** |

Les UUID historiques suivants sont conservés :

| UUID démo isolé | Fiche |
| --- | --- |
| `00000000-0000-4000-8000-000000000214` | Porte du Non-Retour, lieu à Ouidah |
| `00000000-0000-4000-8000-000000000215` | Marché Dantokpa, lieu à Cotonou |
| `00000000-0000-4000-8000-000000000515` | Table Locale Cotonou Démo, restaurant à Cotonou |
| `00000000-0000-4000-8000-000000000315` | Festival culturel de Ouidah Démo, événement à Ouidah |

Les quatre UUID canoniques `...0101` à `...0104` restent réservés au seed de régression et sont
interdits dans le catalogue démo. Les 56 autres UUID appartiennent aux espaces déterministes `0201`
à `0215` pour les lieux, `0301` à `0315` pour les événements, `0401` à `0415` pour les hôtels et
`0501` à `0515` pour les restaurants. Le validateur doit refuser tout UUID, slug ou ordre média
dupliqué.

## Complétude des fiches

### Commun

Chaque fiche contient un nom, un slug, une description en français, une ville, une adresse de
démonstration, des coordonnées dans le Bénin, trois à cinq tags et trois médias. Elle est assemblée
en brouillon, validée, puis publiée en dernière étape de la transaction.

### Lieux

- cinq historiques, cinq nature et cinq marchés ;
- `place_details` cohérent avec le sous-type ;
- accès gratuit sans prix, ou prix `par_entree` strictement égal au droit d'entrée.

### Événements

- sous-type `culture` de la catégorie `event-culture` ;
- lieu publié référencé ou adresse et coordonnées complètes ;
- organisateur et contact réservés de démonstration ;
- événement gratuit sans prix ni tarif, ou événement payant avec tarifs positifs et prix minimal
  égal au prix de départ ;
- dates revues au plus tard 48 heures avant l'ouverture de la cohorte.

### Hôtels

- détail `lodging`, classement de une à cinq étoiles et nombre de chambres ;
- prix `par_nuit` ; s'il existe des types de chambres, le prix de départ égale le moins cher ;
- horaires sur sept jours, au moins un équipement et un contact réservé masqué dans l'UI.

### Restaurants

- détail `food`, au moins une cuisine et un service ;
- prix `par_personne` ;
- horaires sur sept jours, au moins un équipement et un contact réservé masqué dans l'UI.

## Contrat média

Chaque fiche possède `00-cover`, `01-gallery` et `02-gallery` dans cet ordre. Une seule image porte
`is_cover = true`.

| Propriété | Exigence |
| --- | --- |
| Format servi | JPEG progressif |
| Dimensions | 960 × 1280, ratio 3:4 |
| Couleur | sRGB, 8 bits |
| Poids | 320 Kio maximum ; 48 Mio pour le corpus |
| Métadonnées | aucune EXIF, GPS ou XMP |
| Contenu | sans texte, logo, watermark ni personne reconnaissable |
| Alt FR | unique, descriptif, 60–160 caractères |

Les sorties haute définition et variantes rejetées restent temporaires. Seuls les JPEG servis sont
versionnés dans `demo/catalog/v1/media/`. Le nom d'objet inclut les douze premiers caractères du
SHA-256 et n'est jamais écrasé.

## Storage staging

Le bucket prévu `kwabor-catalog-demo` est public uniquement pour la lecture par URL. Il accepte
`image/jpeg`, limite chaque objet à 512 Kio et n'accorde aucune policy d'insert, update ou delete aux
clients anon/authenticated. Le workflow staging téléverse avec `upsert=false`, puis retélécharge et
vérifie chaque objet avant d'importer le seed.

Le chemin canonique est :

```text
v1/<listing-uuid>/<ordre>-<role>-<sha256-12>-960x1280.jpg
```

Le manifeste conserve à la fois `storage_path`, SHA-256, taille, alt, prompt de génération, date de
génération et statut de revue humaine. L'URL publique est dérivée de l'URL Supabase du staging ; elle
n'est pas codée en dur dans les clients.

## Gates avant ouverture

- exactement 60 fiches publiées, 15 par famille et 20 par ville ;
- exactement 180 médias, trois par fiche et une seule couverture ;
- aucun domaine `.invalid`, contact réel, propriétaire, organisation ou sponsoring ;
- second import strictement idempotent ;
- mêmes fiches publiques pour anon et authenticated, mutations privées inchangées ;
- pages Explore/Search/Detail/Favoris vérifiées avec le corpus ;
- contrôle visuel des crops Android/iOS et test réseau dégradé ;
- rapport de validation et planches-contact archivés dans la CI.

Le mode opératoire de publication et de rollback est décrit dans
[le runbook de la bêta catalogue](runbooks/closed-beta-catalog-release.md). Son exécution reste
conditionnée au provisioning du GitHub Environment `staging` et à une CI exacte verte.

## Documents liés

- [ADR-0036 — profil de livraison](adr/0036-closed-beta-catalog-delivery-profile.md)
- [Plan de livraison V1](v1-production-delivery.md)
- [Tests et qualité](testing.md)
- [Environnements](environment.md)
