# Documentation KWABOR

> Point d'entrée vers les sources produit, techniques, opérationnelles et décisionnelles du dépôt.

## Parcours recommandé

1. Lire le [README](../README.md) pour comprendre le produit et l'état de livraison.
2. Suivre le [guide de setup](setup.md) pour construire Android ou iOS.
3. Lire [l'architecture](architecture.md) et le [modèle de données](data-model.md).
4. Utiliser le [guide de tests](testing.md) avant toute proposition de changement.
5. Vérifier [l'état V1](V1-PROGRESS.md), [l'état détaillé](../PROJECT_STATE.md) et le
   [backlog](../BACKLOG.md) avant de choisir un lot.

## Produit et design

| Document | Public principal | Rôle |
| --- | --- | --- |
| [PRD](../PRD.md) | Produit, ingénierie | Périmètre fonctionnel et exigences V1 |
| [DESIGN](../DESIGN.md) | Produit, design, mobile | Navigation, écrans, états et modèle cible |
| [Plan de livraison V1](v1-production-delivery.md) | Pilotage | Dépendances, séquence et gates de production |
| [Suivi V1](V1-PROGRESS.md) | Équipe | État vérifié, preuves et limites courantes |

## Développement

| Document | Public principal | Rôle |
| --- | --- | --- |
| [Setup](setup.md) | Nouvel arrivant | Prérequis, configuration locale et premiers builds |
| [Architecture](architecture.md) | Développeur, reviewer | Couches, modules, flux et frontières de sécurité |
| [Modèle de données](data-model.md) | Mobile, backend | Entités Supabase, stockage local et migrations |
| [Tests et qualité](testing.md) | Contributeur | Commandes, niveaux de tests et gate avant PR |
| [Contribution](../CONTRIBUTING.md) | Contributeur | Workflow Git, critères de changement et checklist |
| [Chaîne qualité KMP](../TOOLING_SETUP_qualite_kmp.md) | Mainteneur | Configuration détaillée Spotless/Detekt/CI |

## Configuration et livraison

| Document | Public principal | Rôle |
| --- | --- | --- |
| [Vue d'ensemble des environnements](environment.md) | Développeur, opérateur | Tiers, fichiers locaux et catégories de valeurs |
| [Configuration complète](environment-configuration.md) | Opérateur | Variables, fournisseurs et gates propriétaires |
| [Déploiement](deployment.md) | Release manager | Séquence commune, preuves et rollback |
| [Release Android](android-release.md) | Release manager Android | Variants, signature, AAB et Play |
| [Release iOS](ios-release.md) | Release manager iOS | Configurations, signature, archive et App Store |
| [Observabilité](observability.md) | Mobile, opérations | Consentement, événements et fournisseurs Firebase |

## Runbooks

| Runbook | Quand l'utiliser |
| --- | --- |
| [Vidéo d'introduction Store-only](runbooks/onboarding-video-store-release.md) | Remplacer ou qualifier le média embarqué |
| [Préflight des autorisations](runbooks/security-authorization-preflight.md) | Avant toute migration de sécurité sur une base persistante |
| [Onboarding mobile](onboarding.md) | Comprendre et diagnostiquer le premier lancement/auth |

## Décisions et audits

- Les décisions structurantes vivent dans [`docs/adr/`](adr/). Commencer par
  [ADR-0001](adr/0001-record-architecture-decisions.md), puis lire les ADR liés au lot modifié.
- Les audits datés vivent dans [`docs/audits/`](audits/). Ils décrivent une photographie vérifiée et
  ne remplacent pas `PROJECT_STATE.md` pour l'état courant.
- Les règles d'orchestration des agents sont dans [agent-orchestration.md](agent-orchestration.md) ;
  les contraintes du workspace restent dans [AGENTS.md](../AGENTS.md).

## Règles de mise à jour

- Modifier la source canonique plutôt que créer un document presque identique.
- Séparer explicitement comportement actuel, cible planifiée et dépendances externes.
- Vérifier chaque commande, chemin, variable et contrat contre le dépôt avant publication.
- Mettre à jour `PROJECT_STATE.md` et `BACKLOG.md` lorsque l'état réel change.
- Ajouter ou modifier un ADR lorsqu'une décision affecte plusieurs modules ou la production.

Étape suivante : [installer le projet localement](setup.md).
