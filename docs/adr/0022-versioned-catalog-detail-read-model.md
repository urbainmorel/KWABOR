# 0022 — Contrat versionné et intègre des fiches catalogue détaillées

- **Statut** : accepté
- **Date** : 2026-08-02
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile
- **Complète** : ADR-0020

## Contexte et problème

Le socle `listings` et la fondation événementielle permettaient d'afficher des résumés Explore,
mais pas de charger une fiche complète sans réinterpréter côté Android et iOS des tables
polymorphes, des lignes enfants et des champs d'autorité. Les sous-types établissement n'avaient
pas encore de stockage relationnel dédié. Les horaires et liens étaient des objets JSONB trop
permissifs, et aucune validation finale ne garantissait qu'une fiche active soit réellement
décodable par le client strict.

Le détail public doit rester catalogue only : les avis et contenus sociaux sont des UGC séparés.
Il doit fonctionner en réseau lent avec un seul aller-retour, ne divulguer aucun identifiant de
propriétaire, d'organisation ou de stockage et échouer fermé si une ligne publiée est incomplète.

## Options envisagées

- **Lire directement toutes les tables depuis chaque client** : réduit le SQL initial, mais duplique
  les jointures, les règles de publication et la construction polymorphe sur Android et iOS.
- **Ajouter un JSONB libre à `listings`** : facilite l'ajout de champs, mais affaiblit les contraintes,
  la RLS, les indexes et la migration contrôlée du contrat.
- **Conserver des extensions relationnelles et exposer un read model versionné** : garde une source
  normalisée et produit une projection publique stable en un appel.

## Décision

Nous retenons des extensions relationnelles typées et le RPC
`public.get_catalog_detail_v1(p_listing_id uuid) returns table(payload jsonb)`.

### Modèle relationnel

`categories.detail_variant` est une enum fermée : `place`, `lodging`, `food`, `nightlife`, `guide`,
`event`. Elle doit rester compatible avec la famille `listing_type` et sa classe : un lieu est
patrimonial ou commercial, un établissement est commercial et un événement est événementiel. Une
insertion de fiche verrouille sa catégorie et une catégorie déjà référencée ne peut pas changer
silencieusement de variant, y compris face à une insertion concurrente.

Les extensions 1-à-1 sont `place_details`, `lodging_details`, `food_details`,
`nightlife_details`, `guide_details` et `event_details`. Les collections sont `room_types`,
`ticket_tiers`, `listing_amenities` et `listing_media`. Une garde verrouille le parent et refuse une
extension ou collection incompatible avec son variant. Après acquisition du verrou, elle revérifie
aussi l'onboarding, le droit de gestion et le statut mutable du parent afin qu'une publication
concurrente ne puisse pas contourner la RLS évaluée au début d'une écriture.
La définition d'un service ne peut pas retirer un variant déjà utilisé par une fiche liée ; la ligne
de vocabulaire est verrouillée lors de la création du lien pour fermer la course concurrente.

Les états `en_attente` et `publie` sont complets au niveau serveur. La validation est un trigger de
contrainte différé afin qu'un brouillon puisse être assemblé dans n'importe quel ordre, tout en
refusant le commit d'un état actif incomplet. Elle exige notamment :

- exactement une image de couverture officielle ;
- adresse et coordonnées au Bénin pour les lieux et établissements ;
- horaires sur sept jours, au moins un contact et un service pour les établissements ;
- détail typé correspondant à la catégorie ;
- prix XOF et unité cohérents avec le variant ;
- tarif d'entrée strictement positif pour un lieu payant ;
- URL de billetterie, au moins un palier et tous les paliers strictement positifs pour un événement
  payant ; le prix de départ égale le palier le moins cher ;
- `published_at` non nul pour toute fiche `publie`.

La précision des heures d'arrivée et de départ est la minute : elles restent dans `00:00..23:59`
avec des secondes à zéro, ce qui exclut la valeur PostgreSQL spéciale `24:00:00` refusée par le
mapper. `published_at` est nul ou fini ; les timestamps `infinity` et `-infinity` ne franchissent
jamais le contrat mobile, pas plus que les années hors de la plage détaillée ci-dessous. L'âge
minimum vie nocturne reste dans `16..25` et l'expérience d'un guide dans `0..80`, comme le contrat
de décodage partagé.

### Contrats JSONB contrôlés

`opening_hours` vaut `{}` pour un brouillon ou une famille qui n'impose pas d'horaires. Sinon il
contient exactement les sept clés anglaises `monday` à `sunday`. Chaque jour contient exactement
`status` et `periods`. Le statut est `closed`, `open_24_hours` ou `periods`; un créneau contient
`opens_minute`, `closes_minute` et `closes_next_day`. Les minutes sont dans `0..1439`, les créneaux
sont ordonnés, sans chevauchement, et un créneau nocturne est le dernier du jour. Le fuseau métier
implicite est `Africa/Porto-Novo`; aucun offset n'est stocké dans cet objet hebdomadaire.

`socials` accepte uniquement `instagram`, `facebook`, `tiktok`, `youtube`, `x` et `linkedin`.
Les tags et tous les tableaux texte typés sont unidimensionnels, non vides par élément et sans
doublon sensible à la casse. Les tableaux PostgreSQL multidimensionnels sont refusés afin que leur
projection JSON reste toujours un `List<String>` décodable. Tous les textes projetés depuis
`listings`, `cities`, `categories`, `listing_media`, les tables de détail et leurs collections sont
stockés avec des bords canoniques : aucune classe d'espace reconnue par le contrat mobile n'est
acceptée au début ou à la fin, y compris tabulation et saut de ligne, sans modifier les espaces
intérieurs. La détection SQL énumère les catégories et contrôles Unicode de `Char.isWhitespace`
plutôt que de dépendre du `LC_CTYPE` PostgreSQL. Les identifiants texte `cities.id`, `categories.id`
et les `categories.subtype` suivent en plus un format slug ASCII minuscule (`a-z`, chiffres et
tirets simples). Les clés étrangères de
ville et la clé taxonomique composite propagent cet invariant aux identifiants et sous-types
projetés depuis `listings`, y compris pour un lieu d'événement.

Tous les liens projetés utilisent le même sous-ensemble sûr que le mapper Ktor : texte trimé de
2 048 octets UTF-8 maximum, schéma HTTPS, DNS canonique multi-label non local, port absent ou
exactement `:443`, sans forme rembourrée telle que `:0443`, IP, IPv6, userinfo, fragment, antislash
ni suffixe privé (`localhost`, `local`, `internal`, `lan`, `home.arpa`). Chaque signe `%` doit former
un triplet hexadécimal complet, dans le chemin comme dans la query. Le corpus SQL couvre les mêmes
autorités, ports non canoniques, percent-escapes et bornes non-BMP que le corpus mobile. Il n'existe
pas d'allowlist CDN avant MEDIA-001.

Les timestamps projetés restent dans l'intervalle RFC3339 interopérable avec `kotlin.time.Instant`,
de l'année 0001 incluse à l'année 10000 exclue. Cette borne s'applique à `published_at`,
`sponsored_until`, `event_details.start_at` et `event_details.end_at`, en plus de leurs règles métier.
Le curseur du RPC résumé refuse les timestamps forgés hors de cette plage. Le tri résumé utilise
directement le `published_at` obligatoire d'une fiche publiée et ne retombe plus sur le timestamp
technique `created_at`.

### Projection publique V1

Le RPC est `stable`, `security invoker`, avec un `search_path` vide. Il retourne zéro ligne si
l'identifiant est absent, si la fiche n'est pas publiée ou si son horodatage de publication manque,
y compris pour son gestionnaire authentifié. Un événement échoue également fermé si son lieu lié ou
la ville de ce lieu n'est pas lisible publiquement, plutôt que de produire un objet imbriqué
indécodable.

La racine V1 contient uniquement :

`schema_version`, `id`, `is_claimable`, `type`, `subtype`, `listing_class`, `name`, `slug`,
`description`, `content_lang`, `city`, `category`, `location`, `price`, `opening_hours`, `contact`,
`socials`, `tags`, `verified`, `metrics`, `published_at`, `media`, `amenities`, `detail`.

`detail.variant` discrimine les six formes fermées. Les chambres, paliers, médias et services sont
triés par `display_order`, unique dans leur collection. Les objets enfants n'exposent ni identifiant
technique, ni timestamp, ni `storage_path`. `listing_media.kind` prépare les métadonnées image/vidéo,
mais cette tranche ne crée aucun bucket, upload, dérivé, MIME ou politique Storage : ces garanties
restent la responsabilité de MEDIA-001.

`is_claimable` est une colonne générée stockée par PostgreSQL, dérivée uniquement de la classe et de
l'absence de `owner_id` et `organization_id`. Elle vaut vrai seulement pour une classe `commercial`
ou `evenementiel`; une fiche patrimoniale n'est jamais revendicable. C'est le seul signal public
dérivé des colonnes d'autorité, qui ne sont jamais accordées aux rôles clients.

Le RPC ne projette jamais `status`, `owner_id`, `steward_id`, `submitted_by`, `organization_id`, les
colonnes éditoriales/sponsoring, les chemins Storage, les avis ou les contenus sociaux.

### RLS, privilèges et déploiement

Toutes les nouvelles tables activent RLS. `anon` lit uniquement les enfants d'une fiche publiée.
`authenticated` lit aussi les brouillons qu'il peut gérer. Une écriture exige l'onboarding terminé,
le droit serveur de gérer le parent et un statut `brouillon` ou `en_attente`, sauf Admin vérifié.
La suppression directe reste limitée au brouillon.

RLS définit quelles lignes sont visibles ; les grants par colonne définissent quelles données
peuvent franchir cette frontière. Le `SELECT` table-wide de `listings` est révoqué pour `anon` et
`authenticated` : `owner_id`, `steward_id`, `submitted_by`, `organization_id`, `created_at`,
`updated_at`, les champs éditoriaux et les données géographiques techniques restent masqués, tandis
que `is_claimable` et les colonnes strictement nécessaires aux read models sont accordés. La même
stratégie masque `listing_media.id`, `storage_path`, `created_at`, les identifiants techniques des
chambres et tarifs, ainsi que tous les timestamps techniques des extensions. Les privilèges complets
de `service_role` restent inchangés.

Les deux RPC publics restent `security invoker` et fonctionnent avec ces grants minimaux. Le RPC
résumé est redéfini pour trier les médias uniquement par `display_order`, unique par fiche, et le RPC
détail applique la même règle aux médias, chambres et tarifs. Aucun identifiant enfant ni timestamp
technique n'est donc nécessaire pour obtenir un ordre déterministe.

Le RPC est exécutable uniquement par `anon` et `authenticated`. Les fonctions de garde et de
validation finale restent dans `app_private`, à privilèges révoqués, avec `security definer`
seulement lorsqu'une inspection au-delà de RLS est nécessaire.

La migration est fail-closed. Elle audite et valide l'historique avant d'ajouter les contraintes :
catégories sans variant, états actifs incomplets, couvertures multiples, ordres média dupliqués,
textes non canoniques, liens ou coordonnées invalides interrompent le déploiement. La donnée doit
être corrigée ou remise explicitement en brouillon avant de rejouer la migration.

## Conséquences

**Positives**

- Android et iOS consomment le même contrat strict et versionné en un aller-retour.
- Les incohérences sont refusées avant publication plutôt que transformées en erreurs client.
- Les frontières RLS, autorité et UGC sont visibles et testables récursivement.
- Le modèle garde des contraintes relationnelles, des tris déterministes et des évolutions de schéma
  auditables.

**Négatives / compromis assumés**

- Toute évolution incompatible exige un nouveau RPC/version de payload.
- Le trigger différé et les gardes de parent ajoutent des verrouillages aux écritures de fiche.
- Une base historique non canonique bloque volontairement la migration au lieu d'être normalisée en
  silence.
- Le fuseau hebdomadaire est implicite tant que Kwabor reste mono-pays Bénin.

## Hors de cette décision

- L'UI Android Compose Multiplatform et l'UI iOS SwiftUI de la fiche.
- Le cache/offline mobile et la télémétrie de consultation.
- Les buckets, uploads, transformations et quotas média de MEDIA-001.
- Les avis, réponses, favoris, likes et contenus sociaux, qui restent des flux UGC séparés.
- Le workflow de re-modération des modifications sensibles d'une fiche déjà publiée.

## À revoir si

- une V2 du read model ajoute un champ incompatible ou une nouvelle famille de détail ;
- Kwabor sort du marché mono-pays et doit stocker un fuseau par fiche ;
- MEDIA-001 introduit des URLs signées ou un contrat CDN nécessitant une projection distincte ;
- le workflow de re-modération autorise l'édition transactionnelle d'une version publiée.
