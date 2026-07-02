# 0004 — Utiliser Koin pour l'injection de dépendances

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Kwabor a besoin d'injecter repositories, dispatchers, horloges, clients réseau et services plateforme sans service locator manuel.

## Options envisagées

- **Construction manuelle** : lisible au début, fragile à l'échelle.
- **Service locator maison** : rapide, mais contraire au cadrage senior.
- **Koin** : léger, KMP, adapté aux modules par feature.

## Décision

Nous retenons Koin parce qu'il satisfait le cadrage `AGENTS.md` et reste simple pour un socle KMP.

## Conséquences

**Positives**
- Les dépendances sont explicites et testables.
- Les hôtes peuvent initialiser des modules plateforme minces.

**Négatives / compromis assumés**
- Les modules DI devront rester petits pour éviter une configuration centrale illisible.

**À revoir si**
- Les besoins d'injection deviennent incompatibles avec Koin Multiplatform.
