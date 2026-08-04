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

La baseline technique livrée par `OFFLINE-001` est volontairement compatible avec les trois cibles iOS encore actives :

- Room `2.8.4` ;
- SQLite bundled `2.6.2` ;
- DataStore `1.2.1` ;
- KSP `2.3.10`.

Room 3.0.1 et SQLite 2.7.0 ne publient plus de variante `iosX64`. Leur adoption est donc différée jusqu'au retrait formel de cette cible par une décision d'architecture distincte ; supprimer silencieusement `iosX64` pour obtenir une mise à niveau est interdit.

Les règles suivantes s'appliquent :

- Room vit dans la couche `data` de `shared` ; le domaine n'importe aucun type Room ou SQLite.
- Les entités locales restent distinctes des DTO Supabase et des modèles domaine, avec mappers explicites.
- Le driver SQLite embarqué est utilisé pour éviter les divergences d'implémentation plateforme.
- Le schéma Room exporté est versionné et contrôlé par `check`. La version 1 est une baseline de création et n'a pas de migration artificielle `0 → 1`. Dès la version 2, tous les anciens JSON sont conservés et chaque migration supportée est testée, sans fallback destructif en production.
- Les builders Android/iOS sont des fonctions minces de leurs source sets et sont injectés par Koin. Le seul contrat `expect` actuel est le `RoomDatabaseConstructor` dont KSP génère les `actual` pour Android et chaque cible iOS.
- Room conserve les données structurées : cache Explore, favoris, notifications, brouillons, versions de conflit et outbox.
- DataStore KMP reste réservé aux préférences légères ; aucun état métier synchronisable n'y est stocké.
- Le composition root est détenu une seule fois pendant la vie du processus mobile. Room, DataStore et leurs ressources sont créés paresseusement, fermés avec le graphe Koin et ne doivent jamais être dupliqués pour le même fichier dans un processus.
- L'outbox coalesce Like/Favori vers le dernier état souhaité et conserve une clé d'idempotence stable.
- En conflit de brouillon, les deux versions sont préservées jusqu'à résolution explicite.

La première baseline persiste uniquement :

- un cache Explore normalisé en trois tables — snapshots, fiches canoniques et positions par snapshot ;
- au maximum 50 fiches par snapshot et 64 snapshots récents, afin de borner le disque sans expirer arbitrairement le dernier mur disponible hors ligne ;
- la ville Explore, la locale livrée et la devise d'affichage dans DataStore.

Une corruption logique d'un snapshot Explore provoque l'éviction transactionnelle de ce seul snapshot et un cache miss ; les erreurs SQLite ou I/O ne sont pas avalées. La fraîcheur du contenu canonique utilise provisoirement l'instant de capture fourni par l'appelant avec une égalité déterministe « première écriture conservée ». Avant son branchement dans `EXPLORE-002`, les appels en vol d'une même requête doivent être dédupliqués. Si plusieurs requêtes concurrentes peuvent recevoir des révisions serveur différentes d'une même fiche, le RPC et le cache devront transporter `listings.updated_at` au lieu de déduire la fraîcheur de l'horloge appareil.

Le driver SQLite embarqué reste le driver de production Android/iOS. Les tests hôte Robolectric utilisent explicitement `AndroidSQLiteDriver`, car le runner JVM ne charge pas les bibliothèques Android `sqliteJni` empaquetées dans un APK.

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
- La base iOS vit dans Application Support. La politique de sauvegarde doit être réévaluée avant d'y ajouter médias volumineux ou données locales non régénérables ; la baseline actuelle n'y stocke aucun secret.

**À revoir si**

- Room KMP perd le support production d'une cible mobile retenue ;
- une limite vérifiée empêche les migrations non destructives ou le volume local V1 ;
- les temps de compilation KSP deviennent incompatibles avec les gates CI, après mesure et optimisation.
