# 0009 — Implémenter le design system avant les écrans

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Kwabor dépend fortement d'une identité monochrome, de tokens et d'un composant prix fiable.

## Options envisagées

- **Styliser écran par écran** : rapide, mais incohérent.
- **Attendre les maquettes finales** : bloque le développement.
- **Tokens minimaux d'abord** : stable et aligné avec DESIGN.

## Décision

Nous retenons une première couche de tokens Compose, thème clair/sombre et `PriceTag` minimal avant les écrans métier.

## Conséquences

**Positives**
- Les futures UI partent d'une base cohérente.
- Les écarts de couleur restent contrôlés.

**Négatives / compromis assumés**
- Les typographies finales dépendront des assets/fonts validés.

**À revoir si**
- Figma introduit des tokens incompatibles avec le DESIGN actuel.
