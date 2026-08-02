# 0020 — Fondation intègre des détails événementiels

- **Statut** : accepté
- **Date** : 2026-08-02
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile
- **Complète** : ADR-0005, ADR-0018, ADR-0019

## Contexte et problème

Le catalogue distingue déjà les fiches `evenement`, mais aucune structure ne stockait leur période,
leur lieu, leur organisateur ou leur billetterie. Le futur contrat Explore ne pouvait donc ni trier
les événements par date, ni appliquer un filtre temporel sans inventer des données côté client.

Le DESIGN définit une extension 1-à-1 `event_details`. Cette fondation doit rester exploitable par la
Data API Supabase, préserver les invariants si la fiche parente change et ne pas anticiper les choix
encore ouverts de classement, de sponsoring ou de sémantique des filtres.

## Options envisagées

- **Ajouter les champs directement à `listings`** : simple à requêter, mais rend les colonnes
  événementielles nullables pour toutes les autres familles et affaiblit le modèle polymorphe prévu.
- **Stocker un objet JSONB libre** : flexible, mais reporte la validation sur chaque client et rend les
  contraintes, indexes et migrations de contrat moins sûrs.
- **Créer une extension relationnelle 1-à-1** : suit le modèle cible, garde les champs typés et permet
  des invariants SQL ainsi que des grants/RLS explicites.

## Décision

Nous retenons `public.event_details`, reliée en 1-à-1 à `public.listings` par `listing_id` avec
suppression en cascade.

La fondation contient : catégorie, début et fin optionnelle en `timestamptz`, fiche de lieu
optionnelle, nom et contact de l'organisateur, type de billet, URL HTTPS optionnelle et capacité
optionnelle. `ticket_type` est une enum fermée `gratuit`/`payant`. Les dates doivent être finies, la
fin ne peut précéder le début et une capacité présente doit être strictement positive.

Les invariants suivants sont appliqués en base :

- la fiche parente reste de type `evenement` et sa catégorie correspond exactement au sous-type ;
- les textes requis sont normalisés par trim et restent non vides, sans maximum arbitraire absent du
  contrat produit ;
- un événement référence soit une fiche `lieu`/`etablissement`, soit l'adresse et les coordonnées de
  sa propre fiche ;
- une mise à jour ultérieure de la fiche parente ne peut invalider sa catégorie ou sa localisation ;
- un événement ne peut passer `en_attente` ou `publie` sans ses détails requis, et ces détails ne
  peuvent plus être supprimés directement tant que le parent conserve l'un de ces statuts ;
- la suppression ou le déplacement de détails verrouille la fiche parente afin de sérialiser cette
  opération avec une soumission concurrente et de réévaluer l'invariant après déverrouillage ;
- un événement brouillon peut préparer un lieu brouillon géré par le même acteur, mais ce lieu doit
  être publié avant le passage de l'événement en revue ou en publication ;
- une fiche déjà utilisée comme lieu ne peut pas devenir un événement et un lieu référencé par un
  événement `en_attente` ou `publie` ne peut pas être dépublié ;
- l'URL de billetterie, lorsqu'elle existe, est HTTPS sans espace, et le contact organisateur est un
  email ou un numéro E.164.

La migration est fail-closed sur l'historique : elle s'interrompt si une fiche événement déjà
`en_attente` ou `publie` n'a pas de ligne `event_details`. Le déploiement doit alors remettre ces
fiches en brouillon ou appliquer un backfill explicitement relu avant de rejouer la migration.

Les gardes côté `listings` et `event_details` doivent inspecter et verrouiller des dépendances qui
peuvent être masquées par RLS à l'acteur authentifié. Ce sont donc des fonctions trigger
`security definer` dans `app_private`, avec `search_path` vide et privilège `EXECUTE` révoqué à
`public`, `anon`, `authenticated` et `service_role`. Avant tout verrou enfant, la garde vérifie
explicitement l'onboarding et le droit de gérer le parent ; un lieu rattaché doit en plus être publié
ou géré par l'acteur. Ces gardes ne constituent pas des fonctions Data API et ne retournent aucune
donnée métier.

Les grants et RLS sont explicites :

- `anon` lit seulement les détails des fiches publiées ;
- `authenticated` lit les fiches publiées et celles qu'il peut administrer ;
- une insertion ou mise à jour exige un onboarding terminé, le droit serveur de gérer la fiche et
  un statut parent `brouillon` ou `en_attente`, sauf pour un Admin vérifié ; la suppression directe
  des détails reste limitée au brouillon, y compris pour l'Admin ;
- les colonnes d'horodatage et l'identifiant parent ne sont pas modifiables par le client ;
- `service_role` reçoit les privilèges explicites nécessaires aux traitements serveur.

Cette borne d'écriture publiée est temporairement alignée avec la politique actuelle de `listings`.
Le parcours de modification avec re-modération exigé par le PRD devra faire évoluer les deux tables
dans une tranche dédiée ; cette fondation ne doit pas créer un contournement partiel.

## Hors de cette décision

- La récurrence, prévue en V1.1.
- Les tranches de billets multiples et leurs prix XOF sont déjà obligatoires pour les événements
  payants du MVP. Leur table et leur invariant conditionnel seront livrés avec le modèle de détail et
  le workflow de création ; ils ne sont pas redécidés par ce premier contrat de tri Explore.
- La formule de popularité, le plafond sponsorisé, les tris disponibles et la sémantique exacte des
  intervalles de dates.
- Le RPC catalogue v2, son curseur, le cache mobile et les interfaces Android/iOS.

## Conséquences

**Positives**

- Le serveur dispose d'une source typée pour les dates d'événements sans classement client divergent.
- Les incohérences restent impossibles même lorsqu'une fiche parente est modifiée après création des
  détails.
- Les nouveaux projets Supabase restent compatibles avec l'exposition Data API par grants explicites
  et RLS.
- Le seed local fournit un événement futur à dates fixes, payant et reproductible pour les prochains
  tests RPC. Il ne constitue pas encore une preuve de billetterie V1 complète sans `ticket_tiers`.
- Le harnais pgTAP multi-connexion reproduit les deux ordres suppression/soumission, la perte
  concurrente d'une localisation directe et la conversion concurrente d'un lieu ; aucun résultat
  final ne peut violer les références événementielles. Comme il réalise des transactions réellement
  concurrentes, il est exclu de la suite distante standard et ne s'exécute que par le runner Python
  qui vérifie une URL Supabase localhost puis injecte un marqueur de session explicite.

**Négatives / compromis assumés**

- `category` duplique volontairement `listings.subtype` et nécessite une garde bidirectionnelle.
- Les gestionnaires ne peuvent pas encore modifier directement les détails d'un événement publié ;
  le workflow de re-modération doit être livré avant le wizard complet.
- Une base contenant déjà un événement actif incomplet doit être remédiée avant déploiement ; la
  migration refuse volontairement de masquer cette dette par un backfill implicite.
- Cette fondation ne suffit pas à déclarer EXPLORE-002B ou DETAIL-001 terminés.

**À revoir si**

- le workflow de re-modération des fiches publiées est introduit ;
- la table `ticket_tiers` et ses règles conditionnelles sont ajoutées au workflow de détail V1 ;
- la mesure d'un RPC v2 sur un corpus staging justifie d'autres indexes temporels.
