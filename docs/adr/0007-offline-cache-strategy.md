# 0007 — Démarrer offline-first par contrats et files locales

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le PRD cible des connexions intermittentes et exige cache du mur, bannière offline et file locale Like/Favori.

## Options envisagées

- **Online-only** : simple, mais contraire au produit.
- **Offline complet immédiat** : trop large pour le socle.
- **Contrats offline-first progressifs** : cache lecture et files locales par action.

## Décision

Nous retenons une stratégie offline-first progressive, commencée par des contrats de domaine et implémentée par feature.

## Conséquences

**Positives**
- Les flows critiques prévoient l'intermittence dès le départ.
- Les features peuvent ajouter leur cache sans refonte.

**Négatives / compromis assumés**
- La résolution de conflits détaillée sera décidée par feature.

**À revoir si**
- Les tests terrain montrent que le cache lecture ne suffit pas au MVP.
