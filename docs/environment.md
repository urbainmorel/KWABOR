# Environnements KWABOR

> Vue courte des tiers et fichiers de configuration. Le contrat exhaustif reste dans
> [environment-configuration.md](environment-configuration.md).

## Tiers autorisés

| Tier | Usage | Artefacts |
| --- | --- | --- |
| `development` | Développement local | APK debug, Xcode Debug non distribué |
| `staging` | Qualification interne | APK staging, Xcode Staging |
| `production` | Artefacts Store | AAB release et archive iOS signée |

Les valeurs Supabase, Firebase et OAuth d'un tier ne doivent jamais être réutilisées dans un autre.
Une valeur de tier inconnue bloque le build.

## Sources locales

| Fichier | Chargé par | Statut Git |
| --- | --- | --- |
| `.env.example` | Personne automatiquement ; inventaire seulement | Versionné |
| `local.properties.example` | Modèle Android | Versionné |
| `local.properties` | Gradle Android | Ignoré |
| `iosApp/Kwabor/Config/Local.xcconfig.example` | Modèle iOS | Versionné |
| `iosApp/Kwabor/Config/Local.xcconfig` | Xcode local | Ignoré |
| `google-services.json` | Firebase Android | Ignoré |
| `GoogleService-Info.plist` | Firebase iOS | Ignoré/injecté |

## Catégories de configuration

### Valeurs client publiques

- URL et publishable key Supabase ;
- identifiants OAuth Google Android/iOS/serveur ;
- reversed client ID iOS ;
- identifiant de projet Firebase ;
- version et numéro de build.

Le caractère public n'autorise pas leur mélange entre tiers ni leur insertion dans un fichier qui
serait ensuite commité par erreur.

### Secrets et artefacts protégés

- mots de passe et keystore d'upload Android ;
- certificat, mot de passe et provisioning profile iOS ;
- secrets OAuth fournisseur ;
- clés service-role Supabase, secrets FedaPay et clés IA ;
- fichiers Firebase réels encodés pour les workflows de release.

Ces valeurs appartiennent aux GitHub Environments protégés ou au stockage privé du propriétaire.
Elles ne doivent jamais apparaître dans le code, les logs, les captures ou les artefacts publics.

## Démarrage local

- Android : fusionner `local.properties.example` dans `local.properties`.
- iOS : copier `Local.xcconfig.example` vers `Local.xcconfig` sur macOS.
- Un build sans Firebase réel reste possible ; Firebase est alors désactivé.
- Un build sans Supabase peut compiler mais expose volontairement l'état d'indisponibilité.
- Les fournisseurs Google/Apple réels exigent leur provisionnement dans le projet Supabase du même
  tier et les identités plateforme correspondantes.

## Configuration distante

Les GitHub Environments `staging` et `production` doivent être séparés, protégés et alimentés par le
propriétaire. Les workflows valident formats, bundle/application IDs et cohérence du projet Firebase
avant de construire.

À ce jour, le dépôt prépare ces contrats mais ne prouve pas que les projets fournisseurs KWABOR sont
créés ou connectés. Voir les blocages dans [PROJECT_STATE.md](../PROJECT_STATE.md).

## Règles de sécurité

- Ne jamais copier une valeur réelle dans `.env.example` ou les fichiers `*.example`.
- Ne jamais committer `local.properties`, `Local.xcconfig`, keystore, certificat, profil ou fichier
  Firebase.
- Ne pas afficher les valeurs sensibles dans une commande de diagnostic ou un rapport CI.
- Vérifier le tier, l'identité d'application et le project ID avant tout artefact distribuable.
- Exécuter `python -B tools/verify-repository-integrity.py` avant une release.

Référence complète : [configuration des environnements](environment-configuration.md).
