# AGENTS.md — Instructions fermes pour les agents IA Kwabor

Ce fichier est l'instruction de cadrage du workspace pour tout agent IA qui travaille sur Kwabor.
Sous réserve des instructions système/developer et de la demande utilisateur la plus récente, il est obligatoire.

## Source de cadrage senior

Ce fichier est la règle de travail ferme issue du prompt senior KMP initial.

Tout agent doit le lire avant toute tâche non triviale et en appliquer les contraintes comme des exigences, pas comme des conseils.
En particulier :

- Stack cible : Kotlin Multiplatform + Compose Multiplatform + Supabase.
- Cibles : Android, iOS, Web-PWA.
- Contexte produit : Kwabor, guide visuel et intelligent du Bénin, marché mono-pays Bénin.
- Niveau attendu : ingénierie staff/senior, code maintenable, testable, robuste et livrable en production.

## Sources produit et design

Avant de créer ou modifier une fonctionnalité, lire le périmètre concerné dans :

- [PRD.md](PRD.md) : vision produit, périmètre, exigences fonctionnelles, rôles, roadmap, exigences non fonctionnelles.
- [DESIGN.md](DESIGN.md) : design system, navigation, écrans, états, composants, wizards, modèle de données cible.
- [TOOLING_SETUP_qualite_kmp.md](TOOLING_SETUP_qualite_kmp.md) : chaîne qualité attendue pour KMP.
- [docs/adr/0001-record-architecture-decisions.md](docs/adr/0001-record-architecture-decisions.md) et futurs ADR : décisions structurantes déjà actées.

Si le PRD et le DESIGN semblent diverger, ne pas deviner silencieusement. Signaler la divergence, proposer une option argumentée, puis attendre validation si le choix est structurant.

## Ordre de priorité technique

Appliquer cet ordre strict dans tout arbitrage :

1. Correctness.
2. Lisibilité et maintenabilité.
3. Robustesse.
4. Sécurité.
5. Performance.

La vitesse d'exécution ne justifie jamais un contournement de ces priorités.

## Principes non négociables

- Lire avant d'écrire : code existant, PRD, DESIGN et conventions locales.
- Ne pas inventer d'abstraction spéculative.
- Ne pas inventer d'API de librairie : vérifier ou signaler l'incertitude.
- Ne pas présenter un stub, un TODO ou une logique incomplète comme terminé.
- Toute ambiguïté produit, sécurité, données, paiement, auth, offline ou architecture est remontée explicitement.
- Toute décision structurante doit être documentée par ADR.
- Aucun secret, token, endpoint sensible ou clé API en dur.
- Aucun message technique brut ne doit fuiter vers l'utilisateur final.

## Architecture obligatoire

- Découpage strict : `presentation` -> `domain` -> `data`.
- Domaine 100 % Kotlin pur : aucun import Compose, Supabase, Android, iOS, Ktor ou SDK externe.
- Inversion de dépendance via interfaces définies côté domaine.
- Repositories en data ; DTO séparés des modèles de domaine ; mappers explicites.
- Injection via Koin, pas de singleton manuel ni service locator déguisé.
- `expect`/`actual` uniquement pour les vraies différences plateforme, avec des blocs `actual` minces.
- Organisation par feature, pas par grands dossiers techniques globaux.
- UI en flux unidirectionnel : State immuable observe, Intent/Event entre, ViewModel réduit l'état.

## Kotlin et concurrence

- `val` par défaut ; aucune collection mutable exposée publiquement.
- `!!` interdit.
- `sealed interface` / `sealed class` pour les états finis et erreurs typées.
- `when` exhaustif ; pas de branche `else` paresseuse.
- Guard clauses pour réduire l'imbrication.
- `GlobalScope`, `runBlocking` en production et dispatchers codés en dur sont interdits.
- Dispatchers, horloge et dépendances asynchrones injectés pour tests déterministes.
- Erreurs attendues modélisées en erreurs de domaine, pas en exceptions de contrôle de flux.
- Aucun `catch` silencieux.

## Compose Multiplatform et UI

- Composables stateless, state hoisting, paramètres immuables/stables.
- Aucun effet de bord dans la composition.
- Design system centralisé : couleurs, dimensions, typographies, espacements, strings via tokens/ressources.
- Aucune couleur, dimension ou chaîne littérale à l'écran hors ressources prévues.
- Respect strict de `DESIGN.md` : photo-first, monochrome premium, jaune uniquement pour `Sponsorisé`, rouge pour billet/danger.
- Accessibilité minimale obligatoire : contrastes AA, content descriptions, cibles tactiles suffisantes, focus order quand nécessaire.

## Données, Supabase et sécurité

- Supabase ne fuite jamais dans le domaine ou l'UI.
- RLS respecté, jamais contourné côté client.
- Prix saisis et stockés en XOF ; autres devises uniquement en affichage indicatif.
- Paiements promoteur validés côté serveur uniquement.
- Auth tokens en stockage sécurisé plateforme via `expect`/`actual`.
- Logs sans PII ni données sensibles.
- Entrées utilisateur, deep links et payloads validés.

## Offline, performance et contexte local

- Concevoir pour réseau lent/intermittent et Android low/mid-range.
- Listes virtualisées uniquement.
- Pagination, cache, déduplication des appels en vol, retry avec backoff sur erreurs transitoires.
- Images compressées, downsampling, lazy-load, variantes adaptées.
- Aucun calcul lourd ni allocation répétée dans une recomposition ou un scroll.
- Le premium visuel doit venir du design, pas d'effets coûteux.

## Qualité et validation

Quand une codebase KMP existe, maintenir ces contrôles verts :

- `spotlessCheck`
- `detekt`
- tests ciblés, puis `check` selon le risque

Ne jamais affaiblir une règle ktlint/detekt, ajouter une baseline ou poser un `@Suppress` pour faire passer le build sans justification explicite validée.

## Workflow agent

Pour toute tâche non triviale :

1. Lire le contexte concerné.
2. Résumer brièvement l'approche, les fichiers touchés et les risques.
3. Implémenter par incréments petits et cohérents.
4. Vérifier avec les commandes adaptées.
5. Faire une auto-revue avant de conclure.
6. Dire honnêtement ce qui est fait, testé, non testé ou risqué.

Pour les travaux larges ou structurants, commencer par un audit et attendre validation avant modification.

## Definition of Done minimale

Une tâche n'est pas terminée tant que :

- Le comportement demandé est réellement implémenté.
- Les cas limites et chemins d'erreur sont traités.
- Le code reste cohérent avec PRD/DESIGN et l'architecture locale.
- Les tests/validations adaptés ont été exécutés ou l'impossibilité est explicitement indiquée.
- Aucun anti-pattern interdit par ce fichier n'a été introduit.
- Les décisions structurantes ou dérogations sont tracées en ADR.

En cas de conflit entre facilité immédiate et qualité durable, choisir la qualité durable et expliquer l'arbitrage.
