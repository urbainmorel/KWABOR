# KWABOR

> Guide visuel et intelligent du Bénin, livré exclusivement sur Android et iOS.

| Élément | État actuel |
| --- | --- |
| Livraison | V1 en construction — **non déployable en production à ce jour** |
| Android | Compose Multiplatform, API 26 minimum, API 36 cible |
| iOS | SwiftUI natif, iOS 17 minimum |
| Code partagé | Kotlin Multiplatform, Koin, Room KMP et DataStore |
| Backend | Supabase/PostgreSQL, RLS, RPC et Edge Functions |
| Autres clients | Aucun client Web, PWA, WASM ou Desktop |

## Finalité du produit

KWABOR aide les habitants et visiteurs à découvrir les lieux, établissements, événements et guides
du Bénin. L'expérience cible est photo-first, adaptée aux réseaux intermittents et aux appareils
Android modestes, avec une interface Android Compose et une interface iOS SwiftUI native.

## Ce qui fonctionne déjà

- introduction vidéo embarquée, onboarding et authentification mobile ;
- catalogue Explore offline-first, pagination, villes, interactions Like/Favori et fiches détail ;
- actions externes sûres dans une PR brouillon ; découverte des guides et liens internes vers une
  fiche implémentés localement, avec qualifications backend/iOS/appareils encore ouvertes ;
- backend Supabase avec migrations, RLS, contrats catalogue et tests pgTAP ;
- chaînes de qualité Android/KMP et construction iOS simulateur en CI.

Le périmètre complet du PRD n'est pas encore livré. Les avis, la recherche complète, le Social, les
notifications, les parcours B2B/paiement, l'IA et plusieurs preuves de production restent ouverts.
Consulter [l'avancement V1](docs/V1-PROGRESS.md) avant toute décision de release.

## Démarrage rapide Android

Depuis la racine du dépôt, avec JDK 21 et Android SDK 36 :

```powershell
if (-not (Test-Path local.properties)) { Copy-Item local.properties.example local.properties }
.\gradlew.bat check :androidApp:assembleDebug --no-daemon --console=plain
```

Le fichier `local.properties` est ignoré par Git. S'il existe déjà, conserver son éventuel `sdk.dir`
et fusionner uniquement les clés KWABOR. Un build local sans valeurs Supabase peut compiler,
mais il n'offre pas un parcours connecté exploitable. Renseigner uniquement des valeurs publiques du
tier choisi en suivant le [guide de configuration](docs/environment-configuration.md).

Pour iOS, macOS et Xcode sont obligatoires. Voir le [guide d'installation](docs/setup.md).

## Carte du dépôt

```text
androidApp/   Application Android et UI Compose
iosApp/       Hôte iOS et UI SwiftUI native
shared/       Domaine, data, présentation et composition KMP
supabase/     Migrations, seeds, tests pgTAP et Edge Functions
docs/         Architecture, exploitation, ADR, audits et suivi V1
tools/        Vérificateurs et scripts de preuve reproductibles
```

## Documentation

| Besoin | Document |
| --- | --- |
| Choisir le bon document | [Index documentaire](docs/index.md) |
| Installer et construire localement | [Setup](docs/setup.md) |
| Comprendre les frontières du système | [Architecture](docs/architecture.md) |
| Comprendre les données et migrations | [Modèle de données](docs/data-model.md) |
| Exécuter les validations | [Tests et qualité](docs/testing.md) |
| Configurer les tiers et fournisseurs | [Environnements](docs/environment.md) |
| Préparer les artefacts et releases | [Déploiement](docs/deployment.md) |
| Contribuer sans dégrader les invariants | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Règles essentielles

- Android et iOS sont les seuls clients applicatifs.
- Le domaine Kotlin reste pur ; Supabase, Room et SDK plateforme restent hors du domaine.
- Les droits sont imposés côté Supabase/RLS, jamais seulement par l'interface.
- Les prix sont saisis et stockés en XOF.
- La vidéo d'introduction est embarquée : tout changement de ses octets exige une nouvelle version
  Android/iOS distribuée via les Stores.
- Aucun secret, certificat ou fichier fournisseur ne doit entrer dans Git.

Commencer par [docs/index.md](docs/index.md), puis vérifier [PROJECT_STATE.md](PROJECT_STATE.md) et
[BACKLOG.md](BACKLOG.md) pour l'état exact de la branche courante.
