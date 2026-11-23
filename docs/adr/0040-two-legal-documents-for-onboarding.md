# ADR-0040 — Deux documents juridiques à l’inscription

- **Statut** adopté
- **Date** 2026-11-23
- **Décideur** mainteneur
- **Met à jour** [ADR-0026](0026-streamlined-registration-and-contextual-permissions.md) pour les acceptations à l’inscription.

Le profil demande deux acceptations séparées, les conditions d’utilisation et la politique de confidentialité. Android et iOS affichent les révisions actives avec leurs liens. La licence de contenu communautaire est retirée du périmètre produit.

Supabase exige exactement une révision valide de chaque type et enregistre les deux acceptations dans la transaction de finalisation. La signature de la RPC tolère l’ancien troisième argument pendant la mise à jour des clients. Les acceptations existantes sont des données historiques.
