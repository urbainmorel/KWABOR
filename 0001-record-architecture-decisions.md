# 0001 — Adopter les Architecture Decision Records (ADR)

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Les décisions d'architecture structurantes (choix de librairies transverses, patterns, découpage des modules, stratégie de synchronisation offline) étaient jusqu'ici implicites : dispersées dans des messages, des commentaires de code ou la mémoire de l'équipe. Conséquence : on perd le *pourquoi* d'un choix, on le remet en cause à tort, ou on rejoue des débats déjà tranchés — d'autant plus coûteux quand une partie du code est générée par un agent.

## Options envisagées

- **Rien de formalisé** : rapide, mais les décisions se perdent et deviennent du folklore d'équipe.
- **Un document unique `Architecture.md`** : centralisé, mais devient vite un fourre-tout illisible, sans historique clair ni statut par décision.
- **ADR numérotés et immuables** : un fichier par décision, versionné avec le code, historique explicite par remplacement.

## Décision

Nous retenons les **ADR** : un fichier Markdown numéroté par décision sous `docs/adr/`, suivant le gabarit `0000-template.md`. Toute décision structurante — ou toute dérogation aux règles du prompt système — donne lieu à un ADR. Un ADR n'est jamais réécrit : s'il est invalidé, on crée un nouvel ADR qui le remplace (`Remplacé par ADR-XXXX`).

## Conséquences

**Positives**
- Le *pourquoi* des choix est traçable, versionné avec le code et revu en PR.
- Onboarding accéléré : l'historique de raisonnement est lisible.
- Les arbitrages déjà tranchés ne sont pas rejoués.

**Négatives / compromis assumés**
- Léger coût d'écriture à chaque décision structurante.
- Discipline requise pour ne pas laisser des ADR au statut « proposé » sans conclusion.

**À revoir si**
- La cadence des décisions rend le format trop lourd — auquel cas on allège le gabarit, on ne l'abandonne pas.
