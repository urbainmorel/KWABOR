# 0005 — Encapsuler Supabase derrière data et imposer RLS

- **Statut** : accepté
- **Date** : 2026-07-02
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Kwabor manipule rôles, fiches, UGC, paiements et contenus sponsorisés. Les droits ne peuvent pas dépendre de l'UI.

## Options envisagées

- **Contrôles côté client** : insuffisant.
- **Backend custom complet** : flexible, mais plus coûteux au lancement.
- **Supabase avec RLS** : PostgreSQL et politiques d'accès côté serveur.

## Décision

Nous retenons Supabase avec RLS, et le client Supabase reste encapsulé dans la couche `data`.

## Conséquences

**Positives**
- Les règles rôle × classe de fiche sont enforceables côté serveur.
- Le domaine ne dépend pas du SDK Supabase.

**Négatives / compromis assumés**
- Les migrations et politiques RLS demandent revue sécurité dédiée.

**À revoir si**
- Les besoins métier dépassent les capacités RLS ou nécessitent un backend applicatif plus lourd.
