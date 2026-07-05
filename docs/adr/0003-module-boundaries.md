# 0003 — Découper le socle mobile en shared, androidApp et iosApp

- **Statut** : remplacé par [ADR-0010](0010-mobile-only-swiftui-team-access.md)
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le dépôt devait compiler rapidement sur Windows tout en préparant les hôtes mobiles.

Depuis ADR-0010, le produit cible uniquement Android et iOS. Aucun nouveau travail ne doit cibler l'ancienne cible non mobile. La suppression physique du module a été exécutée en MOB-002.

## Options envisagées

- **Un seul module** : simple, mais les responsabilités se mélangent.
- **Modules par plateforme seulement** : clair côté hôtes, mais le partage devient faible.
- **`shared` + hôtes minces** : domaine, data et présentation partagés; hôtes limités au bootstrap.

## Décision

Cette décision est remplacée par ADR-0010 : le découpage cible est `shared`, `androidApp` et `iosApp` SwiftUI.

## Conséquences

**Positives**
- Le domaine reste commun et pur.
- Les hôtes restent minces.

**Négatives / compromis assumés**
- La compilation iOS complète n'est pas garantie sur Windows.
- L'ancienne cible non mobile a été retirée proprement en MOB-002.

**À revoir si**
- Le découpage mobile ne permet plus de tenir les exigences Android/iOS.
