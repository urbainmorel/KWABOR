# 0011 — Persistance locale structurée avec Room KMP

- **Statut** : accepté
- **Date** : 2026-07-14
- **Décideurs** : Produit Kwabor, Architecture, Data
- **Complète** : ADR-0007

## Contexte et problème

ADR-0007 impose une stratégie offline progressive, mais ne choisit pas le moteur persistant. La V1 doit conserver Explore, favoris, notifications, brouillons et opérations en attente sur Android et iOS, avec migrations testables et comportement déterministe sur réseau intermittent.

Une queue uniquement en mémoire ne survit ni à un redémarrage ni à une éviction du processus. Deux moteurs SQL distincts augmenteraient aussi le risque de divergence entre Android et iOS.

## Options envisagées

- **Room KMP dans `shared`** : schéma, entités et DAO partagés ; builders de base minces par plateforme.
- **SQLDelight** : solution KMP mature, mais introduit un second modèle de génération et ne suit pas le socle Jetpack déjà retenu.
- **Stockages natifs séparés** : contrôle maximal, au prix de deux schémas, deux jeux de migrations et deux implémentations de synchronisation.

## Décision

Nous retenons Room KMP comme stockage local structuré unique parce qu'il prend officiellement en charge Android et iOS, permet de partager le schéma et les DAO, et limite les différences plateforme à la création du chemin de base.

Les règles suivantes s'appliquent :

- Room vit dans la couche `data` de `shared` ; le domaine n'importe aucun type Room ou SQLite.
- Les entités locales restent distinctes des DTO Supabase et des modèles domaine, avec mappers explicites.
- Le driver SQLite embarqué est utilisé pour éviter les divergences d'implémentation plateforme.
- Le schéma Room exporté est versionné et chaque migration est testée, sans fallback destructif en production.
- Les builders Android/iOS sont injectés par Koin et constituent les seuls points `expect`/`actual` nécessaires.
- Room conserve les données structurées : cache Explore, favoris, notifications, brouillons, versions de conflit et outbox.
- DataStore KMP reste réservé aux préférences légères ; aucun état métier synchronisable n'y est stocké.
- L'outbox coalesce Like/Favori vers le dernier état souhaité et conserve une clé d'idempotence stable.
- En conflit de brouillon, les deux versions sont préservées jusqu'à résolution explicite.

La documentation Room KMP indique un support à partir de Room 2.7.0 et recommande un SQLite embarqué pour éviter les incohérences entre plateformes : [Set up Room Database for KMP](https://developer.android.com/kotlin/multiplatform/room).

## Conséquences

**Positives**

- Une seule sémantique de cache et de synchronisation sur Android/iOS.
- Migrations locales reproductibles et testables.
- Reprise après redémarrage pour les actions et brouillons offline.
- UI SwiftUI et Compose alimentées par les mêmes contrats de données, sans partager l'UI.

**Négatives / compromis assumés**

- KSP doit compiler pour chaque cible KMP.
- Les migrations Room deviennent un gate de release.
- Certaines API Room Android-only ne sont pas disponibles en code commun et ne doivent pas être contournées par une abstraction spéculative.

**À revoir si**

- Room KMP perd le support production d'une cible mobile retenue ;
- une limite vérifiée empêche les migrations non destructives ou le volume local V1 ;
- les temps de compilation KSP deviennent incompatibles avec les gates CI, après mesure et optimisation.
