# 0036 — Profil de livraison V1 bêta fermée catalogue

- **Statut** : accepté
- **Date** : 2026-08-12
- **Décideurs** : propriétaire produit Kwabor
- **Remplace** : —

## Contexte et problème

La cible V1 décrite dans le PRD reste un produit Android/iOS complet à cinq racines. Son achèvement
avant tout test utilisateur reporterait cependant la première observation réelle du parcours qui
porte la proposition de valeur principale : découvrir le Bénin, ouvrir une fiche crédible, puis
enregistrer ou contacter.

Le socle Explore, Search, Detail, Like/Favori, Auth et Paramètres existe déjà sur Android et iOS.
Les racines Social, Ajouter et Notifications ainsi que l'IA, les avis, le B2B et le paiement ne sont
pas suffisamment terminés pour être exposés sans placeholders ou parcours morts. Le catalogue de
démonstration actuel ne contient en outre que quatre fiches techniques et des médias factices ; il
ne permet pas d'évaluer le potentiel visuel du produit.

La décision requise porte sur un profil de livraison intermédiaire testable. Elle ne redéfinit pas
la vision cible du PRD et ne transforme pas les fonctionnalités reportées en éléments terminés.

## Options envisagées

- **Option A — attendre la V1 complète** : respecte immédiatement toute la cible, mais retarde
  fortement les apprentissages produit et cumule les risques d'intégration.
- **Option B — publier une coquille avec placeholders** : accélère la distribution, mais dégrade la
  confiance et produit des retours peu représentatifs.
- **Option C — bêta fermée catalogue** : expose uniquement un parcours vertical cohérent, avec un
  corpus de démonstration riche, tout en gardant les surfaces non livrées hors navigation.

## Décision

Nous retenons l'option C parce qu'elle permet de tester rapidement la proposition de valeur réelle
de Kwabor sans affaiblir les exigences de sécurité, de confidentialité, de qualité ou de données.

### Parcours inclus

- introduction, accès invité et authentification utile au premier engagement ;
- Explorer avec les trois murs `Lieux`, `Événements`, `Hôtels & Restaurants`, ville, catégories,
  pagination, refresh, cache hors ligne et états chargement/vide/erreur ;
- recherche lexicale simple et résultats publiés uniquement ;
- fiche native typée Android/iOS, galerie officielle, horaires et prix XOF ; les lanceurs externes
  restent disponibles pour de futures fiches réelles mais sont masqués sur le corpus fictif ;
- Like, Favori, outbox durable et `Compte → Favoris` ;
- Compte et Paramètres minimaux : consentements, déconnexion et suppression de compte.

La navigation de la bêta est une allowlist `Explorer · Compte`. Les racines masquées, leurs deep
links et leurs anciens placeholders ne doivent jamais devenir accessibles par un autre chemin. Un
deep link non admis revient vers Explorer avec un message neutre.

### Parcours différés après la bêta

- Social et profil public ;
- Ajouter, contribution, wizards, signalement de lieu manquant et modération associée ;
- centre de notifications et push ;
- assistant IA et « Surprenez-moi » ;
- avis, partage public, signalement et revendication de fiche ;
- filtres avancés, récents de recherche et autocomplétion ;
- B2B, sponsoring commercial, paiement, facturation et conversion multidevise ;
- guide, langues supplémentaires, thème sombre complet et 2FA tant que leurs parcours complets ne
  sont pas qualifiés pour cette cohorte.

Ces éléments restent dans la cible du PRD ou de ses versions ultérieures. Ils ne sont ni supprimés
du backlog ni déclarés livrés.

### Catalogue démonstrateur obligatoire

La bêta n'est distribuable qu'avec exactement 60 fiches publiées et complètes :

| Mur éditorial | Nombre | Répartition par ville |
| --- | ---: | --- |
| Lieux | 15 | 5 Cotonou, 5 Ouidah, 5 Porto-Novo |
| Événements | 15 | 5 Cotonou, 5 Ouidah, 5 Porto-Novo |
| Hôtels | 15 | 5 Cotonou, 5 Ouidah, 5 Porto-Novo |
| Restaurants | 15 | 5 Cotonou, 5 Ouidah, 5 Porto-Novo |

Chaque fiche possède ses champs typés valides et trois images officielles, soit 180 images. Les
visuels sont générés pour la démonstration, réalistes mais sans marque, personne reconnaissable,
texte incrusté ni prétention documentaire. Les établissements, offres et événements sont fictifs,
portent une mention explicite de démonstration et n'utilisent aucune coordonnée de contact réelle.

Les fichiers servis sont des JPEG progressifs sRGB en portrait 3:4, sans EXIF/GPS, bornés à
960 × 1280 et 320 Kio, pour un corpus total de 48 Mio maximum. Ils sont versionnés hors des
ressources applicatives, couverts par un
manifeste SHA-256 et copiés sans écrasement dans un bucket public Supabase Storage réservé au
staging. Les clients n'obtiennent aucun droit d'écriture sur ce bucket. Le seed de démonstration est
séparé du seed canonique et ne peut jamais être appliqué implicitement à la production.

Les contraintes existantes imposent un canal de contact aux établissements actifs et un contact
organisateur aux événements. Le seed utilise uniquement des identités réservées en `.test`, taggées
comme données de démonstration ; l'UI bêta supprime leurs CTA afin qu'aucune action morte ou adresse
réelle ne soit exposée.

### Gates non différables

- CI exacte : intégrité, Spotless, Detekt, tests, pgTAP, concurrence et builds simulateur Android/iOS ;
- RLS/IDOR, session, outbox, suppression de compte et absence de secret ou PII dans les logs ;
- staging isolé, sauvegarde/rollback du catalogue et upload média idempotent ;
- provenance éditoriale, droits de diffusion et mention des visuels générés ;
- builds signés de distribution interne, tests sur appareils réels, offline, performance et
  accessibilité TalkBack/VoiceOver ;
- textes légaux et consentements adaptés à la cohorte ;
- cohorte cible de 10 appareils Android et 5 iPhone pendant sept jours, zéro P0/P1 et au moins
  99,5 % de sessions sans crash.

## Conséquences

**Positives**

- le potentiel du catalogue devient visible et testable rapidement ;
- aucune surface incomplète ou action morte n'est exposée ;
- les retours portent sur le cœur de valeur plutôt que sur une architecture ou des placeholders ;
- le seed, les médias et leur déploiement restent reproductibles et réversibles.

**Négatives / compromis assumés**

- la bêta ne constitue pas la V1 publique complète du PRD ;
- la génération et la revue éditoriale de 180 visuels représentent un travail significatif ;
- la navigation temporaire à deux racines devra évoluer lorsque les surfaces différées seront
  réellement livrées.

**À revoir si**

- les tests montrent que le parcours catalogue ne permet pas de mesurer l'intérêt produit ;
- la cohorte exige une fonction différée pour achever le scénario principal ;
- les coûts média dépassent le budget fixé ou les règles de diffusion ne peuvent être prouvées ;
- les critères de stabilité, sécurité ou confidentialité ne sont pas atteints.
