# BACKLOG.md — Kwabor

## En cours

- [x] FND-001 — Créer le scaffold KMP minimal compilable.
- [x] FND-002 — Formaliser les ADR fondateurs.
- [x] FND-003 — Installer le shell Compose partagé.
- [x] FND-007 — Créer la PR `foundation/app-foundations`.
- [x] FND-004 — Ajouter les contrats repositories du catalogue.
- [x] FND-005 — Préparer les migrations Supabase initiales avec RLS.
- [x] MOB-001 — Acter le cadrage mobile-only, iOS SwiftUI, CI macOS et rôles d'équipe vérifiée.
- [x] MOB-002 — Supprimer proprement `webApp` et retirer la cible Web/PWA du build.
- [x] IOS-001 — Créer l'hôte iOS SwiftUI et l'intégration du framework `shared`.
- [x] CI-001 — Ajouter un job GitHub Actions macOS qui compile iOS en simulateur sans signature.
- [x] CI-002 — Vérifier la CI macOS après push et corriger le build Xcode si nécessaire.
- [x] DATA-TEAM-001 — Créer les migrations Supabase équipes, membres, invitations, budgets et tests RLS.
- [x] DOMAIN-TEAM-001 — Ajouter les modèles domaine et contrats repository des organisations vérifiées.
- [x] DATA-TEAM-002 — Implémenter les DTO et repository data des organisations vérifiées.
- [x] DATA-TEAM-003 — Brancher `OrganizationDataSource` sur Supabase PostgREST/RPC.

## Ensuite

- [ ] DATA-CATALOG-001 — Brancher les repositories catalogue sur Supabase PostgREST.
- [ ] FND-006 — Ajouter les previews UI et tests de design system.
- [ ] AUTH-FOUNDATION-001 — Préparer la session auth partagée et le stockage sécurisé des tokens.

## À ne pas faire maintenant

- Écrans Explore complets.
- Auth réelle.
- Paiement Mobile Money.
- Assistant IA.
- Flux social complet.
- Web/PWA, sauf nouvelle ADR explicite.
