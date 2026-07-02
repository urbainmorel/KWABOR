# 0003 — Découper le socle en shared, androidApp et webApp

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le dépôt doit compiler rapidement sur Windows tout en préparant Android, iOS et PWA.

## Options envisagées

- **Un seul module** : simple, mais les responsabilités se mélangent.
- **Modules par plateforme seulement** : clair côté hôtes, mais le partage devient faible.
- **`shared` + hôtes minces** : domaine, data et présentation partagés; hôtes limités au bootstrap.

## Décision

Nous retenons `shared`, `androidApp` et `webApp`, avec `iosApp` documenté pour intégration Xcode ultérieure.

## Conséquences

**Positives**
- Le domaine reste commun et pur.
- Les hôtes restent minces.

**Négatives / compromis assumés**
- La compilation iOS complète n'est pas garantie sur Windows.

**À revoir si**
- Le build PWA ou iOS impose un découpage plus spécialisé.
