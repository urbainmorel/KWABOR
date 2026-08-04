# Contribuer à KWABOR

> Produire des changements Android/iOS maintenables, prouvés et alignés avec le PRD/DESIGN.

## Avant de modifier le dépôt

1. Lire [AGENTS.md](AGENTS.md), [PRD.md](PRD.md), [DESIGN.md](DESIGN.md) et les ADR du périmètre.
2. Vérifier [PROJECT_STATE.md](PROJECT_STATE.md), [BACKLOG.md](BACKLOG.md) et
   [docs/V1-PROGRESS.md](docs/V1-PROGRESS.md).
3. Confirmer les dépendances du ticket dans [le plan V1](docs/v1-production-delivery.md).
4. Repartir d'une base connue et créer une branche `codex/<ticket>`.

Ne pas commencer une décision structurante ambiguë par du code : réaliser l'audit, documenter les
options et obtenir la validation nécessaire.

## Règles d'implémentation

- Limiter les clients à Android Compose Multiplatform et iOS SwiftUI.
- Respecter `presentation -> domain <- data` et garder le domaine Kotlin pur.
- Définir les interfaces côté domaine et injecter les implémentations avec Koin.
- Utiliser états immuables, intents scellés, flux unidirectionnel et effets explicites.
- Modéliser les erreurs attendues ; ne jamais exposer un message technique brut à l'utilisateur.
- Traiter réseau intermittent, concurrence, annulation, réponses obsolètes et reprise de session.
- Appliquer les droits côté Supabase/RLS/RPC, jamais uniquement côté UI.
- Conserver XOF comme devise d'autorité et les secrets hors du dépôt.
- Ne pas présenter un stub, TODO ou CTA inactif comme une fonctionnalité livrée.

## Workflow local

1. Inspecter avant d'écrire et annoncer les fichiers/risques du lot.
2. Implémenter par incréments petits et cohérents.
3. Ajouter les tests du comportement et des chemins d'erreur.
4. Exécuter les validations ciblées, puis la gate proportionnée au risque.
5. Mettre à jour documentation, `PROJECT_STATE.md` et `BACKLOG.md`.
6. Auto-relire le diff avant commit.

## Gates de qualité

Gate de base Windows :

```powershell
.\gradlew.bat check --no-daemon --console=plain
git diff --check
```

Ajouter selon le lot :

- APK Android et compilation Kotlin iOS pour une verticale mobile ;
- tests Swift/Xcode sous macOS pour toute modification iOS ;
- reset isolé, pgTAP, lint et grants/RLS pour Supabase ;
- vérificateurs marque/média lorsque ces assets changent ;
- tests sur appareils, accessibilité et performance avant release.

Voir [docs/testing.md](docs/testing.md) pour les commandes vérifiées.

Il est interdit d'affaiblir ktlint/Detekt, d'ajouter une baseline ou un `@Suppress` uniquement pour
faire passer la gate.

## Commits et pull requests

- Un commit doit représenter une intention claire et réversible.
- Ne pas mélanger refactoring non nécessaire, feature et changement de workflow.
- Décrire comportement, risques, tests exécutés et validations impossibles localement.
- Conserver l'ordre des PR empilées et ne pas retargeter silencieusement une branche.
- N'effectuer ni push, relance CI, merge ou publication Store sans autorisation appropriée.

Checklist :

- [ ] Le comportement demandé est réellement implémenté, sans CTA factice.
- [ ] Les cas limites, offline, erreurs et concurrence sont couverts.
- [ ] Android et iOS restent cohérents avec le périmètre du lot.
- [ ] Les règles de domaine, RLS et sécurité sont conservées.
- [ ] Les tests et gates adaptés sont verts.
- [ ] Les schémas/migrations et documents ont été mis à jour si nécessaire.
- [ ] Aucun secret, artefact signé ou configuration fournisseur n'est suivi.
- [ ] Un ADR trace toute nouvelle décision structurante.

## Documentation

- Écrire en français sauf contrat technique imposant l'anglais.
- Séparer état actuel, cible et dépendances externes.
- Vérifier chaque commande, variable, chemin et lien contre le dépôt.
- Étendre la source canonique au lieu de créer un quasi-doublon.
- Mettre à jour [docs/index.md](docs/index.md) lorsqu'un nouveau document de premier niveau apparaît.

Étape suivante : [installer le projet](docs/setup.md) ou [choisir un ticket](BACKLOG.md).
