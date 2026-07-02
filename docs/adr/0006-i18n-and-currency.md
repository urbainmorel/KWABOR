# 0006 — Préparer i18n et multidevise dès le socle

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le PRD impose six langues à terme et quatre devises d'affichage, avec XOF comme référence.

## Options envisagées

- **FR codé en dur** : rapide, mais bloque l'expansion.
- **i18n progressive plus tard** : coûteuse à corriger.
- **Structure i18n et MoneyXof dès le départ** : coût faible, cohérence durable.

## Décision

Nous retenons une structure i18n dès le socle et un type `MoneyXof` comme référence unique des prix.

## Conséquences

**Positives**
- Les textes et prix ne se dispersent pas.
- Les règles invité/compte et devises restent centralisables.

**Négatives / compromis assumés**
- Les vraies ressources localisées seront enrichies progressivement.

**À revoir si**
- Un système de ressources Compose multiplatform plus adapté est adopté.
