# PLANS.md — Exécution contrôlée Kwabor

## Règle d'exécution

Une tâche à la fois. Aucun agent ne démarre une deuxième tâche tant que son livrable n'est pas vérifié.

## Séquence de fondation

1. Verrouillage Git et branche de fondation.
2. ADR fondateurs.
3. Scaffold KMP minimal.
4. Primitives domaine.
5. Shell UI commun.
6. Validation `./gradlew check`.
7. PR de fondation.

## Zones à haut risque

- Authentification.
- RLS Supabase.
- Paiements.
- Offline/synchronisation.
- Upload média.
- IA et clés provider.

Ces zones demandent un plan dédié avant implémentation.

## Plan FND-005 — Supabase migrations initiales et RLS

**Agent responsable** : Data/Supabase.

**Objectif atomique** : créer le premier socle SQL versionné Supabase pour les référentiels, fiches, UGC, interactions, notifications, revendications, signalements, campagnes et paiements, avec RLS explicite et seeds Bénin minimaux.

**Livrables**

- Initialisation locale Supabase sans secret commité.
- Migration SQL initiale créée via `supabase migration new`.
- RLS activée sur toutes les tables `public`.
- Grants explicites `anon` / `authenticated` cohérents avec la Data API.
- Seeds minimaux Bénin pour villes, catégories et fiches publiées de test.
- Tests pgTAP ciblant les contraintes et politiques critiques.

**Règles de sécurité**

- Aucun `service_role`, secret, endpoint projet réel ou clé API dans le dépôt.
- Aucune autorisation basée sur `user_metadata`.
- Les rôles applicatifs sont lus dans `public.user_roles`, jamais depuis des claims modifiables par l'utilisateur.
- `listings.status = 'publie'` est la seule lecture publique catalogue.
- Les écritures `listings` restent limitées par rôle vérifié et `listing_class`.
- `social_posts.listing_id` reste obligatoire.
- Les claims sont impossibles sur les fiches patrimoniales.
- Les paiements restent des enregistrements serveur : pas de validation de succès côté client.

**Validation**

- `supabase db reset` si l'environnement Docker local le permet.
- `supabase test db` pour les tests pgTAP.
- `./gradlew.bat check`.
- `git diff --check`.
