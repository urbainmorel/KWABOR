# 0002 — Retenir KMP, Compose Multiplatform et Supabase comme socle

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —
- **Remplacé partiellement par** : [ADR-0010](0010-mobile-only-swiftui-team-access.md) pour les cibles client et l'UI iOS.

## Contexte et problème

Kwabor ciblait plusieurs clients avec une base produit commune, un design system partagé, un backend rapide à opérer et des règles d'accès fortes.

Depuis ADR-0010, la cible produit V1 est Android/iOS uniquement. Compose Multiplatform reste retenu pour Android et les fondations UI existantes ; l'interface iOS devient SwiftUI native.

## Options envisagées

- **Apps natives séparées** : contrôle maximal, mais duplication forte.
- **Framework mobile non natif** : vitesse initiale, mais risque sur expérience native et offline.
- **KMP + Compose + Supabase** : partage métier/UI initial, backend managé et RLS.

## Décision

Nous retenons Kotlin Multiplatform, Compose Multiplatform et Supabase parce que ce trio correspond au PRD et limite la duplication. ADR-0010 précise que le partage UI ne s'applique plus à l'interface iOS finale, qui sera SwiftUI.

## Conséquences

**Positives**
- Domaine, contrats, data et primitives transverses partagés entre plateformes.
- UI Android appuyée sur Compose Multiplatform.
- Supabase fournit Auth, PostgreSQL, Storage, Realtime et RLS.

**Négatives / compromis assumés**
- L'hôte iOS doit être validé sur macOS.
- L'UI iOS native SwiftUI crée une surface de maintenance séparée.

**À revoir si**
- Android/iOS cesse d'être le périmètre produit validé.
