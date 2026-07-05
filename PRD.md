# PRD — Kwabor
### Le guide visuel et intelligent du Bénin
**Version :** 1.0 · **Date :** 1 juillet 2026
**Auteur :** Product — MAJI (Solution Digitale 360°)
**Statut :** Spécification de référence (Produit · Design · Engineering)
**Document lié :** `DESIGN.md` — design system, spécification écran par écran, patterns d'interaction.

---

## Sommaire

0. Cadrage produit
1. Résumé exécutif & parti pris
2. Problème & opportunité
3. Objectifs & métriques de succès
4. Public cible & personas
5. Périmètre (MVP → V2)
6. Exigences fonctionnelles détaillées
7. Parcours utilisateurs de référence
8. Exigences non fonctionnelles
9. Architecture technique (vue produit)
10. Modèle économique
11. Analytics & instrumentation
12. Risques & mitigations
13. Roadmap & jalons
14. Décisions actées & questions résiduelles
15. Glossaire

---

## 0. Cadrage produit

**Nom du produit et de l'application : Kwabor.**

Trois décisions fondatrices, non négociables, gouvernent tout le reste :

- **Marché unique : le Bénin.** Kwabor n'est pas multi-pays. Tout le catalogue, le contenu et les villes concernent le Bénin (Cotonou, Porto-Novo, Ouidah, Abomey, Parakou, Natitingou, Ganvié…). Le titre de l'écran d'accueil est `Découvrez le Bénin`, traduit selon la langue active. Conséquence : **un seul gabarit d'écran, un seul catalogue** ; le produit se décline par **langue** (traduction de l'UI et du contenu), jamais par pays.
- **Multilingue dès la conception.** Six langues prévues : **Français (défaut), Anglais, Portugais, Allemand, Espagnol, Italien.** Le FR est livré au MVP, l'EN en V1.1, les quatre autres ensuite. **Toute** l'UI passe par les ressources i18n dès le MVP (clés remplies en FR), pour ne jamais bloquer les langues suivantes.
- **Multi-devises à l'affichage.** Quatre devises d'affichage : **XOF (référence), Naira (NGN), USD, Euro.** Les prix sont **stockés et payés en XOF** ; les autres devises sont des **conversions indicatives** (§6.12). Les quatre devises sont actives dès le MVP.

---

## 1. Résumé exécutif & parti pris

### 1.1 Le produit en une phrase
Kwabor est une application mobile multiplateforme (**Android et iOS**) qui aide les résidents, la diaspora béninoise et les touristes à **découvrir, en un coup d'œil et dans leur langue, les meilleurs lieux, hôtels, restaurants et événements du Bénin** — avec la beauté d'un feed social et la fiabilité d'un guide structuré.

### 1.2 Les trois piliers
1. **Inspiration visuelle premium.** Un mur de cartes immersif où la photo est plein cadre et l'interface s'efface derrière des overlays. La découverte est passive et « donne envie » dès le premier regard. Ce niveau de finition est un **avantage concurrentiel** : aucun acteur local (Google Maps, guides papier, Instagram) n'offre une expérience à la fois aussi soignée et aussi structurée.
2. **Recherche intelligente.** Un assistant conversationnel IA accessible en un tap (FAB) pour la recherche active et la recommandation contextuelle, **ancrée au catalogue réel** (jamais d'invention).
3. **Engagement proactif.** Un centre de notifications qui pousse des suggestions personnalisées et du contenu sponsorisé — canal de rétention pour l'utilisateur et source de revenu pour les promoteurs.

### 1.3 Modèle multi-acteurs
- **B2C — consommateurs** : utilisateurs finaux (résidents, diaspora, touristes) qui découvrent, et **publient du contenu communautaire** (photos/diaporamas, rattachés à une entité).
- **B2B — producteurs vérifiés** : **Promoteurs commerciaux** (hôtels, restaurants, bars, lieux commerciaux…), **Guides touristiques** (catégorie de Promoteur, espace « Trouver un guide »), **Institutions/Offices** (patrimoine), qui créent et gèrent les fiches. Les promoteurs financent le modèle via **contenu sponsorisé** et **mise en avant**, payés **en Mobile Money (XOF)**.
- **Opérateur — Admin Kwabor** : crée en masse le patrimonial et pré-inscrit les promoteurs (back-office), arbitre la modération, dispose d'un **profil public à badge vérifié**.
- **Règle structurante** : la **création de fiche est réservée** aux producteurs vérifiés et à l'Admin ; l'utilisateur contribue par le **social** et le **signalement de lieu manquant** (§6.11).

### 1.4 Parti pris design & identité visuelle
L'identité visuelle est un **levier produit**, pas une couche décorative. Sur le marché cible, l'offre numérique est soit non curatée et froide (Google Maps), soit belle mais inactionnable (Instagram/TikTok). Kwabor occupe l'espace libre.

**Direction visuelle** — premium, immersive, photo-first :
- **Accent monochrome noir** (et nuances de gris). Un système neutre met le **contenu** (lieux, photos) en avant et donne une signature haut de gamme.
- **Image plein cadre + overlay dégradé** sur cartes et fiches : le texte vit *sur* l'image, jamais dans un bandeau séparé.
- **Couleur uniquement quand elle porte un sens métier** : le **jaune « Sponsorisé »** (transparence publicitaire) et le **rouge « billetterie / danger »** sont les **deux seuls** écarts de couleur tolérés. Sur fond monochrome, ils en deviennent plus repérables — ce qui sert directement les règles de transparence et d'urgence.

**Garde-fou non négociable — le premium ne coûte jamais la performance.** La cible utilise majoritairement des Android low/mid-range sur réseau 3G/4G dégradé. L'esthétique premium s'obtient **par le design, pas par le poids** : overlays/dégradés en Compose/SwiftUI (jamais d'images lourdes empilées) ; photos AVIF/JPG/PNG compressées, lazy-load, placeholder dégradé immédiat ; aucun effet coûteux (blur temps réel massif, parallax lourd) qui dégraderait le budget **P75 < 1,5 s** (§8). Arbitrage explicite : viser le rendu des meilleures apps premium **sans** copier leurs recettes gourmandes en data.

---

## 2. Problème & opportunité

### 2.1 Problème
- La découverte de lieux, d'événements et d'établissements au Bénin est **fragmentée** : Google Maps (peu curaté, peu visuel, non localisé), Instagram/TikTok (ni structurés ni actionnables), bouche-à-oreille.
- Les **promoteurs locaux** (restaurateurs, hôteliers, organisateurs) n'ont pas de canal numérique simple et abordable pour toucher une audience locale ciblée. Les solutions existantes (Meta Ads, Google Ads) sont coûteuses, complexes et mal adaptées au paiement **Mobile Money** local.
- Les **guides classiques** (papier, sites statiques) ne sont jamais à jour et n'ont aucune dimension sociale ou conversationnelle.

### 2.2 Opportunité
- **Marché mobile-first** : forte pénétration smartphone Android low/mid-range, usage WhatsApp quasi universel, Mobile Money dominant.
- **Diaspora béninoise** en forte demande de contenu du pays, prête à recommander/payer des lieux pour ses proches en visite — d'où l'intérêt d'un affichage **multi-devises** (Euro, USD, Naira) et **multilingue**.
- **Tourisme régional et international** en croissance (Ouidah, Abomey, Ganvié, Pendjari, Vodun Days…), publics anglophone, lusophone et hispanophone à capter via la traduction.
- **Aucun acteur** ne combine UGC visuel + IA conversationnelle + monétisation promoteur native Mobile Money sur ce marché, ni à ce niveau de finition.

---

## 3. Objectifs & métriques de succès

| Objectif | Métrique (KPI) | Cible (6 mois post-lancement) |
|---|---|---|
| Adoption | Utilisateurs actifs mensuels (MAU) au Bénin | 25 000 |
| Engagement passif | Taux scroll → like/favori sur le mur | > 8 % |
| Engagement actif IA | % de sessions utilisant l'assistant IA | > 20 % |
| Rétention | Rétention J7 / J30 | 30 % / 12 % |
| Notifications | Taux d'ouverture des notifications proactives | > 15 % |
| Monétisation | Comptes Promoteur actifs payants | 150 établissements |
| Monétisation | Revenu mensuel récurrent (sponsorisé) | À cadrer avec Finance (§10) |
| Qualité contenu | Ratio de fiches ≥ 3 photos + ≥ 1 avis | > 70 % du catalogue actif |
| Performance | Chargement du mur (P75) sur 3G/4G dégradée | < 1,5 s |
| Désirabilité | Proxy « belle app » : taux de partage de fiches/cartes | À instrumenter (§11) |

---

## 4. Public cible & personas

### 4.1 Utilisateurs finaux
- **Résidents urbains** (Cotonou, Porto-Novo, Parakou…) en quête de sorties, restaurants, événements.
- **Diaspora béninoise** préparant un séjour ou recommandant des lieux à distance — souvent non francophone (anglophone, lusophone…) ou raisonnant en Euro/USD.
- **Touristes internationaux** cherchant un guide curaté et fiable, dans leur langue.

**Persona principal — Léa, 29 ans, Cotonou.** Cadre dans le digital, sort le week-end, suit des comptes Instagram de bons plans, lasse de comparer quatre apps pour trouver un resto + un événement le même soir. Sensible au design : une app « moche » la fait décrocher, une app « belle » la fait revenir et partager. WhatsApp en continu.

**Persona international — Anna, 34 ans, Lisbonne.** Touriste préparant un voyage au Bénin (Ouidah, Ganvié, Pendjari). **Ne parle pas français, raisonne en euros.** A besoin d'une app **dans sa langue** avec des **prix lisibles dans sa devise**, sinon elle retombe sur des blogs et TripAdvisor.

### 4.2 Promoteurs
- Hôteliers et restaurateurs, indépendants ou en chaîne locale.
- Organisateurs d'événements (concerts, festivals, conférences).
- Offices de tourisme / institutions (lieux historiques, sites classés) — accès gratuit, contenu certifié.

**Persona secondaire — Moussa, 41 ans, restaurateur à Cotonou.** Gère seul sa visibilité digitale, budget marketing limité, paie en Mobile Money, veut un **ROI direct et mesurable** sur ses campagnes.

**Persona guide — Koffi, 36 ans, guide touristique à Ouidah.** Agréé, parle français/anglais/portugais, propose des circuits (Route des esclaves, Ganvié, Pendjari). Cherche un canal pour **exposer ses services** et **annoncer ses sorties/événements** à une audience de voyageurs qualifiés — sans page externe ni budget pub.

---

## 5. Périmètre

### 5.1 MVP (V1.0) — dans le périmètre
**Découverte (B2C)**
- **Écran d'intro** au tout premier lancement : **vidéo d'arrière-plan immersive** (univers touristique/culturel/festif du Bénin), **embarquée + remplaçable à distance**, sautable → écran **« Se connecter ou s'inscrire »**.
- Mur d'exploration (Lieux / Événements / Hôtels & Restaurants) : recherche par mots-clés, sous-catégories en chips, filtres avancés.
- Fiches de détail immersives (Lieu, Établissement, Événement) : hero + sheet + barre d'action, avec avis, notation, carte, contact. **Fiche = catalogue uniquement** (médias officiels ; aucun média communautaire n'y est affiché).
- **Recherche par mots-clés** : état actif (récents, suggestions, autocomplétion) + écran de résultats.
- Interactions : **Like** (cœur), **Favori** (marque-page), **Partage**, **Signalement**, **Écriture d'avis**.
- **Assistant de découverte IA** conversationnel (FAB) + écran **« Surprenez-moi »** (recommandation aléatoire pondérée).
- **Centre de notifications** proactif (4 familles, dont contenu sponsorisé labellisé).

**Réseau social communautaire (B2C) — dès le MVP**
- Onglet **Social** : feed vertical façon TikTok mêlant **photo unique** et **diaporama** (la **vidéo est différée en V1.1**). Suivre un créateur, Like, partage.
- **UGC rattaché obligatoirement** (contrainte technique) à une entité du catalogue (Lieu / Établissement / Événement) ; **message contextuel** rappelant le lien requis à l'ouverture du composeur.
- L'UGC vit **dans le feed Social et le profil de son auteur — jamais sur les fiches**.
- **Mention d'entité** : au tap, un **aperçu de fiche (≤ 25 % de hauteur)** s'ouvre au-dessus du contenu ; taper le contenu referme l'aperçu (on continue de scroller), taper l'aperçu ouvre la fiche complète.

**Compte, mur souple & profil (B2C)**
- **Écran « Se connecter ou s'inscrire »** avec option claire **« Ne pas s'inscrire »** (mur souple).
- **Mur souple** : l'invité parcourt le mur et ouvre les fiches en lecture seule, **prix en FCFA uniquement** ; le mur se lève à la **première action engageante** (Like, Favori, avis, publier, suivre, **ou changement de devise**). Un **message** au choix « Ne pas s'inscrire » explique que l'inscription débloque l'affichage en **€ / $ / ₦**.
- **Langue détectée automatiquement** (repli déterministe, bascule non bloquante).
- **Authentification** : **Email** (→ OTP → **création de mot de passe** → Nom/Prénom → Ville/Localisation → **Devise, XOF par défaut** → Validation) **ou Google** (→ écran de révision Nom/Prénom pré-remplis modifiables → Ville/Localisation → Devise → Validation). **Sign in with Apple sur iOS uniquement** (obligatoire dès qu'un login social tiers est présent). **Ni téléphone ni âge.** Mot de passe oublié.
- **Flux d'activation Promoteur** distinct (comptes pré-inscrits par l'équipe Kwabor) : lien d'invitation → « Activez votre compte {Nom} » → mot de passe / Google → tableau de bord avec **fiche déjà pré-remplie**.
- **Priming des permissions** : localisation intégrée à l'inscription ; **priming notifications après validation** du compte.
- **Profil** : cover, avatar, bio, **badge de rôle** ; onglets **Publications** (contenus sociaux de l'auteur), **Contenus publiés** (fiches, avec statuts/brouillons/rejets — pour les rôles créateurs), **Favoris**, **Statistiques** ; **édition de profil** ; **« Signaler un lieu manquant »** (option bien visible, ouverte à tous).
- **Paramètres** complets : Compte, Sécurité (mot de passe, 2FA, appareils), Préférences (notifications granulaires, dark mode), Internationalisation (langue, devise, format de date), À propos, Danger Zone.

**Rôles, contribution & Promoteur (B2B) — création réservée**
- **Création de fiche réservée** aux rôles habilités (aucune « suggestion » ouverte). Rôles : **Utilisateur** (social + signaler un lieu manquant), **Guide touristique** (catégorie de Promoteur, service listé dans **« Trouver un guide »** + événements), **Institution/Office** (fiches patrimoniales + enrichissement), **Promoteur commercial** (fiches Commercial/Événementiel), **Admin Kwabor** (seed de masse, back-office, **profil public à badge vérifié**). Voir §6.11.
- **Bouton « + » contextuel** selon le rôle (§6.0).
- **Compte Promoteur** : inscription pro + vérification du justificatif (formulaire spécifique aux **guides**), **revendication de fiche** (claim) — **uniquement pour les classes Commercial/Événementiel**.
- **Tableau de bord Promoteur** : gestion des fiches, **gestion + réponse aux avis**, section **« Promouvoir »** (campagnes sponsorisées + ciblage), **statistiques séparées organique/payé**, **facturation**.
- **Équipe d'organisation vérifiée** : les promoteurs, institutions et Admin Kwabor peuvent inviter des membres avec rôles cumulatifs **Propriétaire > Gestionnaire > Éditeur > Modérateur**. Les droits financiers et d'équipe restent contrôlés par Propriétaire/Gestionnaire selon budget autorisé.
- **Mise en avant éditoriale** (gratuite, décidée par Kwabor, pour le Patrimonial/événements) — **distincte** du sponsoring payant.
- **Paiement Mobile Money** (XOF) des campagnes : opérateurs **MTN MoMo** et **Moov Money** via agrégateurs (**CinetPay**, **FedaPay**), validation côté serveur.

**Transverse**
- **Langue : FR** livrée ; i18n complète câblée pour les 6 langues.
- **Devises d'affichage : XOF, NGN, USD, EUR** (conversion indicative ; **€/$/₦ réservés aux comptes**).
- **Licence de contenu UGC + procédure de retrait** dans les CGU (acceptées à l'inscription).
- **Applications Android/iOS uniquement**, offline partiel sur le mur (cache lecture seule), bannière offline persistante.

### 5.2 V1.1 (post-lancement rapide)
- **Langue : Anglais (EN).**
- **Vidéo** dans le feed Social (le Social existe dès le MVP en photo/diaporama) : format vertical, auto-play mute, **commentaires**, **watermark non maskable**.
- **Vue du profil d'un tiers** enrichie + suivi de créateurs.
- Ciblage avancé des notifications sponsorisées (ville + centres d'intérêt).
- Statistiques promoteur avancées (**heat-map de provenance des vues**).
- Entrée **vocale** dans l'assistant IA.

### 5.3 V1.2 et au-delà
- **Langues : Portugais, Allemand, Espagnol, Italien** (déploiement progressif).
- **Publication croisée TikTok** depuis le réseau social (connexion, publication, statistiques cross-plateforme) — §6.16.
- **Cession de fiches patrimoniales** par Admin Kwabor aux institutions appropriées (transfert de stewardship).

### 5.4 Hors périmètre (V2+, à challenger en Discovery)
- Réservation/paiement **in-app** de tables ou de chambres (le MVP redirige vers WhatsApp/téléphone/site).
- Billetterie événementielle à paiement **intégré** (le MVP redirige vers une URL externe).
- Programme de fidélité multi-établissements.
- Toute autre version applicative. Le produit V1 est mobile-only Android/iOS.
- Extension à d'autres pays (Kwabor est **mono-pays Bénin** par décision produit).

### 5.5 Hors produit
- Modération humaine 24/7 (V1 : modération a posteriori + signalement utilisateur + règles automatiques).

---

## 6. Exigences fonctionnelles détaillées

> Cette section pose le **comportement produit**. Le rendu visuel précis (tokens, dimensions, composants, états) est défini dans `DESIGN.md`.

### 6.0 Architecture de navigation

**En-tête d'accueil — allégée.** L'en-tête de l'écran Explore porte uniquement, de haut en bas : la **localisation** (ville courante au Bénin, modifiable), le **titre** `Découvrez le Bénin`, les **onglets principaux** (Lieux / Événements / Hôtels & Restaurants), la **barre de recherche** (avec bouton filtre apparié) et une rangée de **chips de sous-catégories** propres à l'onglet actif. L'en-tête **ne porte ni la cloche de notifications ni l'avatar de profil** : ces deux destinations vivent exclusivement dans la barre basse, pour éviter tout doublon de point d'entrée.

**Barre de navigation basse — plate, 5 items égaux.** `Accueil · Social · Ajouter · Notifications · Profil`. **Pas de bouton central surdimensionné** : c'est un pattern de **découverte/consommation** (type Airbnb, Google Maps) où la recherche, en haut de l'écran, reste le point d'entrée dominant. **Chaque destination est présente une seule fois** dans toute l'app.
- **Social** ouvre le feed communautaire façon TikTok (photo/diaporama dès le MVP, vidéo en V1.1) — §6.15.
- **Ajouter (« + ») est contextuel selon le rôle** (§6.11) : Utilisateur → contenu social uniquement ; Guide → social + événement + gestion de son service ; Promoteur → social + événement + fiche ; Institution → social + fiche patrimoniale + enrichissement.
- En **mur souple**, toucher une destination réservée (Like, Favori, publier, suivre, changer de devise) déclenche l'invite d'inscription (§6.9).

**Hiérarchie onglets vs chips.** Les **onglets** (Lieux / Événements / Hôtels & Restaurants) sont la navigation primaire entre les trois types d'entités. Les **chips** sous la recherche affinent l'onglet actif (sous-catégories) :
- Lieux → Plages / Historique / Marchés / Nature…
- Événements → Concerts / Festivals / Conférences / Randonnées…
- Hôtels & Restaurants → Hôtels / Restaurants / Maquis / Bars / Cafés…

Un seul composant de chips, alimenté par une liste différente selon l'onglet. Les chips assurent le **tri rapide (1 tap)** ; le bouton filtre ouvre le **drawer complet** (ville, prix, date, type). Deux niveaux d'effort, jamais redondants.

**Assistant IA & Surprenez-moi — un seul FAB.** L'IA est le point d'entrée d'un **FAB flottant unique** sur les écrans Explore (§6.4). Pour éviter deux boutons empilés, **« Surprenez-moi » est une action secondaire révélée par appui long** sur ce FAB (mini menu à deux entrées : *Assistant IA* / *Surprenez-moi*). **Décision actée.**

**Accès Promoteur.** Le tableau de bord Promoteur est accessible via **Profil → sélecteur Personnel/Promoteur** (visible uniquement pour un compte habilité et vérifié). Ce sélecteur est l'unique pont vers la face B2B (§6.11 rôles, §6.12 espace Promoteur).

### 6.1 Module Explore (mur d'exploration)
Trois sous-écrans partagent un layout commun : onglets, recherche + filtre, chips de sous-catégories, **grille 2 colonnes de cartes visuelles plein cadre** (ratio 3:4, image cover + overlay dégradé, **titre et infos posés sur l'image**), FAB IA.

| Sous-écran | Spécificité fonctionnelle |
|---|---|
| **Lieux** | Tri par défaut : popularité (vues + likes pondérés). Pull-to-refresh. Sous-catégories : Plages, Historique, Marchés, Nature… |
| **Événements** | Pastille de date sur la carte ; **ruban « Terminé »** si date passée ; tri par défaut = proximité temporelle ; filtre (ville, date, type, popularité). |
| **Hôtels & Restaurants** | **Première rangée réservée aux cartes sponsorisées** ; chip gamme de prix ; filtre (ville, type, gamme de prix, popularité). |

**Règles métier clés :**
- **Transparence publicitaire (non négociable).** Une carte sponsorisée est toujours identifiable par un **badge texte « Sponsorisé » jaune** — la couleur seule ne suffit jamais. Sur le système monochrome, ce badge est volontairement le seul élément coloré de la grille. Le mot « Sponsorisé » est traduit selon la langue active. **Plafond : 2 slots sponsorisés maximum par grille de 6 cartes visibles.**
- **Lisibilité du texte sur image.** Titre et infos restent en contraste **AA (≥ 4.5:1)** : overlay obligatoire calibré (35–45 % selon densité de texte), texte toujours dans le **tiers bas** de la carte.
- **Prix sur carte** : affichés en mode **compact** dans la devise d'affichage de l'utilisateur (§6.14).
- **États requis** sur chaque sous-écran : chargement (skeleton), erreur réseau (toast + retry), vide (empty state + CTA), offline (bannière persistante).

### 6.2 Module Détail (Lieu / Établissement / Événement)
Présentation **immersive** en **modal sheet remontante (92 % hauteur)**, ouverte depuis n'importe quel point d'entrée (carte, notification, résultat IA, tirage Surprenez-moi, recherche). **Un seul composant `DetailSheet` paramétrable par type.** L'immersion plein écran est le rendu de cette sheet, pas un nouvel écran.

**Structure commune (haut → bas) :**
1. **Hero plein cadre** (image/vidéo) + overlay dégradé ; pastilles flottantes **Retour** (haut-gauche) et **Favori** (marque-page, haut-droite) ; **titre posé sur le hero** (tiers bas) + label de contexte (ville / catégorie / date) ; **preuve sociale** (compteur de visiteurs/intéressés, mini-pile d'avatars, note).
2. **Sheet blanche remontante** : infos spécifiques au type, description tronquée + « Lire la suite », **rangée de miniatures** (slider, zoom, plein écran), **barre d'actions** (Like / Partager / Favori), **carte Google Maps** + CTA Itinéraire, **section avis** paginée (3 par lot, tri « Plus récents » / « Mieux notés ») avec **point d'entrée « Donner mon avis »**.
3. **Barre d'action collée en bas** : information clé à gauche (note / prix / date), **CTA principal** à droite.

**Spécificités par type (tout champ saisi au wizard §6.11 a un élément d'affichage ici — zéro champ orphelin) :**

- **Lieu :** label ville sur le hero ; stats vues/likes/note ; **horaires d'accès** (ou « Accès libre 24h/24 ») ; **équipements sur site** (parking, guide, toilettes, PMR…) ; **note tarifaire** sous le prix ; CTA bas = **Itinéraire** (noir).
- **Établissement :** badge catégorie (« Hôtel 4★ », « Restaurant »…) ; **pill d'ouverture dynamique** (Ouvert/Fermé, tap → **horaires 7 jours**) ; **rangée de services à icônes** ; **chip prix mode plein** (tap → **convertisseur de devise**, §6.14) ; selon le sous-type : **bloc « Types de chambres / tarifs »** (hébergement), **chips cuisine + bouton « Voir le menu »** (restauration), **caractéristiques + pill « Âge minimum »** (vie nocturne/club) ; **contact direct** (appel, WhatsApp, site, email) ; **liens réseaux sociaux** ; CTA bas = **Contacter** (noir), « à partir de … » à gauche.
- **Événement :** **pastille date** sur le hero ; date/heure + lieu (rattaché ou adresse) ; **bloc billetterie multi-tranches** (Standard, VIP… **prix en mode plein, jamais compact**) ; **infos organisateur** (nom + contact) ; capacité ; CTA bas = **« Acheter un billet » rouge** (deep-link externe si `URL_billet`, badge **« Gratuit »** si prix = 0).

**Revendication.** Sur toute fiche **non revendiquée**, un bandeau « Vous gérez cet établissement ? **Revendiquer** » ouvre le flow claim (§6.12.7).

**Signalement.** Toute fiche peut être signalée via la Share Sheet (motifs en §6.7).

**Règle métier — événement passé.** Dès que la date est passée : **ruban diagonal « Terminé »** (annoncé ARIA « Événement terminé »), **bouton billet désactivé/grisé**, CTA de contact masqué. **État distinct de la même fiche**, pas un écran séparé.

### 6.3 Recherche par mots-clés
La **barre de recherche** (en-tête Explore) traite la requête **par mots-clés** (nom, ville, catégorie, tags). Elle est **distincte de l'assistant IA** (§6.4) qui traite le **langage naturel** : deux niveaux d'intention, jamais redondants. C'est pourquoi la navbar ne porte **pas** d'entrée « Recherche » (elle doublonnerait la barre du haut).

**Comportement :**
- **État focus** (champ actif) : **recherches récentes**, **suggestions/autocomplétion** (lieux/établissements/événements), portée = onglet actif par défaut (basculable « Tout »).
- **Écran de résultats** : grille de cartes (§6.1) filtrée par la requête, conservant chips et filtres.
- **Empty state** « Aucun résultat » + suggestions (élargir la recherche, essayer l'assistant IA).
- Effacement de la requête, gestion de l'historique (effacer).

### 6.4 Assistant de découverte IA
- **Point d'entrée unique : FAB flottant** (icône `auto_awesome`) sur tous les écrans Explore.
- Conversation en **langage naturel, dans la langue de l'utilisateur** (ex. *« un restaurant chic et calme à Cotonou avec un bon vin »*).
- Retourne une **liste structurée de 3 à 5 cartes cliquables** ouvrant directement la fiche détail.
- S'appuie sur : le **catalogue** (recherche sémantique), la **localisation** utilisateur, l'**historique de favoris** (si autorisé), la météo/saisonnalité (optionnel V1.1).
- **Garde-fous (non négociables)** : l'assistant **n'invente jamais** un établissement absent du catalogue ; **ne promet jamais** une réservation ferme (il oriente vers le contact direct) ; **cite la source** (la fiche Kwabor) de chaque recommandation.
- **États requis** : indicateur de frappe ; **état « Aucun résultat dans le catalogue »** (au lieu d'une réponse fabriquée) ; **état d'erreur réseau / IA indisponible** (retry).
- **Entrée vocale** : optionnelle (V1.1).

### 6.5 Surprenez-moi
- Déclenché par **appui long sur le FAB IA** (§6.0).
- **Recommandation aléatoire pondérée** (popularité + proximité), excluant les favoris et les fiches récemment vues.
- Plein écran immersif (pas une sheet) : une carte hero, CTA « Voir la fiche » (ouvre le `DetailSheet`) + bouton « Relancer » (nouveau tirage).

### 6.6 Interactions sociales — Like, Favori, Partage, Signalement

**Définition stricte (lève l'ambiguïté historique) :**
- **Like = cœur.** Signal social **public** qui **incrémente `likes_count`** de la fiche. Animation cœur. Visible dans les stats (promoteur, profil). Présent sur la carte (pastille cœur, haut-droite) et dans la barre d'actions de la fiche.
- **Favori = marque-page.** Sauvegarde **personnelle** dans l'onglet **Profil → Favoris** (§6.9). N'affecte pas `likes_count`. Présent sur la carte (selon densité) et dans la barre d'actions de la fiche. **C'est l'unique mécanisme de sauvegarde** ; il n'existe pas d'onglet Favoris en navbar.
- **Partage.** Ouvre la **Share Sheet** (réseaux, WhatsApp en premier — usage local ; copier le lien ; télécharger ; **Signaler**).
- **Signalement.** Disponible sur une **fiche**, un **avis**, ou une **vidéo** (V1.1). Motifs : Spam / Inapproprié / Information erronée / Faux compte-avis / Autre (champ libre si Autre). Le contenu n'est pas masqué côté signalant ; un seuil de signalements déclenche la revue (§6.7).

**Hors-ligne** : Like et Favori sont mis en **file locale** et synchronisés à la reconnexion (§8).

### 6.7 Avis & modération de contenu
- **Avis** = note (1–5) + texte + photos optionnelles + **Like d'avis** + lien **Signaler**.
- **Écriture d'avis** : sheet dédiée (sélecteur d'étoiles, texte, photos, Publier). Accessible depuis le `DetailSheet`. Mode édition si l'utilisateur a déjà noté. Connexion requise.
- **Tri** : « Plus récents » / « Mieux notés ». Pagination par lots de 3.
- **Réponse promoteur** : le promoteur peut **répondre publiquement** à un avis sur sa fiche (libellé « Réponse du gérant », génère une **notification sociale** à l'auteur de l'avis) — §6.12.4.
- **Seuil de signalement** : un avis signalé **3 fois** (configurable) est **automatiquement masqué** en attente de revue modérateur.
- **Pipeline de modération hybride** : règles automatiques (mots-clés, **GPS dans le Bénin**, détection de doublon, contrôle image) → **file de revue humaine** (§6.11, §8).

### 6.8 Centre de notifications proactif
Destination permanente de la navbar (badge pastille rouge quand non-lu). **Quatre familles :**
1. **Suggestions personnalisées** (algorithmiques : favoris, localisation, comportement).
2. **Contenu sponsorisé** (payé par un promoteur, **toujours labellisé « Sponsorisé »** — badge texte jaune obligatoire, jamais la couleur seule).
3. **Nouveautés** (nouveau lieu/établissement publié à proximité).
4. **Alertes événementielles** (événements à venir dans la ville de l'utilisateur).

**Comportement & règles :**
- Liste verticale **groupée par temporalité** (Aujourd'hui / Cette semaine / Plus tôt), **états lu/non-lu**, action **« Tout marquer comme lu »**, **swipe pour masquer**, **clearing du badge** navbar à l'ouverture.
- **Tap → `DetailSheet`** en deep-link selon le type.
- Lien direct vers **Paramètres → Préférences → Notifications**.
- **Fréquence plafonnée** par défaut (anti-spam), **configurable** par l'utilisateur, **opt-out granulaire par famille**.
- **Confidentialité par conception** : le ciblage promoteur (ville + centres d'intérêt) ne donne **jamais** accès à des données nominatives ; le promoteur choisit des critères, la plateforme exécute l'envoi.
- **États** : empty state, skeleton, erreur, offline.

### 6.9 Compte, onboarding & profil

**Onboarding (premier lancement) — séquencement acté :**
1. **Intro vidéo** (premier lancement uniquement) : vidéo d'arrière-plan immersive, **sautable** ; la langue est **détectée automatiquement** pendant l'intro (§6.9.1). Fin de vidéo (ou « Passer ») → écran suivant. Détail asset & performance en §6.9.3.
2. **Écran « Se connecter ou s'inscrire »** : fond plein écran, titre **« Découvrez le Bénin »** (langue détectée), **sélecteur de langue discret** en haut à droite, boutons **Se connecter** / **S'inscrire**, et option claire **« Ne pas s'inscrire »** (entre en mur souple).
3. **Choix de langue conditionnel** : **aucun écran par défaut** (voir §6.9.1) — sinon accessible via le sélecteur discret et les Paramètres.
4. **Authentification** (§6.9.2) : Email **ou** Google (**+ Apple sur iOS**). Le choix de **devise** (XOF par défaut) et la **ville/localisation** sont intégrés au parcours d'inscription. **Acceptation des CGU + politique de confidentialité** (dont **licence de contenu UGC**, §6.11) obligatoire.
5. **Priming des permissions** : la **localisation** est demandée pendant l'inscription (étape ville) ; le **priming notifications intervient après la validation** du compte (ou au premier moment pertinent, ex. première mise en favori).
6. **Mot de passe oublié** : flow dédié (identifiant → OTP → nouveau mot de passe).

**6.9.1 Détection automatique de la langue (repli déterministe).** Principe : **respecter la langue système sans intervention de l'utilisateur.** Algorithme :
1. Lire la **liste ordonnée** des langues préférées exposée par l'OS (iOS et Android en fournissent plusieurs, pas une seule) et retenir la **première langue livrée** rencontrée.
2. Comparer sur le **sous-tag langue en ignorant la région** (`pt-BR`/`pt-PT` → PT, `en-US`/`en-GB` → EN).
3. Si aucune langue préférée n'est livrée → **EN si livré, sinon FR** (au MVP, cette valeur est toujours FR, seule langue livrée).
4. Le résultat s'applique dès l'intro/le premier écran.
5. **Bascule non bloquante** : la langue reste modifiable à tout moment (sélecteur discret sur l'écran « Se connecter ou s'inscrire » + Paramètres → Internationalisation) ; le choix explicite **prime et persiste** aux lancements suivants.
6. L'écran de choix conditionnel n'apparaît que si la détection **ne peut pas trancher** (locale non couverte) **et** qu'il existe plusieurs langues à proposer.

**6.9.2 Authentification & inscription rapide.** Deux méthodes, plus Apple sur iOS. **Ni numéro de téléphone ni âge** ne sont demandés (le téléphone Promoteur est collecté au moment du paiement Mobile Money, §6.13 — donnée de transaction, distincte de l'identité).
- **Connexion.** Champ **email** + bouton **Continuer** → écran **mot de passe** ; **« Se connecter avec Google »** ; **« Se connecter avec Apple » (iOS)**.
- **Inscription par email** (formulaire multi-étapes rapide) : `Email` → `Code OTP` (envoyé automatiquement, saisi à l'écran suivant) → **création du mot de passe** → `Nom / Prénom` (champs vides) → `Ville / Localisation GPS` (sélection **ou** autorisation de détection) → `Devise` (XOF par défaut) → **Validation**.
- **Inscription par Google** : `Auth Google` → **écran de révision** (Nom / Prénom **pré-remplis, modifiables en 1 tap**, bouton unique **Continuer**) → `Ville / Localisation GPS` → `Devise` → **Validation**.
- **Sign in with Apple — iOS uniquement** : proposé au même niveau que Google sur iOS (exigence App Store dès qu'un login social tiers est offert) ; parcours d'inscription identique à Google (révision → ville → devise → validation).

**6.9.3 Écran d'intro vidéo.** Objectif : immerger instantanément dans l'univers touristique, culturel et festif du Bénin.
- **Asset embarqué** dans l'app (lecture **indépendante du réseau**, instantanée au premier lancement) **et remplaçable à distance** (remote config + CDN, précaché) — permet de varier la vidéo saisonnièrement (Vodun Days, festivals) **sans republier** sur les stores. Au MVP, l'**emplacement et toute la mécanique sont provisionnés**, l'asset final est fourni séparément.
- **Spec d'asset stricte** : format **vertical natif** (cadré pour écrans mobiles allongés), **H.264** (universel Android/iOS), **muet**, durée **15–25 s**, **budget de poids serré** (viser ~2–3 Mo).
- **Sautable** (bouton « Passer » visible immédiatement) ; **premier lancement uniquement** (les lancements suivants vont directement à l'auth ou à l'accueil) ; **fallback image statique** si `prefers-reduced-motion`. **Analytics : taux de skip** pour vérifier que la vidéo mérite sa place.

**6.9.4 Mur souple (soft wall) — acté.** L'invité **parcourt le mur et ouvre les fiches en lecture seule** ; les **prix s'affichent en FCFA uniquement**. Le mur se lève (invite d'inscription) à la **première action engageante** : **Like, Favori, avis, publier, suivre, ou changement de devise**. Au choix **« Ne pas s'inscrire »**, un **message** explique que l'inscription débloque l'affichage en **€ / $ / ₦** (et l'ensemble des interactions). Ce compromis protège la conversion (le touriste voit d'abord le contenu) tout en cadrant les actions à valeur.

**6.9.5 Activation des comptes Promoteur pré-inscrits.** L'équipe Kwabor pré-inscrit des promoteurs en amont (cf. stratégie de seed, §12). Ces comptes suivent un **flux d'activation distinct**, pas une inscription à froid : **lien d'invitation** (email/WhatsApp) → l'app ouvre **« Activez votre compte {Nom du commerce} »** → définir un mot de passe **ou** lier Google/Apple → vérification → **accès direct au tableau de bord** avec la **fiche déjà pré-remplie**.

**Profil utilisateur :** cover, avatar, bio, **badge de rôle** (Promoteur, Guide, Institution, **Admin Kwabor** — badge vérifié), bouton **Modifier le profil**, **sélecteur Personnel/Promoteur** (si habilité), **« Signaler un lieu manquant »** (option bien visible, ouverte à tous — §6.11). Onglets :
- **Publications** — contenus **sociaux** de l'auteur (photos/diaporamas ; vidéos en V1.1), chacun rattaché à une entité. Point d'accès public au contenu communautaire de l'utilisateur.
- **Contenus publiés** — *(rôles créateurs)* fiches de l'utilisateur avec **badge de statut** (Brouillon / En attente / Publié / **Rejeté** + motif + « Corriger et resoumettre »). Reprise d'un brouillon, mise à jour, archivage, accès aux stats.
- **Favoris** — grille des fiches favorites (filtrable par type, ruban « Terminé » conservé). **Unique point d'accès aux favoris.**
- **Statistiques** — vues/likes des contributions de l'utilisateur ; **distinct** du tableau de bord Promoteur (§6.12.6).

**Édition de profil :** avatar, cover, nom, bio (recadrage, validation, compteurs).

**Vue du profil d'un tiers** : Publications publiques, compteurs d'abonnés, **bouton Suivre** (réseau social §6.15).

### 6.10 Paramètres
Sections et **sous-écrans** (tous spécifiés dans `DESIGN.md`) :
- **Compte** : identité, email/téléphone, **statut de vérification** (chip vert/ambre/rouge).
- **Sécurité** : **changer le mot de passe**, **2FA** (activation, codes de secours), **appareils connectés** (déconnecter une session).
- **Préférences** : **notifications granulaires par famille** + **plafond de fréquence**, **Dark Mode** (bascule instantanée).
- **Internationalisation** : **langue** (6, nom natif), **devise d'affichage** (4), **format de date** (localisé).
- **À propos** : **version**, **licences**, **politique de confidentialité**.
- **Comptes liés** *(V1.2)* : TikTok (`@username`, connecter/déconnecter).
- **Danger Zone** : **Supprimer le compte** (rouge, dialog irréversible + ré-authentification), **Se déconnecter**.

> **Dark mode.** L'accent étant le noir, le mode sombre impose une **inversion** : les éléments actifs noirs (FAB, item de nav actif, CTA) passent en **surface claire** sur fond sombre, sinon ils deviennent invisibles. Détail des tokens en `DESIGN.md`.

### 6.11 Rôles, classes de fiche & création de contenu

Kwabor sépare **deux axes indépendants** : la **classe de la fiche** (sa nature) et le **rôle de l'acteur**. Ce croisement gouverne toutes les permissions et supprime toute ambiguïté sur « qui peut faire quoi ».

**6.11.1 Trois classes de fiche (implicites).** La classe n'est **jamais choisie par l'utilisateur** : elle est **dérivée** du créateur et du sous-type ; **Admin Kwabor** peut la corriger.

| Classe | Exemples | Propriétaire | Revendicable (claim) |
|---|---|---|---|
| **Patrimonial** | Plages, forêts, monuments, sites historiques, parcs naturels, cités lacustres, marchés publics, musées, lieux de culte, points de vue | Aucun propriétaire privé (`owner_id` = NULL) ; **steward** institution possible | **Non** |
| **Commercial** | Hôtels, restaurants, bars, maquis, cafés, clubs **et** lieux commerciaux (parcs d'attraction, aires de jeux, galeries d'art…), **service de guide** | Promoteur (au claim/création) | **Oui** |
| **Événementiel** | Concerts, festivals, conférences, expositions, spectacles, sport, randonnées | Organisateur (Promoteur/Guide) | **Oui** |

> Un « Lieu » n'est donc pas toujours patrimonial : un parc d'attraction ou une galerie créés par un promoteur sont de classe **Commercial** et s'affichent sous **Lieux**. Le **claim n'existe que pour Commercial/Événementiel** — une plage ou un monument n'est jamais revendicable par un privé.

**6.11.2 Rôles & matrice de permissions.** Rôles cumulables (un promoteur peut aussi être guide). Chaque rôle élevé a **sa propre vérification**.

| Rôle | Consulter · Like · Avis | UGC social (rattaché) | Signaler un lieu manquant | Créer une fiche | Enrichir | Posséder / Revendiquer | Répondre aux avis | Promouvoir (payant) |
|---|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| **Utilisateur** | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| **Guide** *(catégorie de Promoteur)* | ✓ | ✓ | ✓ | ✓ *service de guide + Événementiel* | ✗ | ✓ *ses fiches* | ✓ *ses fiches* | ✓ *ses fiches* |
| **Institution / Office** | ✓ | ✓ | ✓ | ✓ *Patrimonial* | ✓ *Patrimonial* | Steward *(non commercial)* | ✓ *ses fiches patrimoniales* | ✗ *(mise en avant éditoriale)* |
| **Promoteur commercial** | ✓ | ✓ | ✓ | ✓ *Commercial/Événementiel* | ✓ *ses fiches* | ✓ *Commercial/Événementiel* | ✓ *ses fiches* | ✓ *ses fiches* |
| **Admin Kwabor** | ✓ | ✓ | traite les signalements | ✓ *toutes classes* | ✓ *toutes* | attribue owner/steward | ✓ | ✓ / éditorial |

- **Aucune « suggestion » ouverte de fiche.** L'utilisateur ne crée pas de fiche ; il dispose du canal **« Signaler un lieu manquant »** (formulaire, option bien visible dans le Profil) → **file de traitement Admin Kwabor**. C'est un signalement modéré, pas une création.
- **Guide** = **catégorie de Promoteur commercial** avec un **formulaire de vérification spécifique** (agrément/carte, **langues parlées, zones couvertes, spécialités, tarifs**). Son offre est une **fiche « Service de guide »** (classe Commercial) exposée dans l'espace **« Trouver un guide »** ; il peut aussi **publier des événements**. **Il n'enrichit pas** le patrimonial.
- **Institution** enrichit et peut être **steward** (responsable éditorial) d'une fiche patrimoniale. **Admin Kwabor** pourra plus tard **céder** la stewardship de lieux publics aux institutions appropriées.
- **Admin Kwabor** dispose d'un **profil public à badge vérifié** ; le **seed de masse** (patrimonial + comptes promoteurs pré-inscrits) se fait en **back-office**, hors app grand public.

**6.11.3 Bouton « + » contextuel selon le rôle.**
- **Utilisateur** → **Publier un contenu social** (photo/diaporama ; vidéo en V1.1) **uniquement** + accès « Signaler un lieu manquant ».
- **Guide** → contenu social + **Événement** + gestion de son **service de guide**.
- **Promoteur commercial** → contenu social + **Événement** + **Fiche** (Commercial).
- **Institution** → contenu social + **Fiche patrimoniale** + **enrichissement**.

**6.11.4 UGC (contenu communautaire).** Photos et diaporamas au MVP (**vidéo en V1.1**).
- **Rattachement obligatoire et techniquement bloquant** à une entité du catalogue (Lieu / Établissement / Événement) : impossible de publier sans avoir sélectionné une entité. Un **message contextuel** — « vos médias doivent avoir un lien avec un lieu ou un événement de l'app » — s'affiche **à l'ouverture du composeur**.
- **Deux entrées** : depuis une **fiche** (entité **pré-remplie**, publication en 1 tap) ou via le **« + » global** (sélection obligatoire de l'entité).
- **Affichage** : dans le **feed Social** et le **profil de l'auteur** (onglet Publications) — **jamais sur la fiche** (fiche = catalogue only). La **mention** ouvre un aperçu de fiche ≤ 25 % (§6.15).
- **Modération UGC** : contrôle image automatique + signalement ; **watermark non maskable** sur tout export hors app ; **licence de contenu** accordée à Kwabor + **procédure de retrait** (CGU).

**6.11.5 Wizard d'enregistrement de fiche** (`ListingWizard`, rôles créateurs uniquement, paramétré par type puis sous-type) : Type & base → Localisation (map picker biaisé Bénin, **GPS obligatoirement dans le Bénin**) → **Détails** (branche dynamique selon le sous-type) → Médias (**≥ 1 photo cover requise, ≥ 3 recommandées**, drag-reorder, alt text éditable) → **Récap & publication** (preview réelle de la fiche + de la carte) → statut de modération. **Brouillon auto-sauvegardé.** **Prix saisis en XOF** (affichage multidevise calculé, §6.14).

**6.11.6 Cycle de vie & édition.**
- `Brouillon → En attente → Publié` ; `Rejeté (+ motif) → corriger → Brouillon` ; `Publié → Archivé` (manuel, ou automatique après un événement terminé + délai).
- **Une fiche est publiée une seule fois** puis **mise à jour autant de fois que voulu** par son gestionnaire (photos, vidéos, description, horaires, prix…).
- **Édition sensible** (nom, type/sous-type, GPS, prix, billetterie) → **re-modération** (l'ancienne version reste en ligne) ; **édition mineure** (photo, description, horaires) → publication directe + journalisation.

> Le **modèle de données complet** (`listing_class`, rôles, guide, UGC, signalements de lieu manquant, contrainte de claim, stewardship, activation), la taxonomie des sous-types, les champs par sous-type, les permissions RLS et les flux de modération/édition/revendication sont spécifiés dans `DESIGN.md` (section « Wizards d'enregistrement de contenu »).

### 6.12 Espace Promoteur & contributeurs vérifiés (B2B)

> Tout ce module est financé par la monétisation (§10) et constitue le cœur du modèle au MVP. Les rôles et leurs droits sont définis en §6.11.

**6.12.1 Inscription & vérification.** Parcours pro distinct selon le rôle visé, avec **upload d'un justificatif** et **vérification** (manuelle ou semi-automatisée) avant activation du **badge « Vérifié »** ; états En attente / Vérifié / Refusé (+ motif + recours).
- **Promoteur commercial** : informations d'activité + justificatif (registre de commerce, facture, photo de devanture).
- **Guide touristique** *(catégorie de Promoteur)* : **formulaire spécifique** — agrément/carte de guide, **langues parlées, zones couvertes, spécialités, tarifs**, années d'expérience. Son offre devient une **fiche « Service de guide »** (classe Commercial) exposée dans **« Trouver un guide »** (§6.12.9).
- **Institution / Office** : accréditation ; droits d'édition/enrichissement du **Patrimonial** et **stewardship** (§6.11).
- **Activation des comptes pré-inscrits** : flux dédié (§6.9.5), sans inscription à froid.

**6.12.2 Sélecteur Personnel/Promoteur.** Bascule de contexte sur le Profil (visible si habilité et vérifié) menant au tableau de bord.

**Équipe d'organisation vérifiée.** Une organisation vérifiée représente un promoteur, une institution, un établissement ou une entité interne Kwabor. Elle peut avoir plusieurs membres avec droits cumulatifs :

| Rôle équipe | Droits |
|---|---|
| **Modérateur** | Répondre uniquement aux avis/messages clients. |
| **Éditeur** | Droits Modérateur + modifier les fiches autorisées, ajouter médias, publier événements, créer/programmer des publicités avec budget alloué. |
| **Gestionnaire** | Droits Éditeur + gérer l'équipe, inviter/suspendre membres, attribuer Éditeur/Modérateur, allouer les budgets autorisés. |
| **Propriétaire** | Droits Gestionnaire + budget global, paiements, moyens de paiement, suppression, transfert de propriété, paramètres critiques. |

Règles financières : un Éditeur ne peut consommer que son budget alloué ; un Gestionnaire ne peut allouer que le budget autorisé ; seul le Propriétaire contrôle les moyens de paiement et le budget global.

**6.12.3 Tableau de bord.** Vue d'ensemble : sélecteur de fiche gérée ; stats clés (vues, likes, **clics itinéraire**, **clics contact**) ; accès à : Mes fiches · Avis · **Promouvoir** · Statistiques · Facturation.

**6.12.4 Gestion des avis.** Liste des avis des fiches gérées (signalés mis en avant) ; **réponse publique** (composeur) ; rendu de la réponse sous l'avis côté public (§6.7).

**6.12.5 « Promouvoir » — campagnes sponsorisées.** Création d'une campagne : objet (**carte sponsorisée** Explore ou **notification sponsorisée**) → **ciblage** (ville(s) + centres d'intérêt, confidentialité par conception) → période/budget → **aperçu** (rendu réel avec **badge « Sponsorisé »**) → **coût en XOF (mode plein)** → paiement.
> **Mise en avant éditoriale** *(distincte du sponsoring payant)* : Kwabor peut mettre en avant **gratuitement** des fiches **patrimoniales** et des **événements** (curation), pour garder le fil vivant sans le vendre. Jamais labellisée « Sponsorisé » (ce n'est pas de la publicité) ; décidée par Admin Kwabor / institutions stewards.

**6.12.6 Statistiques (non négociable).** Vues, likes, clics itinéraire, clics contact — **par fiche et consolidées**. Les statistiques **organiques** et **payées** restent **strictement séparées et labellisées par origine** ; **jamais fusionnées** dans un même chiffre (même principe que la non-fusion des compteurs cross-plateforme TikTok, §6.16). **Heat-map de provenance des vues** en V1.1.

**6.12.7 Revendication de fiche (claim) — Commercial/Événementiel uniquement.** Sur une fiche **Commercial ou Événementiel** non revendiquée (typiquement pré-créée par le seed Admin Kwabor), le promoteur clique « Revendiquer » → **preuve** (justificatif + coordonnées) → **file de vérification** → Approuvé (`owner_id` = promoteur, badge « Vérifié », édition complète + réponses aux avis + promotion) / Refusé (motif + recours). **Conflit** (2 revendications) : priorité au justificatif vérifié, arbitrage modération. **Les fiches Patrimoniales ne sont jamais revendicables** (pas de propriétaire privé ; stewardship attribuée par Admin Kwabor).

**6.12.8 Facturation.** Liste des campagnes (statut, période, **coût XOF**, performance) ; détail de campagne ; **historique des transactions** ; **factures téléchargeables**.

**6.12.9 « Trouver un guide ».** Espace de découverte dédié aux **services de guide** (fiches Commercial de sous-type guide) : recherche/filtre par ville, langue, spécialité ; carte de guide (photo, langues, zones, tarif indicatif, note) → fiche détail → contact direct. C'est là que le guide « propose ses services ».

### 6.13 Paiement Mobile Money
- **Devise des transactions Promoteur : XOF uniquement.** Aucune conversion au paiement ; les autres devises restent un confort d'**affichage** côté utilisateur (§6.14).
- **Opérateurs béninois** : **MTN MoMo** et **Moov Money**, via agrégateurs (**CinetPay**, **FedaPay**).
- **Flow** : récap (montant XOF, mode plein) → choix opérateur → saisie numéro / déclenchement (USSD ou redirection agrégateur) → **écran de statut** (en cours / réussi / échec + retry) → **reçu/facture** (2 décimales autorisées en contexte facture).
- **Sécurité** : **validation côté serveur uniquement** (jamais côté client), orchestration anti-fraude.

### 6.14 Devises, prix & formats localisés
Kwabor affiche des prix dans **quatre devises** et **six langues** à terme. Le système garantit un affichage **équilibré, précis et honnête** quelle que soit la combinaison.

- **Principe 1 — XOF référence.** Tous les prix sont **saisis et stockés en XOF**. Les autres devises sont des **affichages convertis**, marqués du préfixe **`≈`** (le paiement réel se fait en XOF).
- **Principe 2 — formatage par la locale (ICU/CLDR)**, jamais concaténé à la main : FR `150 000 FCFA` · EN `150,000 FCFA` · DE `150.000 FCFA`. Symboles localement reconnus : **FCFA** (jamais « XOF »), **₦**, **$**, **€**.
- **Principe 3 — décimales par devise.** XOF & Naira : **0 décimale**. USD & Euro : **0** en navigation/liste, **2** uniquement en contexte transactionnel/facture.
- **Principe 4 — deux modes d'affichage.** **Compact (k/M)** sur surfaces denses (chip de carte, épingle, sparkline de stats), **réservé aux montants ≥ 10 000** ; **Plein** sur fiche, billet, paiement, dashboard. **Règle d'or non négociable : un prix transactionnel (billet, facture, coût de campagne) n'est JAMAIS en compact.**
- **Principe 5 — composant prix à largeur réservée + alignement constant** (chiffres tabulaires), pour que le layout ne « saute » pas entre `≈ $40` et `150 000 FCFA`.
- **Conversion** : service de taux backend (XOF → NGN/USD/EUR), rafraîchi périodiquement (source et fréquence — §14) ; valeurs **toujours indicatives** (`≈`).
- **Convertisseur** : taper un prix en mode plein ouvre une sheet listant les 4 devises (`≈`) + rappel « paiement en XOF ».

### 6.15 Réseau social communautaire (feed façon TikTok) — *MVP*
Feed vertical plein écran mêlant **photo unique** et **diaporama** dès le MVP ; **la vidéo arrive en V1.1**. C'est le lieu du contenu communautaire (§6.11.4) : chaque publication est **rattachée à une entité** du catalogue.
- **Format** : portrait verrouillé, swipe vertical, auto-scroll ; photo/diaporama (pagination par tap ou glissement horizontal sur un diaporama) ; **double-tap = Like** ; long-press = pause + télécharger/signaler. En V1.1, la vidéo s'ajoute (auto-play mute, boucle) sans changer le modèle.
- **Contenu depuis les créateurs** : à l'image des « événements à venir » qui s'affichent sur les contenus d'un créateur, l'entité rattachée apparaît en **mention** sur le contenu.
- **Aperçu de fiche par la mention** : au tap sur la mention, un **aperçu de la fiche s'ouvre au-dessus du contenu, à ≤ 25 % de hauteur** (le contenu reste visible dessous). **Taper le contenu** referme l'aperçu (on continue de scroller) ; **taper l'aperçu** ouvre la **fiche détail complète**.
- **Suivi de créateurs**, Like, partage ; **commentaires** en V1.1 (panel dédié : liste, saisie, like/réponse, signaler).
- **Watermark obligatoire et non maskable** (logo Kwabor + `@username`) sur tout média téléchargé/partagé hors app (photos **et** vidéos).
- **Où vit l'UGC** : feed Social + **profil de l'auteur** (onglet Publications). **Jamais sur la fiche** (fiche = catalogue only).

### 6.16 Publication croisée TikTok *(V1.2)*
Permettre à un créateur de publier un contenu simultanément dans le réseau social Kwabor **et** sur son compte TikTok.

> **Préparation dès le MVP :** aucune UI ni flow OAuth TikTok avant V1.2, mais le **schéma de données est conçu « TikTok-ready »** dès le MVP (colonnes nullable réservées dans `linked_accounts` : `tiktok_user_id`, `tiktok_access_token`, `tiktok_refresh_token`, `tiktok_token_expires_at`, chiffrées au repos, invisibles côté client). Choix purement technique, zéro coût produit (§9).

**Périmètre :**
- **Connexion TikTok** : OAuth (TikTok Login Kit), depuis le composeur social ou Paramètres → Comptes liés ; révocable à tout moment ; **sheet de consentement éclairé** avant l'OAuth.
- **Publication croisée** : toggle optionnel « Publier aussi sur TikTok » ; **flow recommandé d'abord : Upload to Inbox / Share Kit** (l'utilisateur finalise depuis TikTok) ; légende pré-remplie éditable ; **watermark non maskable** intégré au fichier avant export.
- **Statistiques cross-plateforme** : compteurs **Kwabor** et **TikTok** **côte à côte, distincts et labellisés par source — jamais sommés**.

**Contraintes à anticiper :** audit applicatif TikTok obligatoire avant `video.publish` (à lancer tôt) ; expiration des tokens (24 h, refresh 365 j, côté backend) ; plafond ~15 publications/jour (message clair en cas de dépassement) ; pas de carrousel photo via l'API ; re-vérification des `privacy_level_options` avant chaque publication.

**Hors périmètre V1.2 :** badge de marque natif sur la vidéo TikTok, compteur unique fusionné, republication automatique en masse.

---

## 7. Parcours utilisateurs de référence

### 7.1 Léa — découverte passive → action
1. **Notification proactive** : *« Le nouveau restaurant La Terrasse vient d'ouvrir à Akpakpa et récolte déjà des avis élogieux. »*
2. Tap → **fiche Détail immersive** (deep link) : hero plein cadre, titre sur l'image, services à icônes, prix en FCFA.
3. Exploration des photos, lecture des avis, **ajout en Favori** (marque-page).
4. Plus tard : **FAB IA** → requête en langage naturel → 5 propositions → sélection.
5. **Contact direct** (appel / WhatsApp) via la barre d'action — hors app.

### 7.2 Anna — touriste lusophone
1. Ouvre Kwabor → **vidéo d'intro** (qu'elle peut passer) → écran « Se connecter ou s'inscrire ». **L'app s'affiche directement en portugais** (locale détectée, zéro tap). Elle choisit **« Ne pas s'inscrire »**.
2. **Explore en mur souple** Ouidah et Ganvié ; les fiches s'affichent en portugais, **prix en FCFA** ; un message lui indique que l'inscription débloque l'affichage en **€**.
3. Utilise le **FAB IA en portugais** : *« o que fazer em Ouidah em 2 dias »*.
4. Veut voir les prix en euros → **le mur se lève**, elle **crée un compte** (Google, 1 tap → révision nom → ville → devise **€**), puis sauvegarde des lieux en **Favori** et les partage à ses compagnons.

### 7.3 Moussa — promotion d'un événement
1. **Devient Promoteur** (justificatif vérifié) → **revendique** sa fiche restaurant.
2. Accède au tableau de bord via **Profil → Personnel/Promoteur**.
3. Crée une campagne dans **« Promouvoir »** → ciblage ville (Cotonou) + intérêts (restaurants, événements).
4. Diffusion programmée → **notification sponsorisée** (badge « Sponsorisé » jaune) auprès des utilisateurs ciblés.
5. **Paiement en XOF** (Mobile Money) → suivi des résultats (vues, clics, estimation) dans les **statistiques séparées** des stats organiques.

### 7.4 Koffi — guide qui expose ses services
1. **Devient Guide** via le parcours pro (formulaire spécifique : agrément, **langues**, **zones**, **spécialités**, **tarifs**) → vérifié.
2. Sa **fiche « Service de guide »** apparaît dans **« Trouver un guide »** (filtrable par langue/ville/spécialité).
3. Via le **« + »**, il publie un **événement** (sortie Ganvié dimanche) — pas de création de lieu, pas d'enrichissement.
4. Des voyageurs le trouvent en filtrant « portugais + Ouidah », consultent sa fiche, le **contactent en direct**.

### 7.5 Aïcha — utilisatrice qui publie du contenu social
1. Au restaurant *La Terrasse* (fiche existante), elle ouvre la fiche → **« Ajouter une photo »** (entité **pré-remplie**).
2. Un **message** rappelle que le contenu doit être lié à un lieu/événement de l'app ; elle publie un **diaporama**.
3. Son contenu apparaît dans le **feed Social** et sur son **profil** ; la **mention** *La Terrasse* ouvre un **aperçu ≤ 25 %** → fiche complète. Elle **ne peut pas** publier sans rattacher d'entité.

---

## 8. Exigences non fonctionnelles

| Catégorie | Exigence |
|---|---|
| **Performance** | Chargement initial du mur < **1,5 s P75** sur 3G/4G dégradée ; images AVIF/JPG/PNG, CDN, compression serveur, lazy-load, placeholder dégradé immédiat ; overlays/dégradés rendus en Compose/SwiftUI, jamais par des images lourdes (§1.4). Budget contrôlé **à chaque écran**. |
| **Offline / résilience** | Cache local du dernier mur (lecture seule) ; **bannière « Vous êtes hors ligne » persistante** ; **file locale** pour les actions (Like/Favori) synchronisée à la reconnexion ; saisie de brouillon de fiche autorisée (upload média et géocodage mis en file). Les fiches Détail ne sont pas garanties hors-ligne au MVP. |
| **Accessibilité (WCAG AA)** | Contrastes ≥ **4.5:1** sur tous textes/overlays (y compris titre sur hero et sur cartes) ; labels ARIA/VoiceOver sur tout composant interactif ; **focus order documenté par écran** ; **alt text** généré (éditable) par image ; cibles tactiles ≥ 44 px ; états annoncés (« Événement terminé », « Ouvre une app externe »…). |
| **Internationalisation** | **6 langues** (FR au MVP, EN en V1.1, puis PT/DE/ES/IT) ; **toute** l'UI via i18n dès le MVP ; **4 devises** d'affichage (conversion indicative, formatage ICU/CLDR) ; formats date/heure localisés ; **tolérance à l'expansion de texte** (DE +30–40 %), aucune largeur de label figée, tests pseudo-localisés. Pas de RTL (langues latines). |
| **Sécurité & confidentialité** | Auth sécurisée : **email + OTP + mot de passe**, **Google OAuth**, **Apple Sign-In (iOS)** ; TLS ; **secrets paiement/OAuth côté serveur uniquement** ; minimisation de données pour le ciblage (§6.8) ; **licence de contenu UGC + procédure de retrait** et politique de confidentialité accessibles depuis Paramètres → À propos. Pas de collecte de numéro de téléphone ni d'âge à l'inscription. |
| **Disponibilité** | Cible **99,5 %** de disponibilité backend en heures d'usage (18h–00h GMT, Bénin). |
| **Scalabilité** | Architecture capable d'absorber la croissance du catalogue et du trafic au Bénin sans refonte (objectif mono-pays). |
| **Modération** | Pipeline hybride : règles automatiques (mots-clés, GPS Bénin, doublon, image) + file de revue humaine pour contenu et comptes Promoteur. |

---

## 9. Architecture technique (vue produit)

> Détails d'implémentation en `DESIGN.md` et futurs ADR. Résumé orienté décisions produit :

- **Client : Kotlin Multiplatform mobile** — `shared` pour domaine, data, contrats, use cases et états ; Android en Compose Multiplatform ; iOS en SwiftUI natif. Aucune autre cible applicative n'est prévue.
- **Backend : Supabase** (PostgreSQL managé, Auth, Storage, Realtime pour compteurs et notifications, **Row Level Security**). RLS pilotée par le couple **rôle × `listing_class`** (§6.11) : lecture publique des fiches publiées ; écriture d'une fiche selon le rôle et la classe (Promoteur → ses fiches Commercial/Événementiel ; Institution/Admin → Patrimonial ; Utilisateur → aucun droit de fiche, seulement UGC et signalement). Colonnes/tables nullable « TikTok-ready » dès le MVP, chiffrées au repos, sans UI ni OAuth avant V1.2.
- **Authentification** : Supabase Auth — **email + OTP + mot de passe**, **Google OAuth**, **Apple Sign-In (iOS uniquement)** ; **flux d'activation** par lien invité pour les promoteurs pré-inscrits ; secrets OAuth côté serveur.
- **UGC & médias sociaux** : Storage + pipeline d'images (AVIF/JPG/PNG) ; **rattachement obligatoire** d'un média à une entité (contrainte applicative + FK) ; **watermark non maskable** à l'export ; modération image automatique ; **licence + retrait** journalisés.
- **Vidéo d'intro** : asset **embarqué** dans le binaire + **remplacement à distance** (remote config + CDN, précaché) ; H.264, muet, vertical, budget de poids serré ; **analytics de skip**.
- **Paiement Promoteur** : Mobile Money béninois (**MTN MoMo**, **Moov Money**) via agrégateurs (**CinetPay**, **FedaPay**) ; orchestration anti-fraude côté serveur ; **transactions en XOF uniquement**.
- **Devises & taux** : prix stockés en XOF ; service backend de taux de change pour l'affichage indicatif (NGN/USD/EUR) ; formatage localisé ICU/CLDR côté client.
- **i18n** : ressources structurées dès le MVP (clés des 6 langues, FR rempli en premier).
- **Assistant IA** : API Anthropic (Claude) **côté serveur** (clé jamais exposée) ; recherche sémantique sur le catalogue (embeddings + filtrage structuré) pour **ancrer les réponses** et éviter les hallucinations ; réponses dans la langue de l'utilisateur.
- **Cartographie** : Google Maps Platform (Static Maps en fiche + Directions pour le CTA Itinéraire).
- **Notifications push** : FCM (Android) / APNs (iOS), orchestrés par un service de campagne backend, segmentation ville/centres d'intérêt.
- **Médias** : pipeline d'images adaptatives (AVIF/JPG/PNG), variantes responsives, lazy-load, placeholder dégradé — condition du « premium sans poids ».
- **Vidéo (V1.1)** : stockage/transcodage adaptatif, streaming progressif (plafonds taille/durée — §14).
- **TikTok (V1.2)** : TikTok Login Kit + Content Posting API (Upload to Inbox d'abord) ; tokens orchestrés côté backend ; polling Display API pour les stats cross-plateforme.

---

## 10. Modèle économique (à valider avec Finance/Direction)

| Source de revenu | Description | Phase |
|---|---|---|
| **Contenu sponsorisé** (notifications) | Le promoteur paie (XOF) pour diffuser une annonce ciblée | MVP |
| **Mise en avant** (cartes sponsorisées) | Slot prioritaire en première rangée Explore | MVP |
| **Abonnement Promoteur Pro** | Statistiques avancées, réponses illimitées aux avis | V1.1 |
| **Frais de mise en relation** (à challenger) | Commission sur clic-to-call qualifié | V2 |

---

## 11. Analytics & instrumentation (minimum produit)

Événements à tracker dès le MVP :
`view_card` · `like` · `favorite_add` · `share` · `search_query` · `filter_applied` · `subcategory_selected` · `ai_assistant_query` · `ai_assistant_result_click` · `notification_received` · `notification_opened` · `review_submitted` · `report_submitted` · `intro_video_shown` · `intro_video_skipped` · `softwall_hit` · `softwall_signup_started` · `currency_change_attempt` · `signup_started` · `signup_completed` · `login_completed` · `auth_method` *(email/google/apple)* · `social_post_created` *(type photo/diaporama)* · `entity_tag_selected` · `mention_preview_opened` · `follow` · `missing_place_reported` · `guide_service_created` · `listing_created` · `listing_updated` · `claim_submitted` · `promoter_activated` · `promoter_verified` · `promoter_campaign_created` · `promoter_campaign_paid` · `directions_click` · `contact_click`.

Chaque événement porte : `ville`, `type_entite`, `entite_id`, `source_session` (organique vs sponsorisé), `langue`, `devise_affichage` — pour l'attribution promoteur et le suivi i18n/devises.

---

## 12. Risques & mitigations

| Risque | Impact | Mitigation |
|---|---|---|
| **Densité de contenu au lancement (amplifiée)** | Adoption faible | La création étant **réservée** (plus de suggestion ouverte), le seed est **critique** : **~1000 promoteurs pré-inscrits** + **patrimonial créé par l'équipe** en amont ; rencontres avec les **associations de promoteurs** ; **flux d'activation** dédié (§6.9.5). |
| **Mur d'inscription qui casse la conversion** | Perte d'utilisateurs (surtout touristes) | **Mur souple** : contenu visible sans compte, prix FCFA ; le mur ne se lève qu'à l'action engageante (dont changement de devise) — le contenu convainc avant de demander l'inscription. |
| **UGC inapproprié rattaché à un commerce** | Nuisance au promoteur, risque légal | **Rattachement obligatoire** + contrôle image automatique + signalement + **licence/retrait** (CGU) ; UGC hors des fiches (feed/profil only). |
| **Vidéo d'intro trop lourde / lente** | Premier lancement dégradé | Asset **embarqué** (indépendant du réseau), **≤ ~2–3 Mo**, H.264 muet, **sautable**, **premier lancement uniquement**, fallback `reduced-motion` ; analytics de skip. |
| Confusion organique / sponsorisé | Perte de confiance | Badge texte « Sponsorisé » jaune systématique (jamais la couleur seule), plafond de slots ; **mise en avant éditoriale** jamais labellisée « Sponsorisé ». |
| Hallucination de l'IA | Perte de confiance, risque réputationnel | Réponses ancrées au catalogue, citation systématique de la source, **état « aucun résultat »**, pas de promesse de réservation. |
| Friction paiement Mobile Money | Revenu faible | Intégrations éprouvées (CinetPay/FedaPay), opérateurs béninois (MTN MoMo, Moov Money), statut clair + retry. |
| Prix multi-devises trompeurs/déséquilibrés | Perte de confiance, layout cassé | XOF référence, `≈`, ICU/CLDR, compact réservé aux surfaces denses (jamais transactionnel), composant à largeur réservée (§6.14). |
| Texte traduit qui déborde (DE/PT) | UI cassée | Composants tolérants à l'expansion, tests pseudo-localisés, pas de largeur figée. |
| Connectivité dégradée | Abandon | Cache offline, skeletons, design tolérant à la latence. |
| Esthétique premium au détriment de la perf | App belle mais lente → abandon | Premium par le design (overlays CSS, images adaptatives), budget P75 < 1,5 s contrôlé par écran. |
| Modération insuffisante | Risque légal/réputationnel | Vérification manuelle des comptes Pro au lancement, seuil de signalement automatique, file de revue humaine. |
| Dépendance audit/quotas TikTok (V1.2) | Fonctionnalité bloquée | Audit lancé tôt, « Upload to Inbox » d'abord, jamais de compteur fusionné. |

---

## 13. Roadmap & jalons proposés

| Phase | Contenu | Durée indicative |
|---|---|---|
| **Discovery / Design finalisé** | Validation PRD + DESIGN, maquettes Figma haute-fidélité, prototype cliquable, ADR | 3–4 semaines |
| **MVP (V1.0)** | Périmètre §5.1 — Bénin, **langue FR**, 4 devises d'affichage | 10–12 semaines dev |
| **V1.1** | **Langue EN**, **vidéo dans le feed Social** (photo/diaporama déjà au MVP), commentaires, ciblage notifications avancé, stats promoteur avancées | 6–8 semaines dev |
| **V1.2+** | **Langues PT/DE/ES/IT** (progressif), publication croisée TikTok | 4–6 semaines dev (+ délai d'audit TikTok, à lancer en amont) |
| **V2** | Réservation/billetterie intégrée, fidélité (sous réserve de Discovery dédiée) | À planifier |

---

## 14. Décisions actées & questions résiduelles

**Décisions actées (levée d'ambiguïté) :**
- **Like = cœur** (social, `likes_count`) · **Favori = marque-page** (personnel, onglet Profil). Deux actions distinctes, jamais confondues.
- **FAB unique IA-first** ; « Surprenez-moi » en appui long.
- **4 devises** (XOF/NGN/USD/EUR) ; **€/$/₦ réservés aux comptes**, invité en **FCFA**.
- **Langue détectée automatiquement** (repli déterministe ; aucun sélecteur imposé ; bascule non bloquante ; le choix explicite prime et persiste).
- **Mur souple** : contenu et fiches consultables sans compte (prix FCFA) ; le mur se lève à la première action engageante (Like, Favori, avis, publier, suivre, **changement de devise**), avec option **« Ne pas s'inscrire »**.
- **Authentification** : **Email** (OTP → mot de passe → nom/prénom → ville → devise) **ou Google** (révision → ville → devise) ; **Apple Sign-In sur iOS uniquement** ; **ni téléphone ni âge**. **Flux d'activation** dédié pour les promoteurs pré-inscrits.
- **Écran d'intro vidéo** au premier lancement (embarqué + remplaçable à distance, sautable, H.264 muet vertical ~2–3 Mo).
- **Création de fiche réservée** aux rôles habilités — **plus de « suggestion » ouverte** ; l'utilisateur contribue par le **social** et **« Signaler un lieu manquant »**.
- **Trois classes de fiche implicites** (Patrimonial / Commercial / Événementiel) ; **claim uniquement Commercial/Événementiel**. Lieux commerciaux (parcs d'attraction, galeries…) publiés par promoteurs, affichés sous Lieux.
- **Rôles** : Utilisateur · **Guide** (catégorie de Promoteur, espace « Trouver un guide », événements, **sans enrichissement**) · **Institution** (patrimonial + stewardship) · Promoteur commercial · **Admin Kwabor** (seed, profil public à badge vérifié, cession ultérieure de stewardship).
- **Bouton « + » contextuel par rôle.**
- **Réseau social dès le MVP** : feed façon TikTok **photo + diaporama** (**vidéo en V1.1**) ; **UGC rattaché obligatoirement** à une entité, **jamais sur la fiche** (feed + profil) ; **mention → aperçu de fiche ≤ 25 %**.
- **Mise en avant éditoriale** gratuite (patrimonial/événements), **distincte** du sponsoring payant, jamais labellisée « Sponsorisé ».
- **Licence de contenu UGC + procédure de retrait** dans les CGU.
- **Billetterie événement : tranches multiples dès le MVP** ; prix billet toujours en **mode plein**.
- **Classification hôtelière** : étoiles **déclaratives** (promoteur), contrôlées par la modération.
- **Langue du contenu** : `content_lang` stockée + **traduction automatique à l'affichage**.
- **Re-modération à l'édition** déclenchée par : nom, type/sous-type, GPS, prix, billetterie. **Une fiche est publiée une fois puis mise à jour à volonté.**
- **Limites médias** : photo ≤ 8 Mo (JPG/PNG/AVIF) ; vidéo ≤ 60 s, ≤ 50 Mo (V1.1) ; intro ~2–3 Mo.
- **Cible V1 mobile-only** : Android + iOS ; aucune autre cible applicative dans la roadmap active.
- **Architecture UI mobile** : Android Compose Multiplatform ; iOS SwiftUI ; `shared` KMP limité au métier/data/contrats/états partagés.
- **Rôles d'équipe vérifiée** : Propriétaire > Gestionnaire > Éditeur > Modérateur, droits cumulatifs et budgets contrôlés.

**Questions résiduelles (Finance / partenaires) :**
1. Vérification Promoteur/Guide/Institution : manuelle (Kwabor) ou semi-automatisée dès le MVP ?
2. Budget d'acquisition pour atteindre 25 000 MAU à 6 mois ?
3. Stratégie exacte de signature Apple Developer pour TestFlight/App Store : certificats, provisioning profiles, rotation et ownership des secrets ?
4. Politique exacte de fréquence/plafond des notifications sponsorisées ?
5. Source et fréquence du taux de change (API tierce vs taux fixe paramétré) ?
6. Liste définitive des opérateurs/agrégateurs Mobile Money à intégrer.
7. Référentiels contrôlés à figer (communes BJ, sous-catégories, services/amenities, **langues/spécialités de guide**) — propriétaire de la maintenance ?
8. Proxy mesurable retenu pour le KPI « belle app » (taux de partage ? complétion d'onboarding ?).
9. Seuil de passage du mur souple : l'ouverture d'une fiche reste-t-elle libre, ou compte-t-elle comme action engageante ? *(décidé : libre)*
10. Modération de l'UGC : automatique seule au MVP, ou file humaine dédiée dès le lancement ?

---

## 15. Glossaire

| Terme | Définition |
|---|---|
| **Fiche / Listing** | Entité du catalogue : Lieu, Établissement ou Événement. |
| **Classe de fiche** | Nature implicite d'une fiche : **Patrimonial**, **Commercial** ou **Événementiel** ; gouverne les droits et la revendicabilité. |
| **DetailSheet** | Composant unique de fiche détail, en modal remontante 92 %, paramétré par type. **Catalogue only** (aucun UGC). |
| **Like** | Signal social public (cœur) incrémentant `likes_count`. |
| **Favori** | Sauvegarde personnelle (marque-page) dans Profil → Favoris. |
| **UGC** | Contenu communautaire (photo/diaporama ; vidéo en V1.1) publié par un utilisateur, **rattaché à une entité**, vivant dans le feed Social et le profil de l'auteur. |
| **Mention** | Lien d'une publication sociale vers son entité ; au tap, aperçu de fiche ≤ 25 %. |
| **Mur souple** | Accès invité en lecture (prix FCFA) ; l'inscription se déclenche à la première action engageante. |
| **Rôle** | Utilisateur · Guide · Institution · Promoteur commercial · Admin Kwabor (cumulables, chacun vérifié). |
| **Guide** | Catégorie de Promoteur exposant un **service de guide** (classe Commercial) dans « Trouver un guide ». |
| **Institution** | Contributeur certifié du **Patrimonial** ; peut être **steward** d'une fiche patrimoniale. |
| **Steward** | Responsable éditorial d'une fiche patrimoniale (sans possession commerciale). |
| **Admin Kwabor** | Opérateur de la plateforme (seed, modération) ; profil public à badge vérifié. |
| **Promoteur** | Compte B2B vérifié gérant une ou plusieurs fiches Commercial/Événementiel. |
| **Revendication / Claim** | Prise de possession d'une fiche **Commercial/Événementiel** non revendiquée (jamais Patrimonial). |
| **Activation** | Flux d'onboarding dédié aux comptes Promoteur **pré-inscrits** (lien d'invitation). |
| **Signaler un lieu manquant** | Canal utilisateur (formulaire, Profil) vers la file Admin Kwabor ; pas une création de fiche. |
| **Campagne** | Achat de visibilité (carte sponsorisée ou notification), payé en XOF. |
| **Sponsorisé** | Contenu **payé**, toujours signalé par un badge texte jaune. |
| **Mise en avant éditoriale** | Curation **gratuite** (patrimonial/événements) par Kwabor ; **jamais** labellisée « Sponsorisé ». |
| **PriceTag** | Composant d'affichage des prix (multidevise, largeur réservée, chiffres tabulaires). |
| **XOF / FCFA** | Devise de référence (saisie, stockage, paiement) ; €/$/₦ réservés aux comptes. |

---

*Document lié : `DESIGN.md` — design system (tokens monochrome, en-tête allégée, navbar plate, cartes overlay, fiches immersives, composant prix), spécification écran par écran (y compris notifications, espace Promoteur, paiement, avis, claim) et patterns d'interaction.*
