# ADR-0038 — Staging de démonstration sous la responsabilité d’un mainteneur

**Statut** : accepté le 20 novembre 2026.

## Décision

Le staging du catalogue peut servir au test individuel sur un appareil du mainteneur, avec données fictives et parcours invité. Les cinq workflows manuels de sauvegarde, migration, archivage des fixtures, Storage et catalogue sont réservés à l’identifiant GitHub du propriétaire canonique `urbainmorel` (`146450999`). Toute relance exige aussi que `urbainmorel` soit à l’origine de la relance. Les jobs vérifient le dépôt canonique et la branche `main` protégée avant d’accéder à l’Environment `staging`.

L’Environment de démonstration n’a pas de deuxième reviewer. Il reste limité aux branches protégées et interdit le bypass administrateur. Le SHA exact, la CI `push/main` réussie, l’identité Supabase distincte de la production, la sérialisation, les confirmations d’écriture existantes et les reçus GEL restent exigés. Le mainteneur examine la preuve du plan avant de lancer un apply.

Pour le test individuel du catalogue fictif, le propriétaire accepte la perte des données de démonstration reconstructibles du staging actuel. La restauration B6.02 devient facultative pour ce parcours. Avant toute écriture, le workflow doit prouver que la cible est le staging approuvé, que les données Auth et Storage sont vides et que les seules données applicatives présentes appartiennent aux fixtures versionnées. Une donnée inattendue bloque l’opération. Le workflow conserve la CI sur le SHA exact, le plan en lecture seule et la confirmation d’écriture.

Le parcours est sélectionné par l’option `reconstructible_demo=true` du plan et de l’exécution. La preuve compare l’empreinte de toutes les tables applicatives du staging au jeu fictif audité. B6.02 garde son rôle pour les opérations qui doivent préserver un état existant.

## Limites

La revue et la garde de la clé reposent sur une seule personne. La perte de la copie hors ligne et la compromission du compte ne sont plus couvertes par un contrôle humain indépendant. Ce risque est accepté pour des données de démonstration reconstructibles. Auth, inscriptions, cohorte, Google Play Internal, TestFlight et production relèvent de leurs contrôles de distribution existants. Une nouvelle décision explicite est requise avant d’accueillir des testeurs ou des données réelles dans cet environnement.
