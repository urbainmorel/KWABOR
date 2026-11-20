# ADR-0038 — Staging de démonstration sous la responsabilité d’un mainteneur

**Statut** : accepté le 20 novembre 2026.

## Décision

Le staging du catalogue peut servir au test individuel sur un appareil du mainteneur, avec données fictives et parcours invité. Les cinq workflows manuels de sauvegarde, migration, archivage des fixtures, Storage et catalogue sont réservés à l’identifiant GitHub du propriétaire canonique `urbainmorel` (`146450999`). Toute relance exige aussi que `urbainmorel` soit à l’origine de la relance. Les jobs vérifient le dépôt canonique et la branche `main` protégée avant d’accéder à l’Environment `staging`.

L’Environment de démonstration n’a pas de deuxième reviewer. Il reste limité aux branches protégées et interdit le bypass administrateur. Le SHA exact, la CI `push/main` réussie, l’identité Supabase distincte de la production, la sérialisation, les confirmations d’écriture existantes et les reçus GEL restent exigés. Le mainteneur examine la preuve du plan avant de lancer un apply.

Pour le staging existant, la sauvegarde B6.02 chiffrée et restaurée sur un runner éphémère précède les mutations. Le mainteneur conserve une copie de récupération age hors ligne, séparée de GitHub, et teste son déchiffrement avant d’attester la date et la durée de validité. Le mode `offline-single-maintainer` et la politique `single-maintainer-demo` sont liés au reçu. Un reçu produit sous une autre politique exige une nouvelle sauvegarde qualifiée.

## Limites

La revue et la garde de la clé reposent sur une seule personne. La perte de la copie hors ligne et la compromission du compte ne sont plus couvertes par un contrôle humain indépendant. Ce risque est accepté pour des données de démonstration reconstructibles. Auth, inscriptions, cohorte, Google Play Internal, TestFlight et production relèvent de leurs contrôles de distribution existants. Une nouvelle décision explicite est requise avant d’accueillir des testeurs ou des données réelles dans cet environnement.
