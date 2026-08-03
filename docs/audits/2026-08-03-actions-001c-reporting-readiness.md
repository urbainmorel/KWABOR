# Audit ACTIONS-001C2 — signalement d'une fiche

Date de vérification : 3 août 2026
Branche auditée : `codex/actions-001c-detail-deeplink`
Base auditée : `5f95d79`

## Verdict

Le signalement d'une fiche n'est pas implémenté. Le dépôt possède un canal sécurisé pour déclarer un
lieu absent, mais aucun contrat ne permet de signaler une fiche existante, de dédupliquer la demande,
de la persister et de l'envoyer dans une file de modération depuis Android ou iOS.

Ce lot est structurant : l'implémentation doit attendre la validation des décisions listées plus bas.

## Exigence vérifiée

- Le PRD exige cinq motifs : Spam, Inapproprié, Information erronée, Faux compte-avis et Autre.
- Le DESIGN affiche un champ libre lorsque le motif est Autre, sans préciser s'il est obligatoire.
- Le contenu reste visible pour le déclarant après envoi.
- Le DESIGN place Signaler dans une Share Sheet applicative, puis ouvre une modale dédiée.
- Le contrat analytique `report_submitted` existe déjà, mais il n'est pas encore émis.

Sources : [`PRD.md`](../../PRD.md), [`DESIGN.md`](../../DESIGN.md) et
[`ObservabilityModels.kt`](../../shared/src/commonMain/kotlin/com/kwabor/shared/domain/observability/ObservabilityModels.kt).

## État réel du dépôt

### Backend

- `report_status` est utilisé uniquement par `missing_place_reports`.
- `missing_place_reports` décrit un lieu absent ; il ne référence aucune fiche existante et ne porte
  aucun motif de signalement de contenu.
- Ses RLS et grants sont durcis, mais son modèle métier ne doit pas être détourné.
- Il n'existe ni `listing_reports`, ni motif typé, ni RPC de soumission, ni déduplication, ni file de
  signalements de contenu, ni règle de conservation après suppression de compte.
- La seule Edge Function configurée est `account-delete`.

### KMP, Android et iOS

- Aucun modèle domaine, repository data, état de formulaire ou mapping RPC de signalement n'existe.
- Aucun écran natif ne propose la modale C3, son envoi, son retry ou son message de succès.
- Les avis n'existent pas encore en base ; leur signalement et leur seuil de trois signalements
  dépendent donc de `REVIEWS-001`.

## Tranche recommandée

Créer `ACTIONS-001C2 — signaler une fiche`, limitée à une cible disposant déjà d'une vraie clé
étrangère :

1. Ouvrir Signaler depuis la fiche via une Share Sheet applicative.
2. Exiger un compte selon la décision produit ci-dessous.
3. Choisir un des cinq motifs ; selon la décision produit, exiger pour Autre un texte nettoyé et borné.
4. Appeler un RPC PostgreSQL sécurisé dont l'identité provient exclusivement de `auth.uid()`.
5. Persister une ligne dans `listing_reports`, liée par FK à une fiche publiée.
6. Rendre les retries idempotents et limiter un signalement actif par déclarant et fiche.
7. Afficher le succès uniquement après confirmation serveur, sans masquer la fiche.
8. Émettre `report_submitted` une seule fois, sans motif, texte libre ni PII.
9. En cas d'absence de réseau, conserver le brouillon et proposer Réessayer, sans file locale.

Le serveur doit refuser tout DML client direct. La table garde RLS activé, la lecture et la modération
restent réservées aux Admins vérifiés, et un index `(status, created_at)` alimente la future file.
Un RPC transactionnel suffit pour cette première tranche ; une Edge Function ne devient nécessaire
que si un CAPTCHA, une attestation d'appareil ou un service externe de limitation de débit est retenu.

## Invariants à prouver

- aucune lecture, écriture ou exécution anonyme non prévue ;
- identité du déclarant non falsifiable et colonnes d'autorité non forgeables ;
- cible inexistante ou non publiée refusée ;
- cinq motifs seulement, avec détail Autre nettoyé et borné ;
- retry idempotent, double tap coalescé et concurrence testée ;
- limitation de débit transversale par compte et fenêtre temporelle, avec seuil configurable ;
- déclarant incapable de lire ou traiter la file Admin ;
- brouillon conservé après échec et résultat obsolète ignoré après changement de fiche ;
- parité Android/iOS, accessibilité et aucun succès anticipé ;
- analytics après succès uniquement, sous consentement, sans texte utilisateur.

## Décisions à valider avant implémentation

| Décision | Recommandation technique | Pourquoi une validation est requise |
| --- | --- | --- |
| Accès | Compte authentifié et onboarding terminé | Réduit le spam, mais le mur souple ne classe pas explicitement Signaler parmi les actions exigeant un compte. |
| Première cible | Fiches uniquement | Les avis et leur table n'existent pas ; une table polymorphe sans FK fragiliserait l'intégrité. |
| Motif Autre | Détail non vide après nettoyage | Le DESIGN affiche le champ, mais ne dit pas explicitement qu'il est obligatoire. |
| Seuil fiche | Mise en file sans masquage automatique | Le PRD fixe trois signalements pour un avis, mais aucun nombre pour une fiche. |
| Suppression du compte | Pseudonymiser le déclarant et conserver une preuve pour une durée juridiquement validée | Une suppression immédiate détruit l'historique de modération ; une durée arbitraire serait une décision juridique. |

La Share Sheet doit être un composant Kwabor : Android et iOS ne peuvent pas injecter de façon fiable
une action personnalisée Signaler dans toutes les feuilles de partage système.

## Hors de cette tranche

- partage HTTPS public, App Links et Universal Links, dépendants d'un domaine officiel ;
- signalement des avis, dépendant de `REVIEWS-001` ;
- signalement des posts sociaux, dépendant de leurs parcours complets ;
- masquage automatique, scoring, recours et journal opérateur, suivis dans `MOD-001` et
  `MOD-OPS-001`.

## Validation de l'audit

Audit strictement en lecture de `PRD.md`, `DESIGN.md`, `BACKLOG.md`, des migrations/RLS/grants,
des fonctions Supabase, du contrat d'observabilité et des écrans détail Android/iOS. Aucune migration,
fonction serveur, UI, CI ou ressource distante n'a été modifiée ou exécutée pendant cet audit.
