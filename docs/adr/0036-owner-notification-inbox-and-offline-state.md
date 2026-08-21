# 0036 — Versionner la boîte de notifications propriétaire et ses états hors ligne

- **Statut** : proposé
- **Date** : 2026-08-10
- **Décideurs** : proposition Produit Kwabor, Architecture, Data, Mobile et Sécurité ; validation
  Produit et Sécurité requise avant activation en production
- **Complète** : ADR-0011, ADR-0013, ADR-0027 et ADR-0035
- **Remplace** : —

## Contexte et problème

Le centre de notifications est une destination permanente de la navigation Android et iOS, mais sa
surface actuelle ne possède ni autorité propriétaire versionnée, ni cache privé, ni protocole de
lecture ou de masquage résistant au réseau intermittent. Le schéma historique expose des types
`social`, `listing`, `promotion` et `system` ainsi qu'un booléen `read`. Il ne représente pas les
quatre familles proactives exigées par le produit et ne permet pas de distinguer :

- une notification déjà couverte par l'ouverture de la boîte, qui ne doit plus alimenter le badge ;
- une ligne réellement lue par l'utilisateur ;
- une ligne masquée par un swipe, qui ne doit plus être affichée.

Réutiliser un seul booléen pour ces trois intentions rendrait impossible le comportement « badge
effacé à l'ouverture » sans marquer silencieusement toutes les lignes comme lues. Une pagination par
offset ou par horloge appareil pourrait en outre dupliquer ou sauter des lignes lorsqu'une nouvelle
notification arrive pendant le parcours. Enfin, un cache ou une file hors ligne non cloisonnés par
session risqueraient de montrer ou de drainer les données du compte A sous le compte B.

Ce lot doit livrer une boîte propriétaire utilisable sans dépendre de la remise distante. Les tokens
device, FCM, APNs, les campagnes, les quotas sponsorisés et leur producteur serveur appartiennent à
un lot ultérieur.

## Options envisagées

- **Étendre le booléen `read` historique** : migration courte, mais le badge, la lecture et le
  masquage resteraient confondus et les courses multi-appareils non ordonnées.
- **Conserver tout l'état uniquement sur l'appareil** : permet une UI rapide, mais perd la cohérence
  entre appareils, la reprise après réinstallation et l'autorité RLS du compte.
- **Ajouter une autorité propriétaire versionnée avec séquence serveur, snapshot et miroir Room** :
  sépare les intentions, stabilise la pagination et permet une outbox durable au prix d'un contrat
  Supabase et Room plus explicite.

## Proposition soumise à validation

Si cet ADR est accepté, NOTIF-001A utilisera une autorité Supabase propriétaire, un protocole de
lecture par séquence/snapshot et un miroir Room v5 privé. Le domaine Kotlin pur exposera les familles,
les états et les ports ; Android conservera son UI Compose Multiplatform et iOS son UI SwiftUI native.

### Quatre familles V1 et préférences conservatrices

La boîte proactive V1 accepte exactement les familles suivantes :

- `suggestion` — suggestion personnalisée ;
- `sponsored` — contenu sponsorisé ;
- `new_listing` — nouvelle fiche publiée ;
- `event_alert` — alerte liée à une fiche événement publiée.

Une préférence propriétaire existe pour chaque famille. L'absence de ligne et toute valeur inconnue
signifient **désactivé** : les quatre familles commencent donc en opt-in, désactivées par défaut, et
seule une action explicite du compte peut les activer. Cette préférence produit est distincte de
l'autorisation système de notifications et ne déclenche aucune demande Android/iOS dans ce lot.

La famille `suggestion` ne permettra pas à un futur producteur de contourner le consentement séparé
de personnalisation par activité. La famille `sponsored` ne pourra jamais consommer l'historique brut
ni exposer de donnée nominative à un promoteur. Les choix de fréquence ne sont pas inventés ici :
leurs valeurs et leur sémantique doivent être validées avant d'ajouter le contrôle prévu par le PRD.

Les lignes historiques ne sont pas reclassées par supposition. En particulier, `social` et `system`
ne deviennent aucune des quatre familles V1. Les anciennes lignes restent compatibles avec leur
contrat historique, mais le nouvel endpoint de boîte proactive ne retourne que des lignes portant
explicitement une famille V1 valide. La notification sociale créée par une future réponse à un avis
nécessitera une décision additive dans REVIEWS-001 ; elle ne doit pas être déguisée en suggestion,
nouveauté ou alerte événementielle.

Le contenu V1 transporte des clés de template bornées `titleKey`/`bodyKey` et des arguments structurés
`titleArgs`/`bodyArgs`, jamais une chaîne technique, un payload fournisseur ou une donnée nominative
libre. Chaque template et chaque nom/type d'argument sont allowlistés par le contrat mobile ; une clé,
un argument ou une taille inconnus invalident toute la ligne avant mise en cache. La cible
`relatedListingId` est un UUID canonique et n'autorise l'ouverture que d'une fiche encore publiée ;
`event_alert` exige en plus une fiche de type événement. Les textes localisés restent la responsabilité
des ressources Android/iOS partagées par leur contrat, pas celle du serveur.

### Autorité propriétaire, séquence et snapshot keyset

Chaque notification V1 reçoit dans la transaction serveur qui la publie une séquence 64 bits
strictement croissante pour son compte. Cette séquence est autoritative ; ni le timestamp ni l'ordre
de réception du téléphone ne peuvent la fixer. Toutes les lectures et mutations utilisent
`auth.uid()` comme autorité et sont protégées par RLS. Les RPC acceptent
`p_expected_account_id` uniquement comme fence : toute différence avec `auth.uid()` est refusée
avant lecture ou mutation.
Les mutations concurrentes avec la suppression de compte utilisent le même verrou transactionnel
par compte et refusent un compte déjà engagé dans sa suppression.

La première page capture la tête du compte comme `snapshot_sequence`. Toutes les pages suivantes :

- restent bornées à `sequence <= snapshot_sequence` ;
- reprennent par keyset total `sequence DESC`, rendu strictement total par l’unicité de la
  séquence pour le compte ;
- portent un curseur opaque lié au compte, à la version du contrat, au snapshot et à la limite ;
- refusent un curseur malformé, futur, réutilisé pour un autre compte ou avec une autre limite.

Une notification publiée après le snapshot attend le prochain refresh au lieu de s'insérer au milieu
de la pagination. `created_at` reste un instant serveur utilisé pour l'affichage et les groupes
« Aujourd'hui », « Cette semaine » et « Plus tôt », calculés selon le calendrier du Bénin, sans
devenir une clé d'ordre choisie par l'appareil.

Le transport demande 20 lignes utiles par défaut et refuse une limite hors de `1..50`. Il peut
retourner une ligne sentinelle supplémentaire pour signaler la suite ; cette sentinelle n'est ni
affichée ni mise en cache, et le curseur suivant vient de la dernière des 50 lignes utiles au plus.
La projection complète est validée avant tout commit local.

Le miroir conserve au plus 200 notifications par compte, comme préfixe strict newest-first. Quand
ce préfixe atteint exactement 200 lignes, son curseur de continuation devient nul : aucune éviction
silencieuse ne peut créer un trou puis reprendre un ancien curseur. Un payload ou un merge qui
dépasserait 200 lignes est refusé atomiquement par une erreur de capacité typée, sans altérer le
snapshot déjà valide.

### `seenThrough`, `readAt` et `hiddenAt`

Les trois états sont monotones et ne se remplacent pas mutuellement :

- `seenThrough` est un watermark par compte exprimé en séquence. À l'ouverture de la boîte, le
  client capture le snapshot réellement présenté puis demande
  `seenThrough = max(seenThrough, snapshot_sequence)`. Le badge signale seulement les lignes V1 non
  masquées dont la séquence est au-delà de ce watermark. Avancer ce watermark ne marque aucune ligne
  comme lue.
- `readAt` appartient à une notification. Il est fixé par une action de lecture unitaire ou par
  « Tout marquer comme lu » et ne redevient jamais nul. Une lecture unitaire fixe aussi le marqueur
  interne `seenAt` de la ligne, sans déplacer le watermark global. L'action globale cible le snapshot
  affiché ; une notification arrivée ensuite demeure non lue et non vue.
- `hiddenAt` appartient à une notification. Le swipe le fixe de façon monotone et exclut la ligne des
  lectures suivantes. Masquer fixe aussi `seenAt`, sans avancer `seenThrough` et sans falsifier
  `readAt`. Une ligne est `unseen` si et seulement si elle n'est pas masquée, si sa séquence est
  strictement supérieure à `seenThrough` et si `seenAt` est nul.

Les RPC associés sont idempotents et account-fenced. Une valeur plus ancienne ne peut faire reculer
un watermark ni effacer un timestamp déjà acquis. Une cible devenue indisponible reste une ligne
lisible et masquable ; son ouverture échoue avec un message produit neutre, sans exposer de payload
technique.

### Room v5 privé et outbox durable

Room passe de la version 4 à la version 5 par une migration non destructive. Le schéma ajoute, sous
une clé de compte obligatoire :

- le miroir borné des notifications V1 et les métadonnées du dernier snapshot validé ;
- le watermark `seenThrough`, les préférences des quatre familles et leurs états pending ;
- une outbox de 512 commandes logiques au plus par compte : avancer `seenThrough`, lire une ligne,
  lire jusqu'à un snapshot, masquer une ligne et choisir l'état d'une préférence.

L'intention est persistée avant l'optimisme visuel. Chaque commande possède une identité stable
réutilisée pendant ses retries ; les commandes monotones ou visant la même préférence sont
coalescées vers l'état le plus récent sans permettre à une réponse ancienne d'effacer une intention
plus récente. Le commit, le retry, la suspension et la suppression utilisent un CAS sur l'identité
de l'opération. À 512 commandes, le rejeu ou la coalescence d'une clé logique existante reste permis,
mais une nouvelle clé est refusée transactionnellement par une erreur de capacité typée. Lire et
masquer la même notification restent deux clés indépendantes, quel que soit leur ordre. Les erreurs
réseau suivent un backoff borné ; les erreurs d'authentification
suspendent le drain et les violations de contrat restent visibles comme erreurs typées expurgées.

Le repli Room en mémoire de l'ADR-0027 peut afficher un résultat réseau courant, mais ne doit jamais
promettre une mutation hors ligne durable. Si la capacité persistante est indisponible, une action
qui exige l'outbox échoue honnêtement avant de publier son optimisme. Une lecture offline peut afficher
le dernier snapshot validé avec son état offline ; elle ne fabrique ni nouvelle notification, ni
acquittement serveur.

Le miroir, ses métadonnées et l'outbox sont privés et strictement liés au compte. La suppression de
compte les purge avant le parcours distant conformément à l'ADR-0035. Cette purge est obligatoirement
composite : un coordinateur app-scoped bloque les gates Interaction et Notification, attend leurs
opérations réellement idle, puis une seule transaction Room supprime l'outbox Interaction, le
snapshot et les lignes Notification, l'outbox Notification et les préférences du compte. Aucun
repository de feature n'expose de purge partielle et le mode Room mémoire échoue avant de promettre
l'effacement.

Après le commit SQL, les deux runtimes invalident leurs wakes, jobs, signaux, effets, états et
générations du compte avant de finaliser leurs tokens exacts en mode committed. Le handle
d'ownership est enregistré dès le point irréversible : `Acquired` n'est rendu que si les deux
invalidations et les deux finalisations réussissent. Un échec post-commit devient un état typé de
reprise, jamais une autorisation de lancer la suppression distante. La reprise suit chaque
participant séparément, conserve le même handle jusqu'aux deux succès et rejette un callback ancien
ou doublé. Android et iOS transmettent ce handle exact à leur handoff tardif ; une annulation ou un
timeout après acquisition provoque une reprise `NonCancellable` au plus une fois.

Une déconnexion ou un changement
de compte masque immédiatement l'ancien miroir en mémoire et annule ses travaux en vol, sans drainer
ses commandes sous une autre identité. Cette transition de session ne purge pas les lignes durables :
elles restent cloisonnées par compte pour une reprise ultérieure de ce même compte. Seule la
suppression de compte explicitement engagée déclenche leur purge durable.

### Fence de session et surfaces natives

Tout chargement, refresh, append, mutation, effet de navigation et événement de synchronisation porte
le `NotificationAccountScope` exact, composé du compte et de l'epoch local capturé à l'origine. Cet
epoch fence les appels repository/réseau et leurs commits runtime ; il n'entre ni dans les clés ni
dans les payloads Room, dont l'identité durable reste le compte afin de permettre la reprise du même
compte après déconnexion. Le repository vérifie aussi l'identité active Supabase au lieu de faire
confiance au compte fourni par l'UI. Le fence est contrôlé avant et après le réseau ainsi qu'avant
toute écriture Room ou publication d'effet. Une réponse A retardée après A → B, A → invité ou
suppression de A est rejetée.

Le runtime partagé expose un état immuable et des intentions communes. Les deux surfaces rendent :

- les groupes temporels, le point non-lu et les états skeleton, vide, erreur et offline ;
- le badge texte jaune « Sponsorisé », jamais indiqué par la couleur seule ;
- « Tout marquer comme lu », le swipe pour masquer et le lien vers les quatre préférences ;
- le tap vers le `DetailSheet` seulement après validation de la cible publiée.

L'événement analytique `notification_opened` est émis uniquement après confirmation de l'ouverture
du détail et seulement si le consentement Analytics courant l'autorise. Le simple tap, un échec de
cible, un refresh ou un rejeu d'outbox ne l'émettent pas.

## Hors de cette proposition

- Enregistrement de tokens device, demande de permission système et diagnostic de remise.
- FCM, APNs, Edge Function de dispatch, producteur distant et événement
  `notification_received`.
- Campagnes, paiement, ciblage, plafond sponsorisé de 1 par 24 h et 3 par semaine, silence local de
  21 h à 8 h, retry de remise et deep link de notification système.
- Valeurs du sélecteur de fréquence et activation automatique d'une famille.
- Reclassement des notifications legacy `social`/`system` ou livraison des réponses aux avis.
- Nouveau client applicatif autre qu'Android et iOS.

## Validation exigée avant acceptation

- faire approuver par Produit et Sécurité l'opt-in désactivé par défaut, l'exclusion des lignes
  legacy et l'absence temporaire de contrôle de fréquence ;
- conserver les preuves automatisées de la limite utile serveur `1..50`, du cache à 200 lignes sans
  trou de curseur et de l'outbox à 512 clés avec coalescence et refus atomique au dépassement ;
- décider et tester avant NOTIF-002 la rétention des notifications et des receipts d'idempotence :
  aucune purge automatique ni fenêtre de rejeu producteur n'est activée par NOTIF-001A ;
- couvrir RLS/IDOR, curseurs forgés, snapshot concurrent, ordre des séquences, suppression de compte
  et A → B → invité avec réponses retardées ;
- couvrir la migration Room `4 → 5`, les redémarrages, le bornage, le CAS, le coalescing, le repli
  mémoire et le drain repris uniquement par le compte propriétaire ;
- prouver le rollback de la transaction composite, l'attente des deux gates, l'invalidation avant
  acquisition, les échecs post-commit récupérables et le handoff tardif exact-once Android/iOS ;
- prouver la parité fonctionnelle et l'accessibilité Android/iOS, dont le badge sponsorisé textuel,
  les groupes en heure du Bénin, le badge navbar distinct du point non-lu et tous les états réseau ;
- vérifier qu'aucun token, appel FCM/APNs, producteur distant ou analytics sans consentement n'entre
  dans NOTIF-001A.

## Conséquences

**Positives**

- Le badge, la lecture et le masquage ont des autorités indépendantes et testables.
- La pagination reste stable pendant l'arrivée de nouvelles notifications.
- Android et iOS convergent sur le même état sans exposer Supabase au domaine ou aux UI.
- Les mutations hors ligne survivent au redémarrage lorsqu'une Room persistante sûre est disponible.
- Les notifications marketing restent désactivées tant que le compte ne les a pas explicitement
  autorisées.

**Négatives / compromis assumés**

- Room v5, l'outbox et les RPC account-fenced augmentent le nombre d'états à tester.
- Une boîte V1 peut rester vide tant qu'aucun futur producteur autorisé n'alimente les quatre
  familles.
- Les notifications legacy sociales et système ne sont pas visibles dans la nouvelle boîte proactive.
- Le contrôle de fréquence promis par le PRD reste absent jusqu'à validation de ses valeurs.

**À revoir si**

- Produit ajoute une cinquième famille ou décide d'intégrer les réponses aux avis à cette boîte ;
- la remise FCM/APNs et les campagnes deviennent autoritatives ;
- une exigence légale modifie l'opt-in, la rétention ou l'effacement ;
- plusieurs producteurs serveur exigent un autre modèle de séquence ou d'idempotence.

## Références

- [PRD — Centre de notifications proactif](../../PRD.md)
- [DESIGN — D1 Centre de notifications et G4 Préférences](../../DESIGN.md)
- [ADR-0011 — Persistance locale Room KMP](0011-room-kmp-local-persistence.md)
- [ADR-0013 — Services mobiles Firebase](0013-firebase-mobile-platform-services.md)
- [ADR-0027 — Persistance locale liée à l'appareil](0027-device-bound-local-persistence.md)
- [ADR-0035 — Outbox durable Like/Favori](0035-durable-viewer-interaction-outbox.md)
