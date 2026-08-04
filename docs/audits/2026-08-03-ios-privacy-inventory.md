# Inventaire de confidentialité iOS — IOS-PRIVACY-001B1

Date d'audit : 3 août 2026
Base de travail : `35ed9cf` sur `codex/sec-001f-account-delete-step-up`, avec changements locaux audités
Portée : hôte SwiftUI, framework KMP consommé par iOS, dépendances iOS, manifest hôte et préparation App Store.

## Conclusion

Le manifest hôte était incomplet : il omettait le nom, l'adresse e-mail et l'identifiant utilisateur,
et classait les interactions produit comme non liées alors que les likes et favoris sont stockés avec
`auth.uid()`. Ces éléments factuels sont corrigés et verrouillés par le vérificateur d'intégrité.

Cette correction ne constitue pas une validation App Store complète. Le rapport de confidentialité de
l'archive Release, les manifests réellement agrégés, les réglages fournisseurs, la rétention et le
questionnaire App Store Connect restent à contrôler sur macOS avec le propriétaire.

## Données de l'hôte confirmées

| Donnée Apple | Traitement livré | Liée | Finalité retenue dans le manifest hôte | Preuves principales |
|---|---|---:|---|---|
| `Name` | Prénom/nom saisis ou proposés par Apple/Google, puis enregistrés dans le profil Supabase | oui | fonctionnalité | `SupabaseAuthRegistrationModels.kt`, `FederatedSignIn.swift` |
| `Email Address` | OTP, connexion, récupération et identité du compte Supabase | oui | fonctionnalité | `SupabaseAuthSessionDataSource.kt`, `SupabaseAuthRegistrationDataSource.kt` |
| `User ID` | Identité Supabase, session Keychain et rattachement des données | oui | fonctionnalité | `AuthSessionManager.kt`, `IosSecureAuth.kt` |
| `Coarse Location` | Coordonnée kilométrique traitée localement pour proposer une ville ; seul l'ID ville est envoyé et stocké | oui pour la ville de profil | fonctionnalité | `ApproximateLocationProvider.swift`, `IosRegistrationController.kt` |
| `Product Interaction` | Likes/favoris persistés avec l'utilisateur ; événements intro montrée/passée après opt-in Analytics | oui | fonctionnalité et analytics | `SupabaseCatalogDataSource.kt`, migration `catalog_interactions`, `OnboardingCoordinator.swift` |

La coordonnée ponctuelle n'est ni transmise ni persistée par Kwabor. Les préférences locales, caches
Room, états d'intro et consentements qui ne quittent pas l'appareil ne sont pas des données
« collectées » au sens de la fiche App Store, mais leurs accès à `UserDefaults` restent couverts par
la Required Reason API `CA92.1`.

## Traitements complémentaires constatés

- jetons et nonces Apple/Google transmis à Supabase Auth lors d'une action explicite ;
- session Supabase chiffrée dans le Keychain avec une classe d'accessibilité liée à l'appareil ;
- acceptations des révisions juridiques enregistrées avec le compte ;
- journaux d'audit Supabase Auth automatiquement capturés pour tous les événements
  d'authentification, avec notamment identifiant utilisateur, adresse IP et user-agent ; les logs
  API couvrent aussi les requêtes REST/GraphQL et leurs métadonnées réseau autorisées ;
- requêtes d'images vers les hôtes média, qui voient au minimum les métadonnées réseau usuelles ;
- aucun accès applicatif trouvé aux Contacts, à la caméra, au micro, à ATT ou à l'IDFA ;
- aucun enregistrement de token APNs/FCM trouvé dans la cible iOS actuelle.

## SDK et dépendances

La cible iOS sélectionne les produits suivants :

- Firebase Apple SDK `12.16.0` : Analytics Core sans IDFA, Core, Crashlytics, Performance, Remote Config et Firebase Installations explicitement lié ;
- Google Sign-In `9.0.0` ;
- framework KMP statique avec Supabase Kotlin `3.6.0`, Ktor Darwin `3.4.3`, Room `2.8.4`,
  DataStore `1.2.1`, SQLite bundled `2.6.2` et leurs transitives.

Les collectes Firebase sont désactivées par défaut dans `Info.plist`. Sur iOS, la configuration
Firebase elle-même est différée jusqu'à la validation d'un compte propriétaire, d'au moins un choix
actif ou d'une maintenance FID persistée sans collecte ; Performance reçoit son état avant
`FirebaseApp.configure` et son instrumentation automatique reste désactivée avant configuration et
au runtime dans la lignée actuellement supportée. Le choix iOS est enregistré en un
élément Keychain atomique avec une empreinte SHA-256 du propriétaire, et les anciennes clés
`UserDefaults` sont purgées après persistance d'un marqueur de neutralisation fail-closed. Tant que
les anciens overrides Firebase ne sont pas neutralisés, Firebase ne démarre pas avec un consentement
partiel susceptible de réactiver une autre catégorie. Crashlytics reste en collecte automatique
désactivée et ses rapports ne sont envoyés manuellement qu'au lancement suivant avec un consentement
diagnostics restauré pour le même compte, sans purge attendue. Nouvel accord et révocation suivent un
ordre de persistance crash-safe différent ; un marqueur Keychain reste jusqu'à ce que le check unique
du processus ne trouve aucun rapport, avec une action `deleteUnsentReports()` toujours fournie. Toute
nouvelle purge après consommation de ce check attend le lancement suivant afin qu'un rapport actif
antérieur à la révocation ne puisse pas être envoyé après un réaccord. Cette attente coupe seulement
les diagnostics ; les autres choix indépendants conservent leurs propres portes. Les réglages
utilisateur Android et iOS permettent de retirer séparément chaque choix ; la révocation coupe
immédiatement les portes applicatives et arrête les nouveaux fetch/listeners Remote Config. iOS
persiste aussi une transaction Firebase Installations typée avec l'état final du consentement,
suspend toutes les collectes jusqu'au succès réseau et réessaie au lancement ou au retour au premier
plan. Le nettoyage d'une première installation reconnue est l'exception locale : il retire le
marqueur survivant avant toute configuration, car l'ancien FID du sandbox désinstallé n'est plus
adressable. Le succès client démarre la suppression fournisseur, dont Firebase borne l'achèvement
dans les systèmes actifs et de sauvegarde à 180 jours. L'API ne garantit pas l'annulation d'un
upload Crashlytics déjà commencé sous un ancien override automatique.
Un marqueur d'override absent ou corrompu déclenche purge diagnostics puis suppression FID avant toute
restauration. Lorsque Firebase est déjà configuré et vient d'être désactivé, la phase de redémarrage
attendu est écrite directement dans le Keychain, sans phase intermédiaire vulnérable à un crash.
Une déconnexion, une annulation d'inscription après création de session ou une suppression de compte
révoque également les trois choix pour empêcher leur transmission à un autre compte du même appareil.
Une première installation reconnue efface aussi le consentement et le marqueur de migration qui
peuvent survivre dans le Keychain avant de restaurer une session Auth.

Deux sources différentes sont utilisées ci-dessous et ne doivent pas être confondues : les manifests
de confidentialité officiels livrés dans les SDK décrivent leurs déclarations embarquées, tandis que
le guide Firebase « App Store data collection » décrit les comportements possibles par produit. Ce
guide aide à préparer la fiche App Store, mais ne remplace pas l'inventaire des manifests réellement
agrégés dans l'archive. Les lignes Firebase du tableau viennent du guide ; la ligne Google Sign-In
vient de son manifest officiel `9.0.0`.

| Composant | Source du signal | Signal officiel pertinent | État avant publication |
|---|---|---|---|
| Crashlytics | guide Firebase | crash, état applicatif, appareil/OS et données ajoutées par le développeur | confirmer dans le Privacy Report et avec le réglage production |
| Performance | guide Firebase | IP/géographie approximative, performances app/réseau et informations appareil | confirmer sur appareil après opt-in |
| Remote Config | guide Firebase | pays, langue, fuseau, OS, identifiant d'app Firebase et bundle ID | confirmer sur appareil après opt-in |
| Firebase Installations / transport | guide Firebase | identifiant d'installation, user-agent Firebase et diagnostics de transport selon les produits liés | confirmer les produits et manifests présents dans l'archive |
| Google Sign-In | manifest officiel du SDK `9.0.0` | nom, e-mail, téléphone, localisation approximative, user/device ID et usage | rapprocher des scopes réellement demandés et du rapport Xcode |
| KMP statique | inspection des artefacts locaux | aucun manifest trouvé pour Supabase/Ktor/Room/SQLite/DataStore | inspecter Required Reason APIs dans le XCFramework final |

Le lockfile résout également `google-ads-on-device-conversion-ios-sdk` `3.6.1` comme dépendance
transitive, alors que le projet ne référence directement aucun de ses produits. Sa présence dans
`Package.resolved` ne prouve donc pas qu'il est lié au binaire ; cette absence ou présence doit être
confirmée dans l'archive Release. De même, le lockfile présent ne suffit pas à prouver la chaîne finale
Google Sign-In. Xcode doit résoudre à nouveau les packages avant l'archive et le verrou résultant doit
être revu.

## Mapping App Store de départ

Le questionnaire doit au minimum partir des catégories hôte certaines : nom, adresse e-mail,
identifiant utilisateur, localisation approximative et interactions produit, toutes liées au compte
pour le comportement global livré. La collecte des journaux Supabase Auth est elle aussi certaine ;
leur traduction exacte en catégories App Store, finalités et caractère lié reste une décision du
propriétaire fondée sur le traitement production. Les diagnostics, données de performance,
identifiants appareil, autres données d'usage et métadonnées des autres services doivent être ajoutés
selon le Privacy Report et les réglages production réels.

`NSPrivacyTracking=false` reste cohérent avec le code : aucun ATT/IDFA n'est utilisé, l'IDFV Analytics
et la personnalisation publicitaire sont désactivés. Cette intention doit encore être confirmée par
les manifests agrégés, les réglages fournisseurs et l'absence de combinaison publicitaire avec des
données tierces.

## Décisions propriétaire encore requises

1. Fixer le SHA, le numéro de build et les fonctionnalités exactes de la soumission.
2. Confirmer que Firebase production est embarqué et quelles capacités sont réellement activables.
3. Valider région, partage, rétention, suppression, finalités et liaison App Store des données
   Firebase, Google Sign-In et Supabase, y compris les journaux Auth automatiques, IP et logs API.
4. Confirmer qu'aucune donnée n'est combinée pour publicité ciblée, mesure publicitaire ou courtage.
5. Confirmer la finalité éventuelle de personnalisation de la ville de profil et le traitement des
   interactions avec du contenu sponsorisé.
6. Fournir l'URL publique approuvée de la politique de confidentialité et rendre cette politique
   facilement accessible depuis les Paramètres. Aucune URL ne doit être inventée dans le client.
7. Fournir ou exporter les réponses App Store Connect existantes et nommer la personne autorisée à
   les publier.

Les surfaces non livrées dans le binaire audité — Social/UGC, upload, avis, signalement, recherche
texte, token push, paiement et formulaires promoteur complets — imposeront un nouvel inventaire lors
de leur ajout.

## Preuves macOS et App Store restantes

Sur la révision exacte candidate :

1. exécuter `xcodebuild -resolvePackageDependencies` et contrôler `Package.resolved` ;
2. construire les XCFrameworks puis archiver la configuration Release avec les fichiers production ;
3. inventorier tous les `PrivacyInfo.xcprivacy` présents dans l'archive ;
4. générer le Privacy Report dans Xcode Organizer ;
5. inspecter l'hôte et le framework statique avec `nm`/`otool` pour les Required Reason APIs non
   déclarées, notamment File Timestamp, System Boot Time et Disk Space ; l'ancien appel direct
   `attributesOfItem(atPath:)` du marqueur promoteur a été supprimé de l'hôte ;
6. tracer le réseau sur appareil après installation neuve, consentements refusés, puis chaque opt-in ;
7. rapprocher code, rapport, traitements backend, politique publiée et questionnaire App Store Connect.

## Sources officielles

- Apple : [App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/),
  [Privacy manifests](https://developer.apple.com/documentation/bundleresources/describing-data-use-in-privacy-manifests),
  [Manage App Privacy](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy),
  [App Review Guidelines §5.1](https://developer.apple.com/app-store/review/guidelines/).
- Firebase : [App Store data collection](https://firebase.google.com/docs/ios/app-store-data-collection),
  [gestion et suppression des installations](https://firebase.google.com/docs/projects/manage-installations).
- Google Sign-In : [manifest officiel 9.0.0](https://github.com/google/GoogleSignIn-iOS/blob/9.0.0/GoogleSignIn/Sources/Resources/PrivacyInfo.xcprivacy).
- Supabase : [Auth audit logs](https://supabase.com/docs/guides/auth/audit-logs),
  [platform logs](https://supabase.com/docs/guides/telemetry/logs).
