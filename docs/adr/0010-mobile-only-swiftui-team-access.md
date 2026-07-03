# 0010 — Recentrer Kwabor sur Android/iOS, SwiftUI iOS et équipes vérifiées

- **Statut** : accepté
- **Date** : 2026-07-03
- **Décideurs** : Équipe, utilisateur sponsor produit
- **Remplace** : ADR-0003 pour les cibles de modules ; remplace partiellement ADR-0002 pour le partage UI

## Contexte et problème

Le cadrage initial préparait Android, iOS et Web-PWA avec Compose Multiplatform comme socle UI partagé.
La décision produit actuelle retire entièrement la version web du périmètre et cible uniquement Android et iOS.

Cette décision impose aussi de clarifier l'architecture iOS : l'interface iOS doit être native SwiftUI, tandis que le partage Kotlin Multiplatform reste centré sur le domaine, les contrats, la data, les use cases et les états exploitables par les deux plateformes.

Enfin, les comptes vérifiés ne sont pas toujours opérés par une seule personne. Les Admin Kwabor, institutions, promoteurs et établissements vérifiés doivent pouvoir travailler en équipe, avec des droits cumulatifs et contrôlés côté serveur.

## Options envisagées

- **Conserver Android/iOS/Web-PWA** : maximise la couverture, mais disperse l'effort de fondation et maintient une cible non prioritaire.
- **Partager toute l'UI Compose sur Android et iOS** : réduit la duplication UI, mais contredit la demande d'une interface iOS SwiftUI native.
- **Mobile-only avec Android Compose Multiplatform, iOS SwiftUI et shared KMP métier** : concentre la V1 sur les plateformes mobiles prioritaires et garde un partage fort sans imposer Compose à iOS.

## Décision

Nous retenons une cible V1 mobile-only : Android et iOS uniquement.

- Android utilise Compose Multiplatform pour l'interface.
- iOS utilise SwiftUI pour l'interface.
- `shared` reste Kotlin Multiplatform et expose domaine, data, contrats repositories, use cases, modèles d'état et utilitaires transverses.
- Le module `webApp` est hors scope et doit être supprimé dans la tâche suivante.
- Aucun nouveau travail ne doit cibler Web, PWA, Web Push, responsive desktop ou Kotlin/Wasm tant qu'une ADR ultérieure ne réouvre pas ce périmètre.

## CI iOS

La compilation iOS sera validée par GitHub Actions sur runner macOS.

Stratégie retenue :

- créer un hôte `iosApp` SwiftUI avec projet Xcode ;
- compiler `shared` pour iOS via les tâches Kotlin Multiplatform adaptées ;
- lancer un build simulateur avec `xcodebuild` et `CODE_SIGNING_ALLOWED=NO` pour la quality gate ;
- reporter la signature App Store/TestFlight à une étape release dédiée avec compte Apple Developer, certificat, provisioning profile et secrets GitHub chiffrés.

Références officielles à vérifier lors de l'implémentation CI :

- GitHub-hosted runners : https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- Secrets GitHub Actions : https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets
- Signature Xcode : https://docs.github.com/en/actions/how-tos/deploy/deploy-to-third-party-platforms/sign-xcode-applications

## Modèle d'équipes vérifiées

Les rôles de plateforme et les rôles d'équipe sont distincts.

Rôles de plateforme :

- `user`
- `guide`
- `institution`
- `promoteur`
- `admin_kwabor`

Rôles d'équipe dans une organisation vérifiée :

| Rôle équipe | Droits cumulés |
|---|---|
| **Modérateur** | Répondre uniquement aux avis/messages clients. |
| **Éditeur** | Droits Modérateur + modifier les fiches autorisées, ajouter médias, publier événements, créer/programmer des publicités avec budget alloué. |
| **Gestionnaire** | Droits Éditeur + gérer l'équipe, inviter/suspendre des membres, attribuer les rôles Éditeur/Modérateur, allouer les budgets autorisés. |
| **Propriétaire** | Droits Gestionnaire + contrôle absolu : budget global, paiements, moyens de paiement, suppression, transfert de propriété, paramètres critiques. |

Règles financières :

- Un Éditeur ne peut utiliser que le budget qui lui est alloué.
- Un Gestionnaire ne peut allouer que le budget autorisé par le Propriétaire.
- Seul le Propriétaire contrôle les moyens de paiement et le budget global.

Règles de sécurité :

- Les droits sont appliqués côté Supabase RLS, jamais seulement par masquage UI.
- Les organisations vérifiées possèdent ou stewardent les ressources critiques ; les membres agissent au nom de l'organisation.
- Un utilisateur peut appartenir à plusieurs organisations avec des rôles différents.
- Les invitations d'équipe doivent être traçables, expirables, acceptées explicitement et révocables.
- Les actions sensibles doivent être auditables.

## Conséquences

**Positives**
- Le scope V1 est plus net : production mobile Android/iOS, pas de dispersion Web.
- L'iOS peut respecter les conventions natives SwiftUI.
- Le partage KMP reste utile sans coupler l'UI iOS à Compose.
- Le modèle d'équipe prépare les vrais usages B2B sans multiplier les rôles métier publics.

**Négatives / compromis assumés**
- La suppression de `webApp` casse toute expérimentation Web/PWA existante.
- SwiftUI iOS implique une couche UI séparée et des tests iOS dédiés.
- La compilation iOS ne peut pas être validée localement sur ce poste Windows ; le runner macOS devient la source de validation.
- Le modèle équipe demande une migration Supabase et des tests RLS avant toute UI de gestion d'équipe.

**À revoir si**
- La V1 doit impérativement inclure une présence web publique.
- Compose Multiplatform iOS devient une exigence produit explicite.
- Les droits d'équipe deviennent trop fins pour le modèle cumulatif retenu.
