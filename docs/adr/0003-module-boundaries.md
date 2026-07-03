# 0003 — Découper le socle initial en shared, androidApp et webApp

- **Statut** : remplacé par [ADR-0010](0010-mobile-only-swiftui-team-access.md)
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le dépôt devait compiler rapidement sur Windows tout en préparant Android, iOS et PWA.

Depuis ADR-0010, Web/PWA sort du scope V1. Aucun nouveau travail ne doit cibler `webApp`. La suppression physique du module est planifiée en MOB-002.

## Options envisagées

- **Un seul module** : simple, mais les responsabilités se mélangent.
- **Modules par plateforme seulement** : clair côté hôtes, mais le partage devient faible.
- **`shared` + hôtes minces** : domaine, data et présentation partagés; hôtes limités au bootstrap.

## Décision

La décision initiale retenait `shared`, `androidApp` et `webApp`, avec `iosApp` documenté pour intégration Xcode ultérieure.

Cette décision est remplacée par ADR-0010 : le découpage cible devient `shared`, `androidApp` et `iosApp` SwiftUI.

## Conséquences

**Positives**
- Le domaine reste commun et pur.
- Les hôtes restent minces.

**Négatives / compromis assumés**
- La compilation iOS complète n'est pas garantie sur Windows.
- Le module `webApp` doit être retiré proprement.

**À revoir si**
- Une cible Web/PWA est réouverte par ADR.
