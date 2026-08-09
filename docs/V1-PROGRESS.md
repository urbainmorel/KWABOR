# Suivi opérationnel V1

Ce fichier est le tableau de bord courant de la reprise V1. Le détail chronologique reste dans
[`PROJECT_STATE.md`](../PROJECT_STATE.md) et les lots dans [`BACKLOG.md`](../BACKLOG.md).

## État global

| Élément | État vérifié |
| --- | --- |
| Date du snapshot | 9 août 2026 |
| Référence Git | Base du lot : `main` au merge `c630ee6b0b323544d891c71e80e6ebde06672738` de la PR `#57` |
| Intégration | PR `#50` V1, `#51` fondation HISTORY, `#52` CI, `#53` autorité HISTORY, `#54` état/ADR-0031, `#55` autorité FAVORITES, `#56` client FAVORITES et `#57` EXPLORE-002B2A fusionnées ; EXPLORE-002B2B1 livré par le présent lot |
| Sécurité | PR `#35` fusionnée ; préflight et déploiement sur environnement persistant non exécutés |
| Ancienne pile | Les PR `#36` à `#48` sont fermées avec commentaires de supersession ; leurs têtes sont déjà ancêtres de `main` via `#50` |
| Auth parallèle | PR `#34` fermée avec commentaire de supersession, non ancêtre de `main` et remplacée fonctionnellement par AUTH-UX-001 intégrée |
| CI de la fusion | Runs post-fusion jusqu'à `31316774201` entièrement verts, dont Supabase, Gradle et iOS Debug/Staging/Release sur la fusion de `#56` |
| Décision de release | **No-go** |
| Périmètre V1 | Divergence ouverte entre le PRD/DESIGN complet et la V1 minimale proposée par l'audit |

Les anciens pourcentages d'avancement ont été retirés : ils n'étaient pas reliés à une matrice de
couverture vérifiable et donnaient une précision trompeuse après la fusion de la pile.

## Intégré dans `main` ou livré par le présent lot

### Architecture, sécurité et livraison

- ARCH-004 maintient le domaine Kotlin pur et injecte les dispatchers depuis la couche application.
- STAB-003 verrouille le wrapper, les inventaires, les fichiers fournisseurs et les contrôles
  d'intégrité du dépôt.
- SEC-001A ferme les contournements d'autorisation identifiés sur l'onboarding, Social, les équipes,
  les claims, les signalements et la taxonomie des fiches.
- SEC-001F renforce la suppression de compte par ré-authentification et session éphémère, sans
  credentials dans le body de l'Edge Function.
- Les fondations de build Android et iOS, la CI parallèle, les configurations Xcode simulateur et la
  documentation DOC-001 sont présentes.

### Authentification, onboarding et paramètres

- L'intro embarquée Store-only, byte-identical Android/iOS, remplace le canal média distant. Toute
  nouvelle vidéo exige une nouvelle release dans les deux Stores.
- L'inscription compacte email ou fédérée, la connexion, la récupération, Google Android/iOS,
  Apple iOS, la session invitée et la reprise contextuelle de l'action protégée sont intégrées.
- Profil et Paramètres sont séparés. Déconnexion, suppression de compte et consentements
  d'observabilité refusés par défaut sont accessibles sur Android et iOS.
- OFFLINE-002 place les données locales dans les emplacements non sauvegardés prévus et applique un
  repli mémoire si la politique de protection ne peut pas être garantie.

### Parcours de découverte livré

- Explore Android et SwiftUI consomment le même runtime partagé : pagination, déduplication,
  refresh, ville/GPS, cache Room, états offline/erreur/vide et interactions Like/Favori.
- SEARCH-001A fournit la recherche lexicale publiée-only, la pagination, les résultats Android/iOS
  et un fallback Room borné. Il ne persiste aucune requête utilisateur.
- DETAIL-001A/B/iOS fournit un read model atomique et les fiches natives Android/iOS pour six
  variantes, avec médias officiels, horaires, prix XOF, champs typés et statuts temporels.
- ACTIONS-001A fournit les actions réellement disponibles : itinéraire, téléphone, WhatsApp, site,
  email, menu et billetterie externe.
- GUIDE-001B et ACTIONS-001C1 fournissent la découverte publique des guides et le deep link interne
  strict `kwabor://listing/<uuid>`.
- FAVORITES-001A fournit la lecture propriétaire paginée et Profil → Favoris sur Android/iOS, avec
  filtres de type, ruban « Terminé », retrait, ouverture du détail, accessibilité et synchronisation
  bidirectionnelle avec Explore cloisonnée par compte et epoch de session.
- EXPLORE-002B2A, intégré via `#57`, fournit un RPC v2 séparé : popularité, proximité temporelle,
  fenêtres événement UTC, bornes prix XOF, curseur keyset lié au snapshot et deux placements
  sponsorisés au plus en tête. Le RPC v1 reste inchangé pour les clients Store existants.
- EXPLORE-002B2B1 livre dans le présent lot sa consommation par Explore Android/iOS : gateway KMP
  strict séparé du catalogue/Search v1, tri serveur par onglet, pagination au snapshot exact,
  validation cumulative des sponsors, Room v3 avec migrations `1 -> 2 -> 3` et lecture de secours
  du cache v1, puis cartes natives accessibles avec alt, date, état « Terminé » et badge sponsorisé.

## Incomplet ou absent

### Parcours visibles

- Les racines Social, Ajouter et Notifications affichent encore le placeholder
  `Socle applicatif en place` sur Android et iOS. Le Profil reste limité à l'identité, aux Favoris et
  à l'accès aux Paramètres. Ces surfaces interdisent une release publique.
- La queue offline Like/Favori reste en mémoire et ne draine pas encore durablement après
  reconnexion ; Room et l'outbox persistante restent dans SYNC-001.
- La recherche n'a pas encore de récents durables, d'autocomplétion ni de filtres avancés.
- Le drawer Explore avancé n'est pas livré : bornes prix, presets de dates civiles, éventuel
  multi-ville, compteur live et recherche filtrée restent dans EXPLORE-002B2B2. Produit doit
  arbitrer l'extension du RPC ville scalaire, l'autorité/coût du compteur et le partage des filtres
  avec SEARCH-001B ; aucun filtrage ou classement local de substitution n'est implémenté.
- Le détail ne fournit pas encore la carte intégrée, les avis, le partage public, le signalement ni
  le claim sécurisé. Les actions externes déjà intégrées ne couvrent pas ces fonctions.

### Périmètre PRD V1 non livré

- Social, contribution et profils publics ;
- centre et préférences de notifications ;
- assistant IA et « Surprenez-moi » ;
- listing wizard, média et modération complète ;
- dashboard Promoteur, campagnes, paiement et facturation ;
- conversion multidevise, thème sombre complet et préférences avancées.

Le [PRD](../PRD.md) et le [DESIGN](../DESIGN.md) conservent ces fonctions dans leur V1 actuelle. Le
[rapport de préparation](audits/2026-07-30-v1-production-readiness.md) propose de les reporter pour
une V1 minimale, mais cette réduction n'est pas approuvée ni tracée par ADR. STATE-001 ne modifie
donc ni les cinq racines officielles ni le périmètre produit.

## État des PR historiques

| PR | État actuel | Action de suivi |
| --- | --- | --- |
| `#50` | Fusionnée dans `main` | Référence d'intégration |
| `#35` | Fusionnée dans `main` | Préflight puis déploiement contrôlé restent ouverts |
| `#36` à `#48` | Fermées ; code déjà intégré via `#50` | Commentaires de supersession publiés ; aucune nouvelle fusion |
| `#49` | Fermée ; head intégré via `#50` | PR d’intégration intermédiaire supersédée |
| `#34` | Fermée sans fusion | Commentaire de supersession publié ; aucun portage manuel requis |

La clôture administrative de cette pile est terminée. Les gates physiques, environnementales ou
éditoriales associées restent toutefois valides tant qu'elles ne sont pas prouvées séparément.

## HISTORY-001

La décision produit du 4 août exige de conserver l'historique des recherches soumises pour Search,
le futur Assistant IA et le fil organique pertinent.

État actuel : **socle domaine et autorité Supabase livrés ; miroir Room, synchronisation et UI non
implémentés**.

- Le cache SEARCH-001A conserve des résultats catalogue bornés, pas les requêtes de l'utilisateur.
- Le domaine Kotlin pur partage la canonicalisation avec Search, modélise les scopes invité/compte,
  l'import invité confirmé, l'effacement, les préférences et les erreurs sans exposer le texte dans
  ses représentations de diagnostic.
- L’autorité Supabase propriétaire expose un snapshot borné et l’effacement via RPC ; aucune
  persistance Room, synchronisation multi-appareil ou UI de récents n’existe encore.
- Seules les requêtes explicitement soumises pourront être conservées ; jamais les frappes,
  suggestions survolées, analytics ou logs bruts.
- L’autorité d’un compte est propriétaire via RLS, effaçable, supprimée avec le compte et distincte
  du consentement de personnalisation.
- Les signaux destinés à l'IA ou au fil organique devront être structurés et bornés ; l'historique
  brut complet ne devra jamais être envoyé au modèle.

ADR-0029 acte les invariants suivants : au plus **200 requêtes canoniques distinctes actives** par
compte côté serveur, au plus **50 par scope et par appareil** en local, personnalisation
**désactivée par défaut**, et resoumission canonique identique qui remonte l'entrée existante sans
créer de doublon.

Pour le texte actif V1, la rétention glissante serveur proposée de **180 jours** et l'activation
juridique de la personnalisation restent bloquées par une validation Juridique/DPO. ADR-0031 reste
proposé et ajoute des gates V2, notamment la durée de conservation des tombstones et clés
d’idempotence avec Juridique/DPO et Opérations. La mise en œuvre doit rester découpée entre autorité
serveur, miroir local/synchronisation et UI des récents.

HISTORY-001A livre désormais l’autorité Supabase propriétaire : RPC versionnées d’enregistrement,
snapshot, effacement unitaire/global, plafond concurrent de 200, préférence désactivée et purge de
compte. Le run exact-head `30938251112` passe 85 assertions HISTORY, 899 assertions pgTAP au total et
11 assertions de concurrence dédiées. Le run post-fusion `30940684400` est lui aussi entièrement
vert sur `main`, avec iOS Debug/Staging/Release forcés. Le lot n’active ni rétention, ni signaux
dérivés, ni déploiement automatique d’environnement distant.

## Prochains lots bornés

1. **EXPLORE-002B2B2 — filtres avancés** : faire arbitrer par Produit les presets de dates civiles,
   les bornes prix, le multi-ville, le compteur live et la recherche filtrée, puis seulement livrer
   le drawer Android/iOS sans déplacer le classement hors du serveur.
2. **HISTORY-001B — synchronisation offline** : faire arbitrer ADR-0031, puis implémenter le
   protocole versionné de révisions, tombstones et watermark, le miroir Room et l’outbox sans
   résurrection après effacement ; conserver 50 requêtes distinctes par scope et appareil.

Ces lots touchent des zones distinctes et évitent de recréer une pile d'intégration longue. Le
nettoyage administratif des PR supersédées et la synchronisation documentaire ne doivent pas être
confondus avec une nouvelle livraison fonctionnelle.

## Gates de mise en production

### Produit

- [ ] Périmètre V1 et navigation approuvés dans PRD, DESIGN, backlog et ADR cohérents.
- [ ] Aucun écran, CTA ou onglet factice.
- [ ] Explore, recherche, détail et favoris complets sur Android et iOS.
- [ ] Corpus éditorial, villes, catégories, médias et responsabilités de publication approuvés.
- [ ] Administration opérateur testée sur un environnement persistant.

### Sécurité et données

- [x] SEC-001A fusionné dans `main`.
- [ ] Préflight des données historiques, sauvegarde/restauration et quarantaine éventuelle approuvées.
- [ ] SEC-001A déployé et qualifié sur staging avant production.
- [ ] Matrice RLS/IDOR, Storage, uploads malveillants, secrets, advisors et rate limits qualifiés.
- [ ] CGU, politique de confidentialité, licence UGC et rétentions approuvées.

### Appareils et qualité

- [ ] TalkBack et VoiceOver qualifiés sur appareils physiques ciblés.
- [ ] OFFLINE-002 qualifié sur Android API 31/36.1, transfert OEM et filesystem iOS réel.
- [ ] Performance, mémoire, réseau dégradé et accessibilité prouvés sur appareils low/mid-range.
- [ ] Tests UI/E2E critiques et parcours fournisseurs réels exécutés.
- [ ] Master de marque, vidéo et droits de diffusion approuvés.

### Environnements et distribution

- [ ] Supabase et Firebase staging/production provisionnés et qualifiés.
- [ ] OAuth, SMTP, APNs, certificats, clé Android et secrets Store prouvés.
- [ ] AAB signé et archive/TestFlight produits et testés.
- [ ] Privacy Report, questionnaires Store, métadonnées, support et URLs légales validés.
- [ ] Sauvegarde, rollback, alertes, kill switches et rollout progressif testés.

Tant que ces gates restent ouvertes, la décision de release demeure **no-go**.
