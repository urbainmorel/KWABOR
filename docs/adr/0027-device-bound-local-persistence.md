# 0027 — Lier la persistance locale sensible à l’appareil

- **Statut** : accepté
- **Date** : 2026-08-04
- **Décideurs** : Produit Kwabor, Architecture, Sécurité
- **Complète** : ADR-0011
- **Remplace** : —

## Contexte et problème

Room contient actuellement un cache Explore régénérable, mais doit aussi accueillir des données
privées locales, notamment le miroir de l’historique de recherche. Android désactivait déjà les
sauvegardes avec `allowBackup=false`, sans règles explicites pour tous les transferts constructeur et
plaçait Room dans le domaine standard des bases. Sur iOS, `kwabor.db` vivait directement dans
`Application Support`, qui participe normalement aux sauvegardes, sans attribut de protection
vérifié par l’application.

La décision produit conserve durablement l’historique de recherche d’un compte afin d’afficher ses
récents dans Search et, lorsque la personnalisation est activée, d’alimenter le futur Assistant IA et
le fil organique. Cette durabilité doit venir de l’autorité serveur protégée par RLS, pas d’une copie
de base locale transférable entre identités ou appareils.

## Options envisagées

- **Conserver les sauvegardes plateforme par défaut** : facilite un changement d’appareil, mais peut
  transporter caches, historique privé et état de session hors du cycle de vie contrôlé par Kwabor.
- **Exclure seulement les fichiers connus aujourd’hui** : réduit la portée, mais oublie facilement un
  futur fichier, un journal SQLite ou une variante de sauvegarde constructeur.
- **Persistance locale liée à l’appareil** : exclure explicitement les données applicatives Android et
  isoler Room dans un dossier iOS protégé et non sauvegardé ; synchroniser séparément les données de
  compte qui doivent survivre.

## Décision

Nous retenons une persistance locale liée à l’appareil parce qu’elle sépare clairement la copie
locale privée de l’autorité serveur durable.

- Android place Room dans `noBackupFilesDir/KwaborRoom`, emplacement que le système exclut de chaque
  mode de sauvegarde, et invalide l’ancien cache v2 situé dans le domaine standard des bases. Une
  impossibilité de préparer ce chemin déclenche une base en mémoire au lieu d’ouvrir un stockage
  persistant non conforme ou de terminer l’application.
- Android conserve aussi `allowBackup=false` et référence deux politiques exhaustives :
  `fullBackupContent` pour Android 8–11 et `dataExtractionRules` pour Android 12+. Les neuf domaines
  applicatifs sont exclus du cloud et du transfert appareil-à-appareil. Aucun `BackupAgent` custom
  n’est autorisé. Aucun transfert Android↔iOS n’est configuré ; les données Room privées restent de
  toute façon hors de son périmètre grâce à `noBackupFilesDir`.
- iOS place Room dans `Library/Application Support/KwaborRoom`, marque ce dossier
  `NSURLIsExcludedFromBackupKey=true` et applique explicitement
  `NSFileProtectionCompleteUntilFirstUserAuthentication` au dossier ainsi qu’aux membres SQLite déjà
  présents. Une impossibilité d’appliquer la politique empêche l’ouverture de la base disque et
  bascule sur une base en mémoire, afin de conserver l’accès en ligne sans stockage non protégé.
- La base iOS v2 historique ne contient que du cache régénérable. Ses fichiers à l’ancien emplacement
  sont supprimés lors de la première préparation du nouveau dossier, ce qui provoque au plus un
  rechargement réseau ponctuel.
- DataStore iOS reste dans `Application Support` : il ne contient actuellement que ville, locale et
  devise. Aucun token, historique privé ou état métier synchronisable ne peut y être ajouté.
- Une donnée de compte devant survivre à une réinstallation — dont l’historique de recherche
  connecté — possède une autorité Supabase avec RLS propriétaire. Room n’en garde qu’un miroir
  device-bound. Une donnée invitée reste limitée à l’appareil tant qu’un import explicite vers un
  compte n’a pas été accepté.
- Toute future donnée locale non régénérable qui ne possède pas d’autorité serveur exige une nouvelle
  décision avant d’entrer dans Room.

## Conséquences

**Positives**

- Les chemins Room visés ne participent pas aux sauvegardes ou transferts déclarés par les
  plateformes et ne réexposent pas silencieusement l’état privé d’un autre appareil.
- DB, WAL, SHM et journal SQLite partagent la même politique iOS.
- L’historique durable d’un compte reste disponible via une synchronisation RLS explicite.
- Les politiques Android et le builder iOS sont verrouillés par les contrôles du dépôt.

**Négatives / compromis assumés**

- Un utilisateur doit se reconnecter sur un nouvel appareil et les caches locaux sont reconstruits.
- L’ancien cache iOS est invalidé une fois lors de la mise à niveau.
- Une opération locale non encore synchronisée peut être perdue avec l’appareil ; l’outbox et les
  brouillons doivent donc avoir une politique serveur adaptée avant leur livraison.
- Le simulateur valide les métadonnées mais pas le comportement matériel pendant le verrouillage.
- Les politiques de sauvegarde plateforme ne constituent pas une preuve cryptographique absolue.
  Toute future donnée sensible non régénérable exige une décision dédiée sur le chiffrement avec clé
  liée à l’appareil ; un historique invité peut disparaître lors d’une réinstallation ou d’une purge.

**À revoir si**

- Apple ou Android modifie les formats ou la sémantique de sauvegarde ;
- Kwabor introduit une donnée locale non régénérable sans copie serveur ;
- une qualification appareil montre que la protection ou l’exclusion n’est pas appliquée ;
- un besoin légal explicite impose une conservation ou une portabilité différente.

## Références

- [Android — Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Apple — Excluding files from backup](https://developer.apple.com/documentation/foundation/urlresourcekey/isexcludedfrombackupkey)
- [Apple — Complete until first user authentication](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication)
