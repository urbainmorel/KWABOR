# Modèle de données KWABOR

> Vue opérationnelle des données versionnées dans le dépôt : PostgreSQL/Supabase côté serveur,
> Room/DataStore côté appareil et stockage sécurisé pour la session.

> [!IMPORTANT]
> Une migration présente dans Git ne prouve pas son déploiement sur staging ou production. Les
> environnements KWABOR réels restent à provisionner et qualifier.

## Sources d'autorité

| Source | Ce qu'elle prouve |
| --- | --- |
| [`supabase/migrations/`](../supabase/migrations/) | Schéma serveur, contraintes, RLS, grants et RPC |
| [`supabase/tests/`](../supabase/tests/) | Attentes pgTAP et scénarios négatifs |
| [`shared/schemas/`](../shared/schemas/) | Historique exporté du schéma Room |
| `shared/.../domain` | Modèles et contrats métier sans dépendance technique |
| `shared/.../data` | DTO, entités locales, mappers et implémentations |

## Modèle serveur actuel

Les migrations courantes définissent 38 tables `public` avec RLS. `auth.users`, géré par Supabase
Auth, reste l'autorité d'identité externe à ces tables.

| Agrégat | Tables principales | Responsabilité |
| --- | --- | --- |
| Identité et légal | `profiles`, `user_roles`, `legal_documents`, `user_legal_acceptances`, `account_deletion_requests` | Profil, rôles vérifiés, consentements et suppression |
| Organisations | `organizations`, `organization_members`, `organization_invites`, `member_ad_budgets`, `promoter_invites` | Équipes vérifiées et droits cumulatifs |
| Catalogue | `cities`, `categories`, `listings`, `listing_media`, `event_details` | Socle public et événements |
| Détail typé | `amenities`, `place_details`, `lodging_details`, `room_types`, `food_details`, `nightlife_details`, `guide_details`, `ticket_tiers`, `listing_amenities` | Variantes relationnelles d'une fiche |
| Guides | `guide_languages`, `guide_specialties`, `guide_service_cities`, `guide_service_languages`, `guide_service_specialties` | Facettes et couverture des services de guide |
| Interactions | `favorites`, `likes`, `social_posts`, `social_media`, `notifications` | État utilisateur et premières fondations sociales |
| Gouvernance | `claims`, `missing_place_reports` | Revendications et lieux absents |
| Promotion | `campaigns`, `payments` | Fondations de campagnes et paiements XOF |

## Invariants structurants

- `categories` est l'autorité de taxonomie. La combinaison catégorie, type, sous-type et classe
  d'une fiche est contrainte côté base.
- Chaque fiche active utilise exactement une variante fermée : lieu, hébergement, restauration,
  nightlife, guide ou événement.
- Les extensions sont liées à `listings`; chambres, billets et équipements restent des collections
  relationnelles ordonnées.
- Les événements imposent dates cohérentes, localisation valide et billetterie typée.
- Une fiche patrimoniale n'est jamais revendicable par un propriétaire privé.
- Les prix d'autorité sont des entiers XOF. Les autres devises ne sont que des affichages indicatifs.
- Les coordonnées des fiches catalogue sont contraintes par le polygone du Bénin ; les lieux
  manquants utilisent une boîte englobante moins précise. Les coordonnées de `cities` ne disposent
  pas encore d'une contrainte SQL géographique équivalente.
- Les médias officiels utilisent des URL HTTPS, un ordre unique et une seule couverture image.
- Les rôles d'organisation sont cumulatifs : Propriétaire > Gestionnaire > Éditeur > Modérateur.
- Les documents légaux sont versionnés ; la fin d'onboarding est validée par RPC.

## Frontière d'accès

| Acteur | Accès attendu |
| --- | --- |
| `anon` | Projections catalogue publiées et autres surfaces publiques explicitement accordées : référentiels, documents légaux actifs, profils publics/onboardés, contenus Social publiés et facettes guide |
| `authenticated` | Données propres et RPC autorisés selon onboarding/rôle |
| Admin vérifié | Files et transitions de modération explicitement accordées |
| `service_role` | Opérations serveur réservées, notamment suppression de compte |

RLS et grants par colonne restent tous deux nécessaires. Les RPC sensibles dérivent l'identité de
`auth.uid()` et les fonctions privilégiées internes vivent dans `app_private`; ce schéma n'est pas
un contrat client.

Les lectures publiques principales sont versionnées :

- `list_catalog_summaries(...)` ;
- `get_catalog_detail_v1(uuid)` ;
- `list_guide_facets_v1()` ;
- `list_guide_services_v1(...)`.

Elles sont publication-only et n'exposent pas les identifiants d'autorité ni chemins Storage privés.

## Persistance locale actuelle

### Room KMP

La base `kwabor.db` est en version 2 avec migration automatique `1 -> 2`. Elle contient six tables :

- `explore_cache_snapshots` ;
- `explore_cached_listings` ;
- `explore_cache_snapshot_items` ;
- `explore_reference_snapshots` ;
- `explore_reference_cities` ;
- `explore_reference_categories`.

Le contenu canonique est séparé des requêtes/snapshots et de leur ordre. Un snapshot contient au
maximum 50 fiches et la rétention garde 64 snapshots récents. Les écritures obsolètes sont rejetées
et une corruption logique évince seulement le snapshot concerné.

Android place Room dans `noBackupFilesDir/KwaborRoom`, exclu de chaque mode de sauvegarde par le
système, et conserve des règles explicites qui excluent les neuf domaines du cloud et des transferts
appareil-à-appareil. iOS utilise le sous-dossier dédié `Application Support/KwaborRoom`, exclu des
sauvegardes et protégé avec `CompleteUntilFirstUserAuthentication`. Les anciennes bases v2,
composées uniquement de cache régénérable, sont supprimées lors du changement de chemin. Si une
plateforme ne peut pas appliquer sa politique, Room reste en mémoire pour la session au lieu
d’ouvrir une base disque non conforme. Les schémas exportés sont versionnés et vérifiés par `check`.
Une donnée de compte durable, comme le futur historique de recherche connecté, garde son autorité
côté Supabase/RLS ; Room n’en conserve qu’un miroir local régénérable.

### DataStore et session

`kwabor.preferences_pb` conserve seulement :

- `explore_city_id` ;
- `app_locale` ;
- `display_currency`.

DataStore ne contient aucun token ou état métier synchronisable. Les tokens d'authentification
restent dans le stockage sécurisé plateforme, derrière les contrats partagés.

## Capacités explicitement absentes

Le schéma cible du DESIGN ne doit pas être confondu avec l'état livré. Sont encore absents ou
incomplets :

- avis, réponses et signalements de fiches/avis ;
- outbox persistante Like/Favori et brouillons conflictuels ;
- préférences/tokens push complets ;
- buckets, uploads temporaires et finalisation média ;
- ledger et webhooks FedaPay ;
- taux de change, vecteurs et données IA ;
- caches locaux recherche, détail et guide.

Ces capacités restent suivies dans [BACKLOG.md](../BACKLOG.md).

## Migrations et preuves

- Les migrations Supabase sont ordonnées par timestamp et appliquées en avant uniquement. Elles
  peuvent remplacer des policies, contraintes, grants ou fonctions ; une correction déjà déployée
  passe donc par une nouvelle migration, jamais par la réécriture de l'historique.
- Toute modification serveur exige des tests pgTAP, RLS négative et vérification des grants.
- Une base persistante exige sauvegarde et [préflight d'autorisation](runbooks/security-authorization-preflight.md).
- Toute version Room supérieure conserve les anciens schémas JSON et fournit une migration testée ;
  aucun fallback destructif n'est admis en production.

Les commandes reproductibles sont dans [testing.md](testing.md).
