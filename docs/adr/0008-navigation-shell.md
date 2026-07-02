# 0008 — Poser une navigation racine plate à cinq entrées

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le DESIGN impose une bottom nav plate : Accueil, Social, Ajouter, Notifications, Profil.

## Options envisagées

- **Navigation libre par feature** : risque de doublons.
- **Router complet immédiat** : prématuré.
- **Shell racine partagé** : ancre les destinations sans implémenter les flows.

## Décision

Nous retenons un shell racine Compose partagé avec cinq destinations placeholder.

## Conséquences

**Positives**
- Les futures features ont un point d'ancrage commun.
- La navigation respecte le DESIGN dès le socle.

**Négatives / compromis assumés**
- Les destinations ne contiennent pas encore les écrans réels.

**À revoir si**
- Les contraintes plateforme imposent une navigation hôte différente.
