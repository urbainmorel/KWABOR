# 0002 — Retenir KMP, Compose Multiplatform et Supabase comme socle

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Kwabor cible Android, iOS et PWA avec une base produit commune, un design system partagé, un backend rapide à opérer et des règles d'accès fortes.

## Options envisagées

- **Apps natives séparées** : contrôle maximal, mais duplication forte.
- **Framework web mobile** : vitesse initiale, mais risque sur expérience native et offline.
- **KMP + Compose + Supabase** : partage métier/UI, backend managé et RLS.

## Décision

Nous retenons Kotlin Multiplatform, Compose Multiplatform et Supabase parce que ce trio correspond au PRD et limite la duplication.

## Conséquences

**Positives**
- Domaine et UI partagés entre plateformes.
- Supabase fournit Auth, PostgreSQL, Storage, Realtime et RLS.

**Négatives / compromis assumés**
- L'hôte iOS doit être validé sur macOS.
- La cible PWA/Wasm doit rester surveillée côté performance.

**À revoir si**
- Compose/Wasm ne tient pas les objectifs P75 du mur sur appareils bas de gamme.
