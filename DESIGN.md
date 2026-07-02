# DESIGN.md — Kwabor
### Design System & Spécification UI/UX — *Le guide visuel et intelligent du Bénin*
**Version :** 1.0 · **Date :** 1 juillet 2026
**Cible technique :** Kotlin Multiplatform + Compose Multiplatform (Android / iOS / Web-PWA)
**Document lié :** `PRD.md` — vision produit, périmètre, exigences fonctionnelles, KPIs.

> **Source de vérité du design.** Système **monochrome noir premium** · **photo plein cadre + overlay** · **couleur uniquement quand elle porte un sens métier** (jaune *Sponsorisé*, rouge *billetterie/danger*) · **navbar basse plate à 5 items égaux** (pas de bouton central) · **en-tête allégée** · **FAB IA unique** · titre `Découvrez le Bénin` · **composant prix multidevise**. **Intro vidéo** au 1er lancement → **« Se connecter ou s'inscrire »** ; **mur souple** (invité en FCFA) ; auth **Email / Google / Apple-iOS**. **Rôles** (Utilisateur · Guide · Institution · Promoteur · Admin Kwabor) × **classes implicites** (Patrimonial / Commercial / Événementiel) ; **création de fiche réservée** ; **fiche = catalogue only**. **Social façon TikTok dès le MVP** (photo/diaporama ; vidéo V1.1) avec **UGC rattaché** et **mention → aperçu ≤ 25 %**. Mono-pays Bénin, i18n-ready (6 langues), 4 devises d'affichage.

---

## Sommaire

1. Direction artistique
2. Identité de marque
3. Design tokens
4. Composant Prix (PriceTag)
5. Règle d'interaction — Like vs Favori
6. Bibliothèque de composants communs
7. Patterns structurants (en-tête, navbar, FAB)
8. Cartes d'exploration
9. Fiche Détail immersive (DetailSheet)
10. Catalogue des états (chargement, vide, erreur, offline)
11. Spécifications écran par écran
12. Wizards d'enregistrement de contenu
13. Navigation & architecture de l'information
14. Responsive (mobile / tablette / desktop)
15. Motion & micro-interactions
16. Performance
17. Internationalisation & accessibilité

---

## 1. Direction artistique

Kwabor n'a pas une « charte décorative » : l'identité visuelle est un **levier produit**. Le pari : offrir **la beauté d'un feed social et la fiabilité d'un guide structuré**, là où Google Maps est fiable mais froid et Instagram beau mais inactionnable.

**Quatre lois fondatrices régissent chaque écran :**

1. **La photo est le héros, l'UI s'efface.** Le contenu occupe le plein cadre. L'interface vit en **overlays translucides** posés *sur* l'image, jamais dans une boîte à côté d'elle.
2. **Monochrome par défaut, couleur par exception.** Tout le système est **noir / blanc / nuances de gris**. La couleur est **interdite sauf deux cas métier** : le **jaune Sponsorisé** (transparence publicitaire) et le **rouge billetterie/danger** (transaction, urgence). Sur fond monochrome, ces deux couleurs deviennent *plus* repérables.
3. **Le noir est l'accent.** Les éléments actifs (CTA principal, FAB IA, item de navbar actif, bouton sélectionné) sont **noir plein** — signature « ink premium ». *(En thème sombre, ce noir s'inverse en surface claire — §3.3.)*
4. **Premium par le design, jamais par le poids.** La cible utilise majoritairement des Android low/mid-range sur réseau dégradé. Tout effet premium est rendu **en CSS/Compose**, jamais par des images lourdes empilées. Budget non négociable : mur d'exploration **P75 < 1,5 s** (§16).

**Mood de référence.** Monochrome éditorial photo-first : grille de cartes plein cadre, en-tête légère, pastilles translucides, fiche détail immersive à hero plein écran. La couleur n'apparaît que sur le badge jaune *Sponsorisé* et le bouton rouge *Acheter un billet*.

---

## 2. Identité de marque

| Élément | Spécification |
|---|---|
| **Nom produit & app** | **Kwabor** (un seul mot, capitale K). Titre d'accueil `Découvrez le Bénin` (traduit), jamais « Guide Touristique ». |
| **Symbole (mark)** | Le **« K » au chemin** : un K dont une rivière/route sinueuse traverse le contre-forme (*le chemin de la découverte du Bénin*). Décliné **blanc sur ink** (icône d'app, contextes sombres) et **ink sur blanc** (contextes clairs). |
| **Logotype (wordmark)** | Symbole + `KWABOR` en capitales espacées (tracking large), même famille display que l'UI. Versions horizontale et icône seule. |
| **App icon** | Carré ink `#0E0E10` plein, mark blanc centré, sans dégradé. Variante adaptative Android (foreground = mark, background = ink). |
| **Watermark communautaire** | Mark Kwabor 24 px + `@username` 14/600, blanc 60 % d'opacité, **non maskable**, posé en bas-droite sur tout export du réseau social — **photos/diaporamas (MVP) et vidéos (V1.1)**. |
| **Intro vidéo / first run** | Vidéo verticale muette embarquée + remplaçable à distance, **bouton « Passer »**, fallback statique `reduced-motion` → écran « Se connecter ou s'inscrire » (E1/E3). |

> Le mark fonctionnant en positif comme en négatif sert l'inversion dark mode (§3.3) : pas de re-dessin, juste un swap de couleur de remplissage.

---

## 3. Design tokens — source unique de vérité

> Tokens centralisés (un seul fichier de thème Compose). **Aucun hex en dur dans un écran** : tout passe par un token (cohérence + inversion dark mode automatique).

### 3.1 Échelle neutre (Ink ramp) — le cœur monochrome

Rampe neutre légèrement chaude (pour que les photos « respirent »). Elle porte 95 % de l'UI.

| Token | Hex (clair) | Usage |
|---|---|---|
| `ink/950` (**Accent**) | `#0E0E10` | CTA principal, FAB IA, item nav actif, app icon, surfaces actives |
| `ink/900` | `#1A1A1C` | Texte principal |
| `ink/700` | `#3C3C41` | Texte fort secondaire, icônes pleines |
| `ink/500` | `#6E6E76` | Texte secondaire, placeholders, sous-titres |
| `ink/300` | `#B6B6BD` | Icônes inactives, état désactivé |
| `ink/200` | `#DCDCE1` | Bordures discrètes |
| `ink/150` | `#E8E8EC` | Séparateurs (divider) |
| `ink/100` | `#F1F1F3` | Fond champs de recherche, chips inactives, skeleton |
| `paper/50` (**Fond app**) | `#FAFAF8` | Background global (off-white chaud) |
| `surface/0` | `#FFFFFF` | Cartes, sheets, surfaces blanches |

**Overlay photo (recette premium-sans-poids).** Dégradé CSS/Compose, **jamais** une image PNG sombre empilée :
- `overlay/card` : `linear-gradient(180deg, transparent 0%, transparent 45%, rgba(0,0,0,0.78) 100%)` — calibré **35–45 %** de hauteur selon la densité de texte. Le texte vit dans le **tiers bas**.
- `overlay/hero` : version plus profonde (`…rgba(0,0,0,0.86) 100%`) pour la fiche détail, garantissant le contraste AA du titre.

### 3.2 Couleur sémantique métier — les **deux seuls** écarts tolérés

| Token | Hex | Texte posé | Usage strict |
|---|---|---|---|
| `accent/sponsored` (**Jaune**) | `#F4B400` | `ink/950` | **Badge texte « Sponsorisé »** uniquement (carte, notification). Étoile de note active (variante `#F5B301`). |
| `accent/ticket` (**Rouge**) | `#C5283D` | `#FFFFFF` | **CTA « Acheter un billet »**, actions danger (supprimer le compte), lien *Signaler*, ruban d'alerte. |

**Règles non négociables :**
- La couleur seule ne suffit **jamais** : un contenu sponsorisé porte **toujours le mot « Sponsorisé »** (traduit). Le jaune sans label est interdit.
- **Aucune autre couleur** n'entre dans le système. Pas de bleu CTA, pas de vert marque, pas de palette de catégories. Les catégories se distinguent par la **photo et la typographie**.
- Contraste **AA ≥ 4.5:1** vérifié par token.

### 3.3 Thème sombre — **inversion de l'accent**

L'accent étant le noir, le mode sombre impose une **inversion** : les éléments actifs noirs deviennent **surface claire** sur fond sombre, sinon ils disparaissent.

| Token | Clair | Sombre | Note |
|---|---|---|---|
| Fond app | `#FAFAF8` | `#0B0B0C` | |
| Surface (carte/sheet) | `#FFFFFF` | `#161619` | |
| Surface élevée | `#FFFFFF` | `#1E1E22` | |
| Texte principal | `#1A1A1C` | `#F2F2F4` | |
| Texte secondaire | `#6E6E76` | `#9A9AA2` | |
| Divider | `#E8E8EC` | `#2A2A2F` | |
| **Accent actif** (CTA / FAB / nav) | `#0E0E10` | `#F2F2F4` + texte ink | **Inversion** : le « bouton noir » devient « bouton clair ». |
| `accent/sponsored` | `#F4B400` | `#FFC53D` | texte ink dans les deux cas |
| `accent/ticket` | `#C5283D` | `#FF6B74` | texte : blanc (clair) / ink (sombre) |
| `overlay/card` | `…0.78` | `…0.82` | légèrement renforcé en sombre |

> Toute valeur « relevée » en sombre conserve un contraste ≥ 4.5:1. Le toggle Dark Mode (Paramètres) applique le thème **instantanément, sans rechargement**.

### 3.4 Typographie

Pairing premium éditorial **+ chiffres tabulaires** (essentiels au composant prix §4) :

| Rôle | Famille | Détail |
|---|---|---|
| **Display / titres** | **General Sans** (ou Clash Grotesk) 500/600/700 | Premium géométrique, Latin Extended complet (FR/EN/PT/DE/ES/IT). |
| **Texte / UI / chiffres** | **Inter** 400/500/600 | Lisibilité écran + **chiffres tabulaires** (`tnum`) pour aligner les prix. |
| **Fallback système** | `-apple-system, Roboto, "Segoe UI", sans-serif` | Garantit le rendu avant chargement du webfont (perf). |

**Échelle (mobile) :**

| Style | Taille / Graisse / Interligne | Famille |
|---|---|---|
| Hero overlay (titre sur image) | 30 / 700 / 1.1 | General Sans |
| H1 écran (`Découvrez le Bénin`) | 28 / 700 / 1.15 | General Sans |
| H2 titre fiche détail | 24 / 700 / 1.2 | General Sans |
| H3 titre section | 18 / 600 / 1.3 | General Sans |
| Body L | 16 / 400 / 1.5 | Inter |
| Body S | 14 / 400 / 1.5 | Inter |
| Label / caption | 13 / 500 / 1.3 | Inter |
| Overline (ville · catégorie · date) | 12 / 600 / 1.2, tracking +6 %, MAJUSCULES | Inter |
| **Prix** | 14–24 selon surface, **`tnum`** | Inter |

> **Minimum lisibilité Paramètres : 16 px.** Prévoir l'**expansion de texte** (DE +30–40 %) : aucune largeur de label figée qui tronque en DE/PT (§17).

### 3.5 Forme & élévation

| Token | Valeur | Note |
|---|---|---|
| Radius carte | **16 px** | Rendu premium |
| Radius sheet (top) | **28 px** | Coins hauts des modales / bottom sheets |
| Radius bouton / champ | 14 px | |
| Radius chip / pill | **fully rounded** (999) | Pastilles, prix, ville, chips |
| FAB | cercle **56 px** (Ø) | |
| Ombre carte | `0 2px 12px rgba(16,16,18,0.06)` | Très légère — la profondeur vient de la photo |
| Ombre sheet | `0 -8px 32px rgba(16,16,18,0.12)` | Sheet remontante |
| Ombre FAB | `0 6px 20px rgba(16,16,18,0.22)` | Seule ombre marquée du système |
| Pastilles flottantes (sur photo) | **frosted** : fond `rgba(255,255,255,0.18)` + blur **léger 8 px**, bord `rgba(255,255,255,0.30)` | Verre dépoli léger, pas de blur massif |

### 3.6 Grille & espacement (base 4 pt)

| Token | Valeur |
|---|---|
| Unité de base | 4 px (échelle 4/8/12/16/20/24/32) |
| Marge latérale écran (mobile) | **16 px** |
| Gutter grille cartes | 12 px |
| Colonnes mobile | 2 |
| Ratio carte standard | **3:4** |
| Hauteur Bottom Nav | 76 px (+ safe-area) |
| Cible tactile minimale | **44 × 44 px** |

### 3.7 Iconographie

- **Material Symbols (Rounded, weight 400)** comme set unique, mono-couleur (`ink/700` inactif, `ink/950` actif). **Aucune icône en emoji** : iconographie 100 % vectorielle.
- Icônes clés : IA `auto_awesome` · **Like `favorite`** (cœur, plein quand actif) · **Favori `bookmark`** (marque-page, plein quand actif) · Localisation `location_on` · Filtre `tune` · Itinéraire `directions` · Recherche `search` · Notifications `notifications` · Profil `person`.
- Trait régulier, taille 24 px (28 px en navbar). Aucune icône colorée hors sémantique métier.

---

## 4. Composant Prix (PriceTag) — pièce maîtresse

Kwabor affiche des prix dans **4 devises** (XOF référence, NGN, USD, EUR) × **6 langues**. Le composant `PriceTag` encapsule **toute** la logique d'affichage ; **aucun écran ne formate un prix à la main.**

**Règles encodées :**
1. **XOF est la référence.** Tous les prix sont **stockés en XOF**. Les autres devises sont des **conversions d'affichage**, toujours préfixées de **`≈`** (le paiement réel se fait en XOF).
2. **Formatage par la locale (ICU/CLDR).** FR `150 000 FCFA` · EN `150,000 FCFA` · DE `150.000 FCFA`. Symboles : **FCFA** (jamais « XOF »), **₦**, **$**, **€**.
3. **Décimales par devise.** XOF & Naira : **0**. USD & Euro : **0** en navigation/liste, **2** en contexte transactionnel/facture.
4. **Deux modes selon la surface.** **Compact (k/M)** sur surfaces denses (chip de carte, épingle, sparkline), **réservé aux montants ≥ 10 000** ; **Plein** sur fiche, billet, paiement, dashboard. **Règle d'or : un prix transactionnel (billet, facture, coût de campagne) n'est JAMAIS en compact.**
5. **Largeur réservée + alignement constant** (chiffres `tnum`), pour que le layout ne « saute » pas entre `≈ $40` et `150 000 FCFA`.

| Surface | Mode | Exemple FR (XOF / EUR) |
|---|---|---|
| Chip prix de carte | Compact | `25 k FCFA` / `≈ 38 €` |
| Fiche établissement (« à partir de ») | Plein | `À partir de 15 000 FCFA` |
| Billet événement | Plein (jamais compact) | `5 000 FCFA` / `≈ 7,62 €` |
| Coût de campagne promoteur | Plein, XOF only | `30 000 FCFA` |
| Facture | Plein, XOF, 2 décimales autorisées | `30 000,00 FCFA` |
| Gratuit | Badge | `Gratuit` (jamais `0 FCFA`) |

**Convertisseur de devise** : taper un PriceTag en mode plein ouvre une sheet listant les 4 devises (`≈`) + le rappel « paiement en XOF » (§11, écran C4).

---

## 5. Règle d'interaction — Like vs Favori *(non ambigu)*

Deux actions **distinctes**, jamais confondues :

| Action | Icône | Nature | Effet | Où |
|---|---|---|---|---|
| **Like** | `favorite` (cœur, rouge plein si actif) | **Social public** | **Incrémente `likes_count`** de la fiche ; visible dans les stats (profil, promoteur) | Pastille **haut-droite** de la carte ; barre d'actions de la fiche ; double-tap vidéo (V1.1) |
| **Favori** | `bookmark` (marque-page, ink plein si actif) | **Personnel privé** | **Sauvegarde** dans Profil → Favoris ; n'affecte pas `likes_count` | Pastille **haut-gauche** secondaire de la carte (sous la note si présente) ; barre d'actions de la fiche ; pastille du hero |

> **Conséquences design :** sur le **hero** de la fiche, la pastille flottante haut-droite est le **Favori** (sauvegarde rapide en consultation). Le **Like** vit dans la **barre d'actions** (Like / Partager / Favori). Sur la **carte**, le **cœur** (Like) est en haut-droite ; le **marque-page** (Favori) apparaît si la densité le permet. **Les favoris sont accessibles uniquement** via Profil → Favoris (jamais en navbar). Animation Like : cœur scale 1 → 1.3 → 1 (200 ms). Like et Favori sont mis en **file locale offline** et synchronisés à la reconnexion.
>
> **Mur souple :** pour un **invité**, Like et Favori (comme avis, suivre, publier, ou changer de devise) **déclenchent l'invite d'inscription** (écran E4). L'invité voit les prix **en FCFA uniquement** ; l'action reprend son cours une fois le compte créé.

---

## 6. Bibliothèque de composants communs

| Composant | Spécification | Réutilisé sur |
|---|---|---|
| **En-tête Explore (allégée)** | §7.1 — localisation + titre + onglets + recherche+filtre + chips. **Aucune cloche, aucun avatar.** | Écrans Explore |
| **Bottom Nav (plate, 5 items)** | §7.2 — Accueil / **Social** (feed) / **Ajouter** (contextuel par rôle) / Notifications / Profil. **Pas de bouton central.** | Écrans racine |
| **Onglets principaux** (Lieux / Événements / Hôtels & Restaurants) | Pills : actif = `ink/950` + texte blanc ; inactif = `ink/100` + texte `ink/700`. Scroll horizontal si débordement. | Explore ; variante soulignée sur Profil |
| **Chips de sous-catégories** | Pill `ink/100` / `ink/700` ; sélectionnée = bord `ink/950` 1.5 px + texte `ink/950`. 1 tap = tri rapide. | Sous la recherche |
| **Champ de recherche** | Hauteur 48, radius 14, fond `ink/100`, icône `search` 20 à gauche, **bouton filtre `tune`** carré 48 à droite. | Explore |
| **Carte d'exploration** | §8 — image 3:4 plein cadre, overlay, titre + ville + note + prix posés sur l'image. | Explore (3 variantes) |
| **Badge Sponsorisé** | Pill ambre, texte ink `Sponsorisé` 12/600, coin haut-gauche. Jamais la couleur seule. | Cartes Hôtels & Restau., Notifications, aperçu campagne |
| **Pastille note** | Frosted, `star` ambre 14 + valeur 13/600 blanc. | Cartes, hero |
| **Pastille Like** | Frosted Ø 36, `favorite` ; actif = cœur `accent/ticket` plein. | Cartes (haut-droite), hero (barre d'actions) |
| **Pastille Favori** | Frosted Ø 36, `bookmark` ; actif = ink plein. | Cartes, hero (haut-droite) |
| **PriceTag** | §4 — largeur réservée, `tnum`, compact/plein, préfixe `≈`. | Partout où un montant s'affiche |
| **FAB IA** | §7.3 — cercle 56 `ink/950`, `auto_awesome` blanc, 16 px du bord, 88 px au-dessus de la navbar (inversé clair en dark). Appui long → speed-dial *Assistant IA* / *Surprenez-moi*. | Écrans Explore |
| **Barre d'action collée (sticky)** | Bas de fiche : info clé à gauche + **CTA principal** à droite (noir, ou rouge pour billet). | Fiches Détail |
| **Action Row** | `favorite` **Like** / `share` Partager / `bookmark` **Favori** — icônes 24, label 12. | Fiches Détail |
| **Rangée de services à icônes** | Chips horizontales scrollables : icône + label (WiFi, Restaurant, Piscine, Parking…). | Détail Établissement |
| **Carte Google Maps (embed)** | 100 % × 200 px, épingle `ink/950`, CTA rond 48 `directions`, état erreur = cadre `ink/100` + message. | Fiches Détail |
| **Review Item** | Avatar 32, nom 14/600, étoiles ambre, texte, like d'avis, lien *Signaler* (rouge), **+ encart « Réponse du gérant »** si réponse promoteur. Pagination par lots de 3. | Fiches Détail, gestion avis |
| **Composeur d'avis** | §11 (C1) — sélecteur d'étoiles, texte, photos, Publier. | Fiche → Donner mon avis |
| **Notification Item** | §11 (D1) — icône de famille, titre, extrait, vignette, horodatage, point non-lu, badge « Sponsorisé » si applicable. | Centre de notifications |
| **Bottom Sheet générique** | Coins 28, drag handle 36×4 `ink/200`, drag-to-dismiss, anim 300 ms `cubic-bezier(0.32,0.72,0,1)`. | Détail, filtres, partage, avis, IA, convertisseur, contact |
| **Sélecteur de ville** | §11 (C5) — recherche + liste des communes BJ + « Utiliser ma position ». | En-tête, onboarding, wizard |
| **Filter Drawer** | §11 (C6) — bottom sheet (mobile) → panneau latéral (desktop). Ville / prix (double slider XOF) / date / type + **Appliquer/Réinitialiser** + compteur. | Événements, Hôtels & Restau. |
| **Sheet de contact** | §11 (C7) — choix de canal : Appel / WhatsApp / Site / Email. | Détail Établissement |
| **Lightbox galerie** | §11 (C8) — plein écran, swipe, pinch-zoom, compteur, fermeture. | Fiches Détail |
| **Champ de formulaire** | 48 px, radius 14, fond `ink/100`, label flottant, erreur inline rouge sous le champ. | Auth, wizards, paramètres |
| **Uploader média** | Cover + réordonnancement drag, preview, progress, alt text éditable. | Wizards, avis, profil |
| **Éditeur d'horaires 7 j** | 7 lignes jour, créneaux multiples, « 24h/24 », « Fermé ». | Wizard Établissement/Lieu |
| **Map picker** | Autocomplétion biaisée Bénin, dépôt/déplacement d'épingle, « Utiliser ma position », validation polygone Bénin. | Wizard (localisation) |
| **Chip de statut** | Pill colorée sémantique : Brouillon `ink/500`, En attente `ink/700`, Publié `ink/950`, **Rejeté** rouge, Vérifié ambre/vert. | Contenus publiés, vérification Promoteur |
| **Skeleton** | Shimmer rounded-rect `ink/100` (clair) / `#1E1E22` (sombre). | Tout chargement réseau |
| **Toast** | Top-center, auto-dismiss 3 s, fond `ink/950` texte blanc. | Toute action async |
| **Empty state** | Pictogramme **mono-trait** + message + CTA optionnel. | Listes vides |
| **Banner Offline** | Pleine largeur, **persistante**, fond `ink/900` texte blanc « Vous êtes hors ligne ». | Global (au-dessus navbar) |
| **Dialog** | Surface élevée, titre + corps + 2 actions ; **action destructive en rouge**. | Confirmations (suppression, archivage, déconnexion) |
| **Segmented control** | 2–3 segments, actif = `ink/950` ; ex. **Personnel / Promoteur**, tri avis. | Profil, listes |
| **Intro Video Layer** | Vidéo verticale aspectFill, muet+playsinline, **bouton « Passer »** frosted, fallback statique `reduced-motion`, asset embarqué + remote. | Écran E1 |
| **Auth Landing** | Fond image plein écran + « Découvrez le Bénin » + **pastille de langue** haut-droite + CTA S'inscrire/Se connecter + **« Ne pas s'inscrire »**. | Écran E3 |
| **Pastille de langue** | Frosted (§3.5) `🌐 FR ▾` ; lisible sur photo, discrète ; ouvre la liste des langues livrées. | E3, E5, E6 |
| **Bouton fournisseur d'auth** | Google (toujours) · **Apple (iOS uniquement)** ; style outline, logo + label. | E5, E6 |
| **Soft-Wall Sheet** | Bottom sheet contextuelle (bénéfice + S'inscrire/Se connecter/Plus tard) ; **reprend l'action** après auth. | Déclenchée en invité (E4) |
| **Stepper d'inscription** | Barre de progression multi-étapes courte ; retour par étape ; OTP 6 cases ; écran révision Nom/Prénom (Google/Apple). | E6 |
| **Carte sociale (feed)** | Contenu plein écran (photo/diaporama/vidéo) + overlay actions + **puce de mention d'entité**. | Feed Social (J1) |
| **Aperçu de fiche (mention)** | Overlay **≤ 25 % de hauteur** au-dessus du contenu (mini-hero + titre + note + prix) ; tap contenu = ferme, tap aperçu = fiche complète. | Feed Social (J1) |
| **Sélecteur d'entité (rattachement)** | Recherche catalogue → sélection unique **obligatoire** ; pré-rempli si ouvert depuis une fiche. **Publier désactivé sans entité.** | Composeur social (H2) |
| **Bandeau contextuel UGC** | Message non intrusif « vos médias doivent avoir un lien avec l'app… ». | À l'ouverture du composeur social |
| **Watermark overlay** | Mark Kwabor + `@username`, **non maskable**, appliqué à l'export (photo/vidéo). | Composeur social, feed |
| **Menu « + » contextuel** | Tuiles variables selon le rôle (§7.2, §11 H1). | Navbar Ajouter |
| **Formulaire lieu manquant** | Nom, type présumé, ville, localisation opt., note, photo opt. → file Admin. | H4, Profil |
| **Carte de guide** | Photo + langues + zones + note + tarif indicatif (`PriceTag`). | « Trouver un guide » (I15) |
| **Chip de rôle** | `verified` + libellé : Promoteur / Guide / Institution / **Admin Kwabor**. | Profil, avis, feed |
| **Écran d'activation** | « Activez votre compte {Nom} » : mot de passe / Google-Apple → dashboard + fiche pré-remplie. | E8 |

---

## 7. Patterns structurants

### 7.1 En-tête Explore — allégée
De haut en bas, et **rien d'autre** :
1. **Localisation** — `location_on` + ville courante + chevron, modifiable (ouvre le sélecteur de ville, §11 C5).
2. **Titre** `Découvrez le Bénin` (H1 28/700, traduit).
3. **Onglets principaux** — Lieux / Événements / Hôtels & Restaurants (pills).
4. **Recherche + filtre** appariés (champ 48 + bouton `tune`).
5. **Chips de sous-catégories** propres à l'onglet actif.

> **Ni cloche ni avatar dans l'en-tête.** Notifications et Profil vivent **exclusivement** dans la navbar (§7.2).

### 7.2 Barre de navigation basse — plate, 5 items égaux
`Accueil` · `Social` · `Ajouter` · `Notifications` · `Profil`. Icônes 28, label 12.
- **Aucun bouton central surdimensionné.** Pattern de découverte/consommation où la recherche en haut reste le point d'entrée dominant. « Ajouter » est un item égal.
- **Social** = feed communautaire façon TikTok (photo/diaporama dès le MVP, vidéo en V1.1) — §11 Groupe J.
- **Ajouter (« + ») est contextuel selon le rôle** (§11 H1) : un menu adapté s'ouvre (Utilisateur → contenu social ; Guide → social + événement + service ; Promoteur → social + événement + fiche ; Institution → social + fiche patrimoniale + enrichissement).
- Actif = icône pleine + label `ink/950` (→ surface claire en dark) ; inactif = `ink/300`. **Notifications porte un badge pastille** (point `accent/ticket`) quand non-lu, **effacé à l'ouverture** de l'écran.
- Chaque destination est présente **une seule fois**. Pas d'entrée « Recherche » (doublonnerait la barre du haut) ; pas d'entrée « Favoris » (onglet du Profil).
- **Mur souple** : un **invité** qui touche Social/Ajouter/Notifications/Profil ou une action engageante voit l'**invite d'inscription** (E4).

### 7.3 FAB IA — point d'entrée unique IA & sérendipité
- Cercle 56 `ink/950`, `auto_awesome` blanc, sur les écrans Explore, 16 px du bord droit, 88 px au-dessus de la navbar (inversé clair en dark).
- **Tap** → Assistant IA (sheet, §11 B1). **Appui long** → speed-dial à 2 entrées (*Assistant IA* / *Surprenez-moi*), pour ne jamais empiler deux FAB.

---

## 8. Cartes d'exploration — le mur immersif

Composant signature. Grille **2 colonnes**, gutter 12, ratio **3:4**, image **cover plein cadre** + overlay (§3.1). Toutes les infos sont **posées sur l'image**, dans le **tiers bas**.

| Élément | Position | Style |
|---|---|---|
| Image | plein cadre | WebP/AVIF, lazy-load, placeholder dégradé immédiat |
| Pastille note | haut-gauche | frosted, `star` ambre + valeur blanche |
| **Pastille Like (cœur)** | haut-droite | frosted, cœur rouge si actif (§5) |
| **Pastille Favori (marque-page)** | sous la note (si densité) | frosted, ink si actif (§5) |
| **Badge Sponsorisé** | haut-gauche (remplace la note) | ambre, texte `Sponsorisé` — *seul élément coloré de la grille* |
| Titre | tiers bas | blanc 16/700, 2 lignes max |
| Ligne ville | sous le titre | `location_on` 14 + ville, blanc 80 % |
| Chip prix | bas-droite | **Compact** dans la devise utilisateur (§4) |

**Spécificités par onglet :**

| Onglet | Spécificité |
|---|---|
| **Lieux** | Tri défaut = popularité (vues + likes pondérés). Pull-to-refresh. Chips : Plages / Historique / Marchés / Nature… |
| **Événements** | **Pastille date** sur l'image (`20 juin ›`) ; **ruban diagonal « Terminé »** (`ink/500`, blanc 12/700) si date passée, ARIA « Événement terminé » ; tri défaut = proximité temporelle. |
| **Hôtels & Restaurants** | **Première rangée Sponsorisée** ; chip gamme de prix ; **max 2 cartes sponsorisées par grille de 6 visibles**. |

**Non négociable :** badge texte « Sponsorisé » toujours présent (jamais la couleur seule) ; lisibilité AA (overlay 35–45 %, texte tiers bas) ; états skeleton / toast+retry / empty / banner offline (§10).

---

## 9. Fiche Détail immersive — `DetailSheet`

**Un seul composant** paramétrable par type (Lieu / Établissement / Événement), ouvert en **modal sheet remontante 92 %** depuis n'importe quel point d'entrée (carte, notification, résultat IA, Surprenez-moi, recherche). L'immersion plein écran *est* le rendu de cette sheet.

**Structure commune (haut → bas) :**
1. **Hero plein cadre** (image/vidéo) + `overlay/hero`.
   - Pastilles flottantes frosted : **Retour** (haut-gauche), **Favori** `bookmark` (haut-droite, §5).
   - **Titre sur le hero** (tiers bas, 24/700 blanc) + **overline de contexte** (ville · catégorie · date).
   - **Preuve sociale** : mini-pile d'avatars + compteur de visiteurs/intéressés + note.
2. **Sheet blanche remontante** (radius 28, offset −24) :
   - Infos spécifiques au type ; description tronquée (~150 car) + « Lire la suite ».
   - **Rangée de miniatures** (slider, pinch/double-tap zoom, bouton plein écran → Lightbox §11 C8).
   - **Action Row** : **Like** `favorite` / Partager `share` / **Favori** `bookmark` / **Ajouter une photo** `add_a_photo`. Ce dernier ouvre le **composeur social avec l'entité pré-remplie** (§11 H2) — 1 tap, message contextuel affiché.
   - **Carte Google Maps** + CTA `directions` Itinéraire.
   - **Section avis** : **bouton « Donner mon avis »** en tête, liste paginée (3/lot), tri Plus récents / Mieux notés, encart « Réponse du gérant » si présent.
   - **Bandeau « Revendiquer »** **uniquement** si la fiche est de classe **Commercial/Événementiel** et non revendiquée (§11 I12). **Jamais** sur une fiche Patrimonial.
3. **Barre d'action collée en bas** : info clé à gauche, **CTA principal** à droite.

> **Fiche = catalogue uniquement.** Le `DetailSheet` n'affiche **que** les médias officiels (Promoteur/Institution/Admin). **Aucun contenu communautaire** (UGC) n'apparaît sur la fiche : les photos/diaporamas des utilisateurs vivent dans le **feed Social** et le **profil de l'auteur** (§11 J), reliés à la fiche par une **mention**. Le bouton « Ajouter une photo » **crée un post social rattaché**, il n'ajoute pas de média à la fiche.

**Spécificités par type — chaque champ saisi au wizard (§12) a son élément d'affichage ici (zéro champ orphelin) :**

| Type | Hero | Contenu spécifique de la sheet | CTA bas |
|---|---|---|---|
| **Lieu** | overline ville | stats vues/likes/note · **horaires d'accès** (ou « Accès libre 24h/24 ») · **équipements sur site** (parking, guide, toilettes, PMR…) · **note tarifaire** · chip prix (ou badge **Gratuit**) | **Itinéraire** — bouton **noir** |
| **Établissement** | overline catégorie | badge (« Hôtel 4★ », « Restaurant »…) · **pill d'ouverture dynamique** (Ouvert/Fermé, tap → **horaires 7 j**) · **rangée de services à icônes** · **chip prix mode plein** (tap → convertisseur) · **selon sous-type** : bloc **Types de chambres / tarifs** (hébergement) · **chips cuisine + bouton « Voir le menu » + Réservation** (restauration) · **caractéristiques + pill « Âge minimum »** (vie nocturne/club) · **liens réseaux sociaux** | **Contacter** — bouton **noir** (ouvre Sheet de contact §11 C7) ; « à partir de … » à gauche |
| **Événement** | **pastille date** ; ruban « Terminé » si passé | date/heure + lieu (rattaché ou adresse) · **bloc billetterie multi-tranches** (Standard, VIP… **prix mode plein, jamais compact**) · **infos organisateur** (nom + contact) · **capacité** | **« Acheter un billet » — bouton rouge** `accent/ticket` (deep-link externe si `ticket_url` ; badge **Gratuit** si prix = 0) |

**Règle événement passé :** ruban diagonal « Terminé » (ARIA « Événement terminé »), **bouton billet désactivé/grisé**, CTA contact masqué. État distinct de la même fiche.

> Le **rouge** du bouton billet et le **jaune** du badge sponsorisé sont les **seuls** écarts de couleur de la fiche. Tout le reste est monochrome.

**Accessibilité — focus order :** Retour → Favori → titre → preuve sociale → actions (Like/Partager/Favori) → description → services → carte → Donner mon avis → avis → CTA bas.

---

## 10. Catalogue des états (transverse)

Chaque écran réseau implémente quatre états + ses **empty states spécifiques**. Aucun écran ne s'affiche « vide » sans message.

| État | Rendu | S'applique à |
|---|---|---|
| **Chargement** | Skeleton shimmer (§6) | Tout chargement réseau |
| **Erreur réseau** | Toast + bouton **Réessayer** | Toute requête échouée |
| **Offline** | Bannière persistante `ink/900` | Global |
| **Vide — Explore (filtres)** | « Aucun résultat — élargissez vos filtres » + Réinitialiser | Explore, Filter Drawer |
| **Vide — Recherche** | « Aucun résultat » + suggestions + « Essayer l'assistant IA » | Recherche |
| **Vide — IA** | « Je n'ai rien trouvé de correspondant dans le catalogue » (jamais d'invention) | Assistant IA |
| **Vide — Favoris** | « Aucun favori — touchez le marque-page pour sauvegarder » | Profil → Favoris |
| **Vide — Contenus publiés** | « Aucun contenu — Ajoutez un lieu, un établissement ou un événement » + CTA | Profil → Contenus |
| **Vide — Notifications** | « Pas encore de notifications » | Centre de notifications |
| **Vide — Avis** | « Soyez le premier à donner un avis » + CTA | Fiche Détail |
| **Vide — Campagnes** | « Aucune campagne — créez votre première promotion » + CTA | Promoteur → Facturation |
| **Vide — Stats** | « Données indisponibles pour cette période » | Stats promoteur/profil |
| **Détail hors-ligne** | Lecture du cache si disponible, sinon « Indisponible hors ligne » | Fiche Détail (non garanti MVP) |
| **Invité (mur souple)** | Prix en **FCFA** ; actions engageantes → **Soft-Wall Sheet** (E4) | Explore, Détail, Social en invité |
| **Vide — Publications** | « Aucune publication — partagez une photo depuis un lieu ou un événement » + CTA (→ H2) | Profil → Publications (F2) |
| **Vide — Feed Social** | « Rien à afficher pour l'instant — suivez des créateurs ou publiez » | Feed Social (J1) |
| **Bloquant — composeur social** | **Publier désactivé** tant qu'aucune entité n'est rattachée + rappel contextuel | Composeur social (H2) |
| **Vide — Trouver un guide** | « Aucun guide pour ces critères — élargissez la recherche » | I15 |
| **Confirmation — lieu manquant** | Toast « Merci, notre équipe va vérifier » | H4 |
| **Réservé au rôle** | Écran/tuile masqué ou désactivé + « Réservé aux Promoteurs/Guides/Institutions » | « + » et écrans de création |

---

## 11. Spécifications écran par écran

> Frame de référence mobile **390 × 844**, safe-area top 24. Toute couleur renvoie à un token (§3). Tout texte passe par l'i18n (§17). Chaque écran réseau implémente les états du §10.

### Groupe A — Découverte

**A1. Explore — Lieux** *(écran par défaut)*
En-tête allégée (§7.1), onglet Lieux actif. Grille §8. FAB IA (§7.3). Tap carte → `DetailSheet` Lieu. Pull-to-refresh. Chips : Plages / Historique / Marchés / Nature…

**A2. Explore — Événements**
Onglet Événements actif. Cartes avec pastille date + ruban « Terminé ». Filter Drawer (C6) : ville (multi), date (Aujourd'hui / Ce week-end / Cette semaine / Ce mois / Mois prochain), type (Concert, Festival, Conférence, Randonnée…), popularité.

**A3. Explore — Hôtels & Restaurants**
Onglet actif. Première rangée Sponsorisée (badge ambre, ≤ 2/écran). Chip gamme de prix. Filter Drawer : ville, type (Hôtel, Auberge, Résidence, Restaurant, Bar, Café, Club, **Maquis**…), gamme de prix (double slider XOF), popularité.

**A4. Recherche — état actif**
Au focus du champ : champ en haut, **recherches récentes** (effaçables), **suggestions/autocomplétion** (nom · ville · catégorie), bascule de portée **Onglet actif / Tout**. Clavier ouvert, bouton effacer.

**A5. Recherche — résultats**
Grille de cartes (§8) filtrée par la requête, chips et filtres conservés. Empty state « Aucun résultat » + suggestions + « Essayer l'assistant IA » (§10).

**A6–A8. Détail — Lieu / Établissement / Événement**
`DetailSheet` paramétré (§9). Hero slider 55–60 % ; alt text par image ; focus order documenté (§9).

### Groupe B — IA & sérendipité

**B1. Assistant IA** *(tap FAB)*
Bottom sheet 90 %. Bulle d'intro + 2–3 prompts rapides en chips (« Restaurant chic et calme à Cotonou », « Que faire ce week-end ? »). Conversation messagerie : bulles user (droite, `ink/100`) / assistant (gauche, surface). **Réponse structurée** : texte court + **3–5 mini-cartes horizontales** (carte §8 réduite) → tap = `DetailSheet`. Indicateur de frappe. **Micro-label « Source : fiche {nom} »** sur chaque reco (anti-hallucination). Champ bas + micro (vocal V1.1). États : **« Aucun résultat dans le catalogue »**, erreur réseau (retry). Réponses dans la langue de l'utilisateur.

**B2. Surprenez-moi** *(appui long FAB)*
Plein écran immersif (pas une sheet) : carte hero ~70 %, image plein cadre, titre superposé, **CTA noir « Voir la fiche »** + bouton rond « Relancer » (`casino`). Crossfade + léger scale (250 ms) entre tirages. Tirage pondéré popularité + proximité, exclusion des favoris/vus récents.

### Groupe C — Sheets & interactions

**C1. Donner mon avis**
Bottom sheet : titre « Votre avis sur {nom} », **sélecteur d'étoiles 1–5** (ambre `#F5B301`), champ texte multiligne (compteur), **uploader photos** optionnel, CTA **« Publier »** noir (désactivé tant que la note est vide). Mode **édition** si déjà noté. Connexion requise → invite login si invité. Toast « Avis publié, merci ». Erreur (retry).

**C2. Share Sheet**
Bottom sheet 60 %, handle, padding 24. Grille 3 colonnes de réseaux (**WhatsApp en premier**). Actions : **Copier le lien** / **Télécharger** / **Signaler** (rouge). Toasts d'état.

**C3. Report Modal**
Sheet centrée : « Signaler ce contenu », motifs en radio (**Spam / Inapproprié / Information erronée / Faux compte-avis / Autre**), champ texte si Autre, **CTA « Envoyer » rouge**, lien Annuler. Toast « Signalement envoyé, merci » ; contenu non masqué côté signalant.

**C4. Convertisseur de devise** *(tap sur un PriceTag plein)*
Bottom sheet : montant de référence en **XOF (FCFA)** en tête, puis les **3 conversions** `≈ ₦ / ≈ $ / ≈ €` (chiffres `tnum` alignés). Mention « Taux indicatif — paiement en FCFA ». Lien « Changer ma devise par défaut » → Paramètres (G5).

**C5. Sélecteur de ville** *(en-tête, onboarding, wizard)*
Bottom sheet : champ de recherche en haut, **liste des communes du Bénin** (`cities`), section « Récentes », bouton **« Utiliser ma position »** (géoloc → ville la plus proche). Sélection = fermeture + maj du contexte.

**C6. Filter Drawer**
Bottom sheet (mobile) → **panneau latéral persistant** (desktop). Sections selon l'onglet : **Ville** (multi), **Prix** (double slider XOF), **Date** (chips), **Type**. Pied collant : **Réinitialiser** (gauche) + **Appliquer (N résultats)** noir (droite). Compteur live.

**C7. Sheet de contact** *(établissement, CTA « Contacter »)*
Bottom sheet : boutons pleins **Appel** / **WhatsApp** / **Site web** / **Email** (selon les canaux renseignés). Annonce ARIA « ouvre une application externe ». Masque les canaux absents.

**C8. Lightbox galerie**
Plein écran noir : image centrée, **swipe** horizontal, **pinch/double-tap zoom**, compteur « 3 / 8 », bouton fermer (haut-droite). Fond `#000`.

### Groupe D — Notifications

**D1. Centre de notifications** *(navbar item 4)*
AppBar « Notifications » + action **« Tout marquer comme lu »**. Liste verticale **groupée par temporalité** : *Aujourd'hui · Cette semaine · Plus tôt*.
**Notification Item** : icône de famille (glyphe), **titre** 14/600, **extrait** 13 `ink/500`, **vignette** du contenu lié (48), **horodatage** relatif, **point non-lu** `ink/950` à gauche. Selon la famille :
- **Suggestion personnalisée** — vignette + libellé « Pour vous ».
- **Contenu sponsorisé** — **badge texte « Sponsorisé » jaune obligatoire** (seul élément coloré).
- **Nouveauté** — « Nouveau près de {ville} ».
- **Alerte événementielle** — pastille date.

Interactions : **tap → `DetailSheet`** (deep link selon le type) ; **swipe → masquer** ; **badge navbar effacé** à l'ouverture ; lien « Gérer mes notifications » → Paramètres (G4). États : empty (§10), skeleton, erreur, offline.

### Groupe E — Intro, compte & mur souple

**E1. Écran d'intro vidéo** *(premier lancement uniquement)*
Plein écran, **vidéo d'arrière-plan verticale** (univers touristique/culturel/festif du Bénin) en **aspectFill**, safe-area 0. Auto-play **muet + playsinline**, durée 15–25 s. **Bouton « Passer »** discret (pastille frosted) visible **immédiatement** en haut-droite. Léger overlay bas + mark Kwabor. Fin de vidéo **ou** « Passer » → **transition automatique vers E3**. La **langue est détectée** pendant l'intro (§E2). **Fallback image statique** si `prefers-reduced-motion`. Asset **embarqué + remplaçable à distance** (spec : H.264, muet, ~2–3 Mo — §PRD 6.9.3). **Analytics : taux de skip.** *(Lancements suivants : pas de vidéo → E3 si déconnecté, Accueil si connecté.)*

**E2. Langue — détection automatique** *(pas d'écran dans le cas courant)*
**Principe : respecter la langue système, sans intervention.** Aucun sélecteur imposé — un écran de choix bloquant serait une friction inutile (au MVP, mono-langue FR).
- **Détection :** lire la **liste ordonnée** des langues préférées de l'OS, retenir la **première langue livrée** ; comparer sur le **sous-tag en ignorant la région** (`pt-BR`/`pt-PT` → PT) ; sinon **EN si livré, sinon FR**.
- **Écran de choix — conditionnel uniquement :** affiché seulement si la locale n'est pas couverte **et** qu'au moins deux langues sont livrées (moot au MVP ; « s'active » avec l'EN).
- **Bascule non bloquante :** **pastille de langue** en haut-droite de l'écran E3 + Paramètres → G5. Le **choix explicite prime et persiste**.

**E3. « Se connecter ou s'inscrire »** *(landing après l'intro)*
**Image plein écran** (fond ; au MVP asset embarqué, fond dynamique catalogue reporté). Titre **« Découvrez le Bénin »** (H1, langue détectée), sous-titre court. **Sélecteur de langue moderne et discret en haut à droite** — **pastille frosted** (§3.5) `🌐 FR ▾`, lisible sur photo, s'intègre avec harmonie sans se fondre. En bas : **CTA « S'inscrire »** (noir, plein) → E6 ; **« Se connecter »** (secondaire, contour) → E5 ; et une option **texte claire « Ne pas s'inscrire »** → entre en **mur souple** (Accueil en invité). Au tap sur « Ne pas s'inscrire », **petit message** (toast/sheet) : « En continuant sans compte, les prix s'affichent en **FCFA**. Inscrivez-vous pour voir les tarifs en **€, \\$ ou ₦** et interagir. »

**E4. Invite d'inscription (mur souple)** *(déclenchée en session invité)*
Bottom sheet déclenché quand un invité tente une **action engageante** (Like, Favori, avis, publier, suivre, **changer de devise**). Contenu : titre contextuel selon l'action (« Créez un compte pour enregistrer ce lieu » / « …pour voir les prix en € »), bénéfice, **CTA « S'inscrire »** (→ E6) + **« Se connecter »** (→ E5) + « Plus tard ». **L'action visée reprend automatiquement** après création/connexion. *(Le mur souple laisse parcourir le mur et ouvrir les fiches en lecture seule, prix FCFA — l'ouverture d'une fiche n'est pas une action engageante.)*

**E5. Connexion**
Layout centré, **mark Kwabor**, **pastille de langue** en coin haut. Champ **email** + bouton **Continuer** → **écran mot de passe** (avec « Mot de passe oublié » → E7). Boutons **« Se connecter avec Google »** et **« Se connecter avec Apple » (iOS uniquement)**. Lien vers Inscription. Validation inline, CTA désactivé tant qu'invalide, spinner inline (pas de layout shift). Erreurs : identifiants invalides, compte non vérifié.

**E6. Inscription** *(formulaire multi-étapes rapide, progress linéaire)*
Deux chemins ; **ni téléphone ni âge** ; **CGU + confidentialité + licence UGC** acceptées avant validation ; **langue** héritée de la détection (§E2).
- **Par email :** `Email` + **Continuer** → **Code OTP** (6 chiffres, envoyé automatiquement, saisi à l'écran suivant, renvoi 30 s) → **Création du mot de passe** (règles + confirmation) → **Nom / Prénom** (champs vides) → **Ville / Localisation** (sélecteur ville C5 **ou** « Autoriser la détection GPS » — c'est ici qu'est demandée la permission localisation) → **Devise** (4 options, **XOF par défaut**, aperçu `PriceTag`) → **Validation**.
- **Par Google / Apple (iOS) :** **Auth** → **Écran de révision** (Nom / Prénom **pré-remplis** depuis le fournisseur, **modifiables en 1 tap**, bouton unique **Continuer**) → **Ville / Localisation** → **Devise** → **Validation**.
- Retour possible à chaque étape sauf confirmation. Après validation → **priming notifications** (E différé, cf. règle ci-dessous) puis Accueil.

**E7. Mot de passe oublié**
Cohérent avec E6 : identifiant (email) → **OTP 6 chiffres** (renvoi 30 s) → **nouveau mot de passe** (règles + confirmation) → toast succès → retour E5.

**E8. Activation d'un compte Promoteur pré-inscrit**
Ouvert via **lien d'invitation** (email/WhatsApp). Écran **« Activez votre compte {Nom du commerce} »** : rappel de l'établissement pré-rempli, choix **définir un mot de passe** *(email déjà connu)* **ou** **lier Google/Apple** → vérification → **accès direct au tableau de bord Promoteur** (I4) avec la **fiche déjà pré-remplie**. Pas d'inscription à froid.

> **Priming notifications** — placé **après la validation du compte** (ou au premier moment pertinent, ex. première mise en favori) : sheet bénéfice + **Activer** / Plus tard, avant le prompt OS. Le priming **localisation** vit dans l'étape Ville de E6. Refus non bloquant (dégradation gracieuse).

### Groupe F — Profil

**F1. Profil**
Cover 180 (parallax **léger**), avatar 96 en overlap (−48), nom 20/700, bio 14 (3 lignes). **Badge de rôle** (chip + `verified`) selon le compte : **Promoteur**, **Guide**, **Institution**, **Admin Kwabor** (badge vérifié). **Bouton Modifier le profil** (`ink/100`, → F5), **sélecteur Personnel/Promoteur** (segmented, si habilité et vérifié → I3), et lien **« Signaler un lieu manquant »** (`report_gmailerrorred`, → H4) bien visible. Onglets soulignés : **Publications** (F2) / **Contenus publiés** (F2bis, *rôles créateurs uniquement*) / **Favoris** (F3) / **Statistiques** (F4).
> **Visibilité des onglets par rôle :** un **Utilisateur** simple voit **Publications · Favoris · Statistiques** (pas de « Contenus publiés », puisqu'il ne crée pas de fiche). Les rôles créateurs (Guide, Promoteur, Institution, Admin) voient **tous** les onglets.

**F2. Onglet — Publications** *(contenu social de l'auteur)*
Grille de **vignettes de contenus sociaux** (photo/diaporama ; vidéo en V1.1) publiés par l'utilisateur, chacune marquée d'une **puce d'entité rattachée** (nom du lieu/événement). Tap → ouvre le contenu dans le **viewer Social** (J1). Indicateur diaporama (icône pile) / vidéo (durée). C'est le **point d'accès public** au contenu communautaire de l'utilisateur. Empty : « Aucune publication — partagez une photo depuis un lieu ou un événement » + CTA (→ H2).

**F2bis. Onglet — Contenus publiés** *(rôles créateurs)*
Liste/grille des **fiches** gérées par l'utilisateur, **chip de statut par carte** : Brouillon `ink/500` · En attente `ink/700` · Publié · **Rejeté** rouge. Tap **Rejeté** → motif (Spam / Doublon / Hors Bénin / Photos non conformes / Infos insuffisantes / Autre) + **« Corriger et resoumettre »** (rouvre le brouillon). Tap **Brouillon** → reprise du wizard (H3). Actions par fiche : éditer (mise à jour à volonté) · archiver (dialog) · voir les stats. **N'apparaît pas** pour un utilisateur simple. Empty (§10).

**F3. Onglet — Favoris**
Grille de cartes (§8) des fiches favorisées, **filtre par type** (Lieux / Événements / Hôtels & Restau.), ruban « Terminé » conservé. **Unique point d'accès aux favoris.** Synchronisation de la file offline. Empty (§10).

**F4. Onglet — Statistiques (utilisateur)**
Compteurs des contributions de l'utilisateur (vues/likes reçus sur ses **publications sociales** ; pour les créateurs, vues/likes de ses **fiches**). *(Distinct du dashboard Promoteur I13.)* En V1.2, ajoute les compteurs **Kwabor** et **TikTok** côte à côte, **jamais fusionnés** (badge de source par compteur).

**F5. Édition du profil**
Écran/sheet : changer **avatar** (96) et **cover** (180) avec recadrage, **nom** (limites), **bio** (3 lignes, compteur). CTA Enregistrer. Validation inline.

**F6. Profil d'un tiers**
Cover + avatar + bio publics, **badge de rôle**, compteurs **abonnés/abonnements**, **bouton Suivre** (état actif/inactif), onglet **Publications** publiques (+ « Trouver un guide » mis en avant si le tiers est un guide). Alimente le réseau social (J1).

### Groupe G — Paramètres

**G1. Paramètres (écran principal)**
AppBar « Paramètres » + retour. Rows 56 px (texte min 16), sections : **Compte** (G2), **Sécurité** (G3), **Préférences** (G4), **Internationalisation** (G5), **À propos** (G6), **Comptes liés** *(V1.2, G7)*, **Danger Zone** (G8).

**G2. Compte**
Identité, email/téléphone (avec « Modifier »), **chip de statut de vérification** (vert/ambre/rouge).

**G3. Sécurité**
- **Changer le mot de passe** : ancien + nouveau + confirmation, règles, validation.
- **2FA** : toggle d'activation, canal OTP, **codes de secours** (générer/copier).
- **Appareils connectés** : liste des sessions (appareil, date, lieu), action **« Déconnecter cet appareil »**.

**G4. Préférences**
- **Notifications** : **toggles par famille** (Suggestions / Sponsorisé / Nouveautés / Alertes événementielles) + **plafond de fréquence** (curseur ou choix). Opt-out granulaire.
- **Dark Mode** : toggle (application instantanée, sans reload).

**G5. Internationalisation**
- **Langue** : liste des 6 (nom natif), application instantanée.
- **Devise d'affichage** : 4 options + aperçu `PriceTag`.
- **Format de date** : options localisées (ICU/CLDR).

**G6. À propos**
**Version** de l'app, **Licences** (open source), **Politique de confidentialité**, Conditions d'utilisation.

**G7. Comptes liés** *(V1.2)*
TikTok : état (chip `@username` si connecté), **Connecter** (→ K1) / **Déconnecter** (rouge clair + dialog).

**G8. Danger Zone**
**Supprimer le compte** (rouge, **dialog irréversible** + ré-authentification), **Se déconnecter** (dialog de confirmation).

### Groupe H — Ajout de contenu

**H1. « + Ajouter » — menu contextuel par rôle**
Bottom sheet dont les tuiles **dépendent du rôle** (§11 rôles) :
- **Utilisateur simple** → **Publier un contenu social** (photo/diaporama) **uniquement** ; lien secondaire **« Signaler un lieu manquant »** (→ H4). *Aucune tuile de création de fiche.*
- **Guide** → Contenu social · **Événement** · **Mon service de guide**.
- **Promoteur commercial** → Contenu social · **Événement** · **Fiche** (Commercial).
- **Institution** → Contenu social · **Fiche patrimoniale** · **Enrichir une fiche**.

Chaque tuile ouvre le flux dédié : contenu social → **H2** ; fiche/événement/service → **H3** (`ListingWizard` paramétré). Prix **en XOF**. *(La classe de fiche n'est jamais demandée : elle est implicite — §PRD 6.11.1.)*

**H2. Composeur de contenu social** *(photo/diaporama au MVP ; vidéo en V1.1)*
- **Rattachement d'entité obligatoire et techniquement bloquant.** En tête, un **sélecteur d'entité** (« Identifier un lieu, un établissement ou un événement ») : recherche dans le catalogue → sélection unique. **Publier reste désactivé tant qu'aucune entité n'est choisie.** Si le composeur est ouvert **depuis une fiche** (Action Row → « Ajouter une photo »), l'entité est **pré-remplie** (modifiable).
- **Message contextuel** affiché **à l'ouverture** (sheet info, non intrusive) : « Vos médias doivent avoir un lien avec l'app — une photo prise dans un restaurant, lors d'un concert, sur un site… identifiés dans Kwabor. »
- **Médias** : capture/import **photo** ou **diaporama** (ré-ordonnancement, recadrage) ; légende ; **watermark non maskable** visible en preview. Toggle **« Publier aussi sur TikTok »** grisé « Bientôt » (V1.2 → K1).
- Publication → apparaît dans le **feed Social (J1)** et le **profil → Publications (F2)** ; **jamais sur la fiche**. Reprise du fil garantie. États : upload/progress, erreur (retry), modération auto en tâche de fond.

**H3. ListingWizard** *(Fiche : Lieu / Établissement / Événement / Service de guide — rôles créateurs)*
Multi-étapes + progress adaptée au sous-type (§12) : **Type & base** → **Localisation** (map picker biaisé Bénin, **GPS dans le polygone Bénin**) → **Détails** (branche dynamique ; pour un **Service de guide** : langues, zones, spécialités, tarifs) → **Médias** (≥ 1 photo cover, ≥ 3 recommandées, drag-reorder, alt text éditable) → **Récap & publication** (preview **réelle** de la fiche §9 + de la carte §8) → statut « En attente de modération ». **Brouillon auto-sauvegardé** ; validation bloquante par étape ; erreurs inline rouge. **Publiée une fois, mise à jour à volonté** (§PRD 6.11.6). Réservé aux rôles habilités (un utilisateur simple n'atteint jamais cet écran).

**H4. Signaler un lieu manquant** *(ouvert à tous les comptes)*
Accessible depuis le Profil (F1) et le menu « + » (utilisateur). Formulaire court : **nom du lieu**, **type présumé** (Lieu / Établissement / Événement), **ville/quartier**, **localisation** (map picker optionnel), **note libre**, photo optionnelle. CTA **Envoyer** → **file de traitement Admin Kwabor** (`missing_place_reports`). Toast « Merci, notre équipe va vérifier ». **Ce n'est pas une création de fiche** : aucun contenu n'est publié ; l'utilisateur ne devient pas créateur.

### Groupe I — Espace Promoteur & contributeurs vérifiés *(B2B)*

> Face B2B (monétisation §10 du PRD). Rôles et droits définis en §PRD 6.11. Design system monochrome, AA, tolérant à l'expansion de texte.

**I1. Devenir contributeur vérifié** *(sélection de rôle)*
Écran d'explication des avantages + **choix du profil** : **Promoteur commercial** / **Guide touristique** / **Institution / Office**. Chaque choix ouvre le **formulaire adapté** (I2). *(Les comptes pré-inscrits par l'équipe passent par l'activation E8, pas par cet écran.)*

**I2. Vérification — formulaire adapté & statut**
Formulaire **spécifique au rôle** + **upload de justificatif**, puis écran de **statut** (En attente `ink/700` / **Vérifié** vert / **Refusé** rouge + motif + **recours**). L'approbation active le **badge de rôle** (F1) et le sélecteur (I3).
- **Promoteur commercial** : raison sociale, type d'activité, contact, ville + justificatif (registre de commerce, facture, **photo de devanture**).
- **Guide touristique** *(catégorie de Promoteur)* : identité pro, **agrément/carte de guide**, **langues parlées** (multi), **zones couvertes** (villes/régions), **spécialités** (chips : histoire, nature, gastronomie…), **tarifs indicatifs**, années d'expérience. À l'issue, création guidée de la **fiche « Service de guide »** (classe Commercial) exposée dans **« Trouver un guide »** (I15).
- **Institution / Office** : accréditation + périmètre patrimonial ; ouvre les droits d'**enrichissement** et de **stewardship** (I16).

**I3. Sélecteur Personnel / Promoteur**
Segmented control sur le Profil (visible si **habilité et vérifié** : Promoteur, Guide, Institution, Admin). « Promoteur » ouvre le tableau de bord I4.

**I4. Tableau de bord Promoteur**
AppBar + **sélecteur de fiche gérée**. Bloc **stats clés** (vues · likes · **clics itinéraire** · **clics contact**, période). Accès rapide en liste : **Mes fiches** (I5) · **Avis** (I6) · **Promouvoir** (I7) · **Statistiques** (I13) · **Facturation** (I14). Respect monochrome.

**I5. Mes fiches**
Liste des fiches possédées, chip de statut, actions : **Éditer** (H3 en mode édition → re-modération si champ sensible ; **mise à jour illimitée**) · **Archiver** · **Voir les stats** · **Promouvoir**.

**I6. Gestion des avis (promoteur)**
Liste des avis des fiches gérées, **avis signalés en tête**, tri. Tap → **Composeur de réponse** (texte + Publier) → la réponse s'affiche sous l'avis (« Réponse du gérant », §6 Review Item) et **notifie l'auteur**.

**I7. Promouvoir — création de campagne**
Wizard : **Objet** (Carte sponsorisée Explore / Notification sponsorisée) → **Ciblage** (I8) → **Période & budget** → **Aperçu** (rendu réel : carte §8 avec **badge Sponsorisé**, ou Notification Item §D1) → **Coût en XOF (mode plein)** → CTA **« Payer »** (→ I9).
> **Mise en avant éditoriale** *(non payante — Admin/Institution, écran back-office ou I17)* : pousse une fiche **Patrimoniale** ou un **événement** dans des emplacements de curation. **Jamais** de badge « Sponsorisé » (ce n'est pas de la publicité). Rendu visuel distinct du sponsoring (pas de jaune).

**I8. Ciblage**
Sélection **ville(s)** (C5 multi) + **centres d'intérêt** (chips). Estimation d'audience. **Confidentialité par conception** : aucune donnée nominative exposée (le promoteur choisit des critères, la plateforme exécute).

**I9. Paiement Mobile Money**
Récap montant **XOF (mode plein)** → **choix opérateur** (**MTN MoMo** / **Moov Money**) → saisie numéro / déclenchement (USSD ou redirection agrégateur **CinetPay / FedaPay**). **Validation côté serveur uniquement.**

**I10. Statut de paiement**
Écran d'attente avec états : **En cours** (spinner) / **Réussi** (✓, → reçu I11) / **Échec** (motif + **Réessayer**). Pas de validation côté client.

**I11. Reçu / Facture**
Récap transaction : campagne, montant **XOF (2 décimales autorisées en facture)**, opérateur, date, référence. **Téléchargeable**. Lien vers Facturation (I14).

**I12. Revendication de fiche (claim)** *(Commercial/Événementiel uniquement)*
Déclenchée par le bandeau « Revendiquer » du `DetailSheet` — **présent seulement sur les fiches Commercial/Événementiel non revendiquées** (typiquement pré-créées par le seed Admin). Étapes : **Preuve** (justificatif + coordonnées) → **Soumission** → **Suivi** (`claims.status` : En attente / **Approuvé** → `owner_id` = promoteur, badge Vérifié, édition complète + réponses aux avis + promotion / **Refusé** + motif + recours). **Conflit** (2 revendications) : priorité au justificatif vérifié, arbitrage modération. **Les fiches Patrimoniales ne sont jamais revendicables** (pas de bandeau ; stewardship attribuée par Admin — I17).

**I13. Statistiques Promoteur**
Tableau de bord : compteurs **par fiche et consolidés** (vues · likes · clics itinéraire · clics contact). **Séparation stricte Organique / Sponsorisé** via **badge de source par compteur — jamais sommés**. Sparklines (compact autorisé). Sélecteur de période. **Heat-map de provenance des vues** *(V1.1)*. Empty (§10).

**I14. Facturation / campagnes**
Liste des campagnes (statut · période · **coût XOF** · performance), détail de campagne, **historique des transactions**, **factures téléchargeables**. Empty (§10).

**I15. « Trouver un guide »** *(espace de découverte + gestion du service)*
- **Côté public** (accessible depuis Accueil/recherche) : écran de découverte des **services de guide** — filtres **ville · langue · spécialité**, cartes de guide (photo, **langues**, **zones**, **note**, **tarif indicatif** via `PriceTag`) → **fiche Service de guide** (`DetailSheet` de sous-type guide : bio, langues, spécialités, zones, tarifs, avis, **contact direct**).
- **Côté guide** : gestion de sa fiche service (H3), publication d'**événements**, suivi des vues/contacts (I13). C'est là qu'il « propose ses services ».

**I16. Espace Institution — enrichissement & stewardship**
Pour un compte **Institution** vérifié : liste des **fiches patrimoniales** de son périmètre (ou dont elle est **steward**) ; **enrichir** (photos officielles, horaires, description, accès — modération allégée/fast-track) ; **réponses officielles** ; pas de possession commerciale, pas de sponsoring payant (mise en avant éditoriale possible). Badge « Certifié ».

**I17. Console Admin Kwabor** *(back-office, hors app grand public — vue de référence)*
Réservée au rôle **Admin** : **seed de masse** (création patrimoniale, import), **pré-inscription des promoteurs** (génère les liens d'activation E8), **file de modération** (fiches, avis, UGC, **`missing_place_reports`** — H4), **attribution/cession de stewardship** aux institutions, **correction de la classe** d'une fiche, **mise en avant éditoriale**. L'Admin dispose par ailleurs d'un **profil public à badge vérifié** côté app. *(UI détaillée hors périmètre de ce document produit ; spécifiée séparément.)*

### Groupe J — Réseau social communautaire *(MVP)*

**J1. Feed Social** *(façon TikTok — photo & diaporama au MVP ; vidéo en V1.1)*
Portrait verrouillé, contenu plein écran (aspectFill), safe-area top 0, swipe vertical = navigation, auto-scroll.
- **Types de contenu** : **photo unique** ; **diaporama** (barre de progression segmentée en haut, tap bord droit/gauche ou glissement horizontal pour paginer) ; **vidéo** en V1.1 (auto-play mute, boucle). Modèle d'interaction identique quel que soit le type.
- **Interactions** : **double-tap = Like** (Heart Burst 200 ms) ; overlay droit **Suivre / Like / Commentaires (J2, V1.1) / Partager** ; long-press = pause + **télécharger/signaler**. **Watermark Kwabor non maskable** (§2) sur photos **et** vidéos.
- **Mention d'entité** (rattachement obligatoire de tout contenu) : puce **`place`/`event` + nom** posée en bas-gauche, au-dessus de la légende — à l'image des « événements à venir » affichés sur les contenus d'un créateur.
- **Aperçu de fiche par la mention** : au **tap sur la mention**, un **aperçu de la fiche s'ouvre au-dessus du contenu**, **hauteur ≤ 25 %** (mini-hero + titre + note + chip prix), **laissant le contenu visible** dessous. **Taper le contenu** → l'aperçu **se referme** et on **continue de scroller** ; **taper l'aperçu** → ouverture de la **fiche détail complète** (`DetailSheet` §9). Animation d'ouverture/fermeture 200 ms.
- **Provenance** : le feed mêle les publications suivies et une découverte pondérée ; chaque contenu renvoie au **profil de l'auteur** (F6). États : chargement (skeleton vidéo/vignette), erreur (retry), offline, empty. **Invité** : le feed est visible en mur souple ; Like/Suivre/publier déclenchent l'invite (E4).

**J2. Panel commentaires** *(V1.1)*
Bottom sheet 80 % : liste des commentaires (avatar, nom, texte, **like de commentaire**, **répondre**, **signaler**), champ de saisie en bas, indicateur d'envoi. Empty « Soyez le premier à commenter ».

### Groupe K — Connexion TikTok *(V1.2)*

**K1. Connexion TikTok**
**Sheet de consentement éclairé (45 %)** **avant** l'OAuth : ce qui sera partagé + logo TikTok + **« Continuer vers TikTok »** + Annuler → OAuth natif (webview) → toast succès + chip `@username` dans Paramètres (G7). Point d'entrée : composeur social (H2, toggle « Publier aussi sur TikTok ») ou Paramètres → Comptes liés. Déconnexion = rouge clair + dialog. Labels ARIA sur la redirection externe.

---

## 12. Wizards d'enregistrement de contenu

> Spécialise H1/H2 en une **spécification complète par type et sous-type**. Objectif non négociable : **chaque champ saisi correspond à un élément de la fiche (§9) et à une colonne du modèle de données** — aucun champ orphelin, aucun champ fantôme. Tous les prix sont **saisis et stockés en XOF**. Tout est **mono-pays Bénin** ; tous les libellés passent par l'i18n.

### 12.1 Portée & principes
- Trois familles de fiche enregistrables : **Lieu**, **Établissement** (sous-types), **Événement** — plus le sous-type **Service de guide** (classe Commercial). *(Le contenu social — photo/diaporama, vidéo en V1.1 — suit le composeur H2, distinct du `ListingWizard`.)*
- **Création réservée aux rôles habilités** (Guide, Promoteur, Institution, Admin) : un **utilisateur simple n'atteint jamais** ce wizard (il publie du social et peut « signaler un lieu manquant »).
- **Classe de fiche implicite** (`listing_class` : Patrimonial / Commercial / Événementiel) — **jamais demandée** à l'auteur : dérivée du rôle créateur et du sous-type ; **Admin peut corriger**. Le **claim n'existe que pour Commercial/Événementiel**.
- **Un seul composant `ListingWizard`**, paramétré par `type` puis `sous-type` : étapes 1, 2, 4, 5 communes ; **étape 3 (Détails) = branche dynamique**.
- Légende : **✓** requis · **○** optionnel · **cond.** requis sous condition.

### 12.2 Taxonomie des types & sous-types

| Type | Sous-types / catégories | Unité de prix |
|---|---|---|
| **Lieu** | Plage · Site historique · Monument · Marché · Nature & Parc · Cité lacustre · Lieu de culte · Musée · Point de vue · Place/Esplanade · Jardin | entrée (ou Gratuit) |
| **Établissement — Hébergement** | Hôtel · Motel · Auberge · Résidence · Maison d'hôtes | par nuit |
| **Établissement — Restauration** | Restaurant · Fast-food · Pâtisserie/Salon de thé | par personne |
| **Établissement — Vie nocturne & boissons** | Bar · Maquis · Café · Lounge · Club/Boîte | consommation (entrée si Club) |
| **Événement** | Concert · Festival · Conférence · Atelier · Exposition · Spectacle · Sport · Randonnée/Sortie · Gastronomie · Religieux/Culturel · Salon/Foire | billet (ou Gratuit) |

### 12.3 Architecture du wizard (étapes)

| Étape | Contenu | Type |
|---|---|---|
| **1 — Type & base** | Type (choisi en H1), sous-type, nom, description, langue de saisie | commune |
| **2 — Localisation** | Ville, quartier, adresse, **point GPS** (map picker §12.10) | commune |
| **3 — Détails** | **Branche selon le sous-type** (§12.5–12.9) : horaires, prix, services, contact, billetterie… | **dynamique** |
| **4 — Médias** | Photos (cover + ordre), vidéo optionnelle, alt text (§12.11) | commune |
| **5 — Récap & publication** | Preview réelle de la fiche (§9) + de la carte (§8), CTA Publier → modération | commune |

### 12.4 Champs communs (socle — tous types)

| Champ | Req. | Format | Règle | Colonne | Élément fiche |
|---|---|---|---|---|---|
| Type | ✓ | choix (H1) | ∈ {lieu, etablissement, evenement} | `listings.type` | gabarit |
| Sous-type | ✓ | select | ∈ liste du type | `listings.subtype` | overline / badge |
| Nom | ✓ | texte 3–80 | non vide, anti-doublon souple (alerte si nom+ville déjà présent) | `listings.name` | titre du hero |
| Description | ✓ | multiligne 40–1500 | bornes + compteur | `listings.description` | bloc description |
| Langue de saisie | ✓ | select (FR défaut) | une des 6 | `listings.content_lang` | i18n contenu |
| Ville | ✓ | select (communes BJ) | ∈ `cities` | `listings.city_id` | label ville |
| Quartier / zone | ○ | select / texte | cohérent avec la ville | `listings.district` | overline |
| Adresse | ✓ | texte (géocodage) | non vide | `listings.address` | carte / itinéraire |
| **Point GPS** | ✓ | épingle | **dans le polygone Bénin** | `listings.lat/lng/geog` | carte + Itinéraire |
| Photos | ✓ (≥1) | upload, cover + ordre | ≥1 cover ; ≥3 recommandées | table `media` | hero + miniatures |
| Vidéo courte | ○ | upload | limites §12.11 | `media` (video) | hero |
| Tags | ○ | chips libres | ≤10, ≤24 car | `listings.tags[]` | recherche sémantique IA |

### 12.5 Lieu — spécifiques

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Sous-catégorie | ✓ | ∈ liste Lieu | `place_details.place_category` | overline |
| Accès | ✓ | radio Gratuit / Payant (défaut Gratuit) | `place_details.is_free` | badge Gratuit / chip prix |
| Tarif d'entrée (XOF) | cond. | requis si Payant | `place_details.entry_fee_xof` | chip prix |
| Note tarifaire | ○ | texte court | `place_details.fee_note` | sous le prix |
| Horaires d'accès | ○ | éditeur 7 j ou « Accès libre 24h/24 » | `listings.opening_hours` | info pratique |
| Équipements sur site | ○ | multi (parking, guide, toilettes, PMR…) | `listing_amenities` | rangée services |
| Contact (site / tél.) | ○ | URL / E.164 | `listings.website_url/contact_phone` | liens |

*(Stats vues/likes/note = système, jamais saisies.)*

### 12.6 Établissement — socle commun

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Sous-type | ✓ | ∈ liste Établissement | `listings.subtype` | badge catégorie |
| **Horaires hebdo** | ✓ | éditeur 7 j, créneaux, 24h/24, Fermé | `listings.opening_hours` | **pill Ouvert/Fermé** |
| Prix « à partir de » (XOF) | ✓ | unité selon sous-type | `listings.price_from_xof` + `price_unit` | chip prix + barre |
| Gamme de prix | ○ | €/€€/€€€ | `listings.price_tier` | filtre gamme |
| **Services** | ✓ (≥1) | multi à icônes, filtrés par sous-type | `listing_amenities` | rangée services |
| Contact — Téléphone | cond. | E.164 (+229) ; ≥1 canal requis | `listings.contact_phone` | bouton Appel |
| Contact — WhatsApp | cond. | E.164 | `listings.contact_whatsapp` | bouton WhatsApp |
| Contact — Site / Email | ○ | URL https / email | `listings.website_url/email` | boutons |
| Réseaux sociaux | ○ | URLs | `listings.socials` | liens |

### 12.7 Hébergement *(par_nuit)*

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Classification étoiles | cond. | 0–5, **requis pour Hôtel** | `lodging_details.star_rating` | badge « Hôtel 4★ » |
| Types de chambres | ○ | liste {nom, prix XOF/nuit} ; le min → `price_from_xof` | `room_types` | **bloc tarifs** |
| Nombre de chambres | ○ | entier ≥1 | `lodging_details.room_count` | info |
| Check-in / Check-out | ○ | heures | `lodging_details.checkin/checkout_time` | info |
| Équipements hôtel | (socle) | multi étendu (Piscine, Petit-déj, Navette…) | `listing_amenities` | rangée services |

### 12.8 Restauration *(par_personne)*

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Type(s) de cuisine | ✓ | multi (Béninoise, Africaine, Grill, Libanaise…) ; ≥1 | `food_details.cuisines[]` | chips cuisine |
| Repas servis | ○ | multi (Petit-déj/Déj/Dîner) | `food_details.meals[]` | info |
| Réservation possible | ○ | toggle | `food_details.reservation` | service Réservation |
| Menu | ○ | URL ou photos | `food_details.menu_url`/`media` | bouton « Voir le menu » |
| Prix moyen (XOF) | ✓ (socle) | par personne | `price_from_xof` | chip prix |

### 12.9 Vie nocturne *(consommation / par_entree si Club)*

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Genre de lieu | ✓ | = `subtype` | `nightlife_details.venue_kind` | badge catégorie |
| Caractéristiques | ○ | multi (Musique live, DJ, Terrasse, Karaoké…) | `listing_amenities` | rangée services |
| Âge minimum | cond. | **requis si Club** | `nightlife_details.min_age` | pill « Âge min » |
| Prix conso / entrée (XOF) | ✓ (socle) | unit = consommation / par_entree | `price_from_xof` + `price_unit` | chip prix |

### 12.10 Événement — spécifiques

| Champ | Req. | Règle | Colonne | Fiche |
|---|---|---|---|---|
| Catégorie | ✓ | ∈ liste Événement | `event_details.category` | overline |
| **Date & heure de début** | ✓ | datetime (GMT+1) ; pilote tri + état Terminé | `event_details.start_at` | **pastille date** |
| Date & heure de fin | ○ | ≥ début | `event_details.end_at` | durée |
| Récurrence | ○ | aucune / dates multiples / hebdo *(V1.1)* | `event_details.recurrence` | occurrences |
| Lieu de l'événement | ✓ | **rattacher** un Établissement/Lieu **ou** adresse+GPS | `event_details.venue_listing_id` ou `listings.lat/lng` | overline lieu + carte |
| Organisateur (nom) | ✓ | non vide | `event_details.organizer_name` | info |
| Organisateur (contact) | ✓ | E.164 / email | `event_details.organizer_contact` | contact |
| Billetterie — type | ✓ | radio Gratuit / Payant | `event_details.ticket_type` | badge Gratuit / bloc billet |
| Tranches de billets | cond. | liste {libellé, prix XOF} ; requis si Payant ; **mode plein** | `ticket_tiers` | bloc billetterie |
| URL billetterie externe | cond. | URL https ; requis si Payant + vente externe | `event_details.ticket_url` | CTA **rouge** → deep-link |
| Capacité | ○ | entier ≥1 | `event_details.capacity` | info |

**État « Terminé » (dérivé) :** si `now > end_at` (ou `> start_at`) → ruban « Terminé », bouton billet désactivé, CTA contact masqué (§9). Aucune action manuelle.

### 12.11 Géolocalisation, médias & validation
- **Map picker** : autocomplétion biaisée Bénin, dépôt/déplacement d'épingle, « Utiliser ma position » ; reverse-geocoding pré-remplit l'adresse + suggère ville/quartier. **Validation mono-pays** : point **dans le Bénin** (bounding box lat 6,10–12,50 / lng 0,77–3,86 puis polygone serveur). Hors Bénin → blocage « Kwabor ne référence que des lieux au Bénin. »
- **Médias** : photos ≥1 cover (≥3 recommandées), JPG/PNG/**WebP**, **≤ 8 Mo/photo**, compression + variantes serveur ; cover + drag-reorder. Vidéo ≤ **60 s**, ≤ **50 Mo**, transcodage (V1.1). **Alt text** auto par image, éditable.
- **Validation transverse** : « Suivant » désactivé tant que l'étape est invalide (erreur inline rouge) ; Nom 3–80 (anti-doublon souple) ; Description 40–1500 ; GPS Bénin ; Prix XOF ≥0 (« Gratuit » désactive le montant ; transactionnel jamais compact) ; Téléphone/WhatsApp E.164 `+229` ; URL `https://` ; Dates `end_at ≥ start_at` (passé → publié « Terminé ») ; Étoiles 1–5 requis pour Hôtel.

### 12.12 Modèle de données (Supabase / PostgreSQL)

**Enums** — `listing_type` (lieu, etablissement, evenement) · **`listing_class` (patrimonial, commercial, evenementiel)** · `listing_status` (brouillon, en_attente, publie, rejete, archive) · `price_unit` (par_nuit, par_personne, consommation, par_entree, aucune) · `ticket_type` (gratuit, payant) · **`user_role` (user, guide, institution, promoteur, admin)** · `claim_status` (en_attente, approuve, refuse) · **`report_status` (nouveau, en_revue, traite, rejete)** · **`social_media_type` (photo, diaporama, video)** · `campaign_status` (brouillon, en_attente_paiement, active, terminee) · `payment_status` (en_cours, reussi, echoue).

**`listings` (socle polymorphe)** — `id`, `type`, `subtype`, **`listing_class`**, `category_id`, `owner_id` (nullable → non revendiqué ; **toujours NULL en Patrimonial**), **`steward_id`** (nullable, institution responsable d'une fiche patrimoniale), `submitted_by`, `status`, `name`, `slug`, `description`, `content_lang`, `city_id`, `district`, `address`, `lat`, `lng`, `geog`, `google_place_id`, `price_from_xof`, `price_unit`, `price_tier`, `opening_hours` (JSONB), `contact_phone`, `contact_whatsapp`, `website_url`, `email`, `socials` (JSONB), `tags` (text[]), `verified`, `sponsored_until` (nullable), **`editorial_pin_until`** (nullable — mise en avant éditoriale, ≠ sponsoring), `rating_avg`, `rating_count`, `views_count`, `likes_count`, `created_at`, `updated_at`, `published_at`.

**Extensions 1-1** — `place_details`, `lodging_details`, `food_details`, `nightlife_details`, `event_details`, **`guide_details`** (langues[], zones[], spécialités[], tarif_indicatif_xof, agrément, expérience).
**Identité & rôles** — **`profiles`** (user_id, nom, prénom, avatar, cover, bio, city_id) ; **`user_roles`** (user_id, `user_role`, `verified`, statut, justificatif_url, **cumulables**) — un compte peut être à la fois `promoteur` et `guide`.
**Contenu social (UGC)** — **`social_posts`** (id, author_id, `social_media_type`, **`listing_id` NOT NULL** → rattachement obligatoire, légende, `content_lang`, statut de modération, watermark_applied, likes_count, created_at) ; **`social_media`** (post_id, ordre, url, alt) ; **`post_likes`**, **`comments`** (V1.1), **`follows`** (follower_id, followee_id).
**Tables filles fiche** — `room_types`, `ticket_tiers`, `media`, `listing_amenities` (→ `amenities`), **`reviews`**, **`review_replies`**, **`favorites`**, **`likes`**, **`notifications`**, **`campaigns`** (`cost_xof`), **`payments`** (`amount_xof`), **`linked_accounts`** (TikTok-ready, nullable, chiffré).
**Référentiels** — `cities` (communes BJ), `categories` (avec **`default_listing_class`** par sous-type), `amenities`, **`guide_languages`**, **`guide_specialties`**.
**Gouvernance** — `claims` (**contrainte : `listing_class ∈ {commercial, evenementiel}`**), **`missing_place_reports`** (reporter_id, nom, type_présumé, city_id, lat/lng?, note, photo?, `report_status`, assigné_admin) — canal H4, **ne crée pas de fiche**, **`listing_revisions`** (audit/édition/enrichissement — trace et réversibilité des contributions d'Institution), `moderation_log`, **`promoter_invites`** (pré-inscription → lien d'activation E8, token, statut).

- **Prix toujours en XOF** (`price_from_xof`, `ticket_tiers.price_xof`, `room_types.price_xof`, `guide_details.tarif_indicatif_xof`, `campaigns.cost_xof`, `payments.amount_xof`) ; conversions à l'affichage uniquement (§4).
- **PostGIS** : index GiST sur `geog` ; `ST_DWithin` pour la proximité (tri, Surprenez-moi).
- **RLS pilotée par `user_role` × `listing_class`** :
  - lecture publique des `listings.status = publie` et des `social_posts` publiés ;
  - **fiche Commercial/Événementiel** : écriture par `owner_id = auth.uid()` (rôle `promoteur`/`guide` vérifié) ;
  - **fiche Patrimonial** : écriture/enrichissement par rôle `institution` (périmètre/steward) ou `admin` ; **jamais** par `user` ni par `guide` ;
  - **`social_posts`** : insert par tout compte authentifié **avec `listing_id` non nul** (contrainte) ; un `user` n'a **aucun** droit d'écriture sur `listings` ;
  - **`missing_place_reports`** : insert par tout compte authentifié ; lecture/traitement par `admin` ;
  - secrets paiement/OAuth **jamais** côté client.

### 12.13 Permissions, modération & revendication
- **Permissions (rôle × classe)** — voir la matrice §PRD 6.11.2. En résumé : **Utilisateur** → social (rattaché) + `missing_place_reports`, **aucune fiche** ; **Guide** → fiche **Service de guide** (Commercial) + **Événements**, **sans enrichissement** ; **Institution** → **Patrimonial** (création/enrichissement) + **stewardship** ; **Promoteur** → **Commercial/Événementiel** (possession) ; **Admin** → tout + attribution/cession de stewardship + correction de classe + mise en avant éditoriale.
- **Classe implicite** : dérivée à la création (`categories.default_listing_class` selon le sous-type ; créateur Institution/Admin → Patrimonial ; créateur Promoteur → Commercial ; Événement → Événementiel). **Admin peut corriger.** Un « Lieu » peut donc être **Commercial** (parc d'attraction, aire de jeux, galerie…) et s'afficher sous **Lieux**.
- **Cycle** : `Brouillon → En attente → Publié` ; `Rejeté (+ motif) → corriger → Brouillon` ; `Publié → Archivé`. **Une fiche est publiée une fois puis mise à jour à volonté.** **Pipeline hybride** : règles auto (mots-clés, GPS Bénin, doublon, image) → file humaine (fiches, avis, **UGC**, `missing_place_reports`). Badge « Vérifié » posé après vérification du rôle.
- **UGC** : `social_posts.listing_id` **obligatoire** (contrainte FK NOT NULL) ; **watermark non maskable** à l'export ; **licence de contenu** (CGU) + **procédure de retrait** ; modération image automatique + signalement. UGC **hors des fiches** (feed + profil).
- **Édition d'un publié** : champs **sensibles** (nom, type/sous-type, GPS, prix, billetterie) → **re-modération** (ancienne version en ligne via `listing_revisions`) ; champs **mineurs** → publication directe + journalisation.
- **Revendication (claim)** : flow I12, **restreint à Commercial/Événementiel** (contrainte sur `claims`) ; Patrimonial non revendicable (stewardship attribuée par Admin — I17).
- **Activation** : `promoter_invites` génère le lien d'activation (E8) ; à l'activation, le compte reçoit le rôle et l'`owner_id` de sa fiche pré-remplie.

---

## 13. Navigation & architecture de l'information

```
Bottom Nav (plate, 5 items égaux — aucun central)
├── Accueil (Explore)
│   ├── Onglet Lieux ───────────► DetailSheet Lieu  (Patrimonial ou Commercial)
│   ├── Onglet Événements ─────► DetailSheet Événement
│   ├── Onglet Hôtels & Restau ► DetailSheet Établissement
│   ├── Recherche (mots-clés) ─► état actif ──► résultats
│   ├── « Trouver un guide » ──► fiches Service de guide ──► DetailSheet guide
│   └── FAB IA ────────────────► Assistant IA (sheet) ──► DetailSheet
│        └── (appui long) ─────► Surprenez-moi ─────────► DetailSheet
├── Social (feed photo/diaporama ; vidéo V1.1)
│      ├── Mention ──► aperçu de fiche ≤ 25 % ──► DetailSheet complète
│      └── Suivre / Like / Partager / Commentaires (V1.1) / Profil auteur
├── Ajouter (+ CONTEXTUEL par rôle)
│      ├── Utilisateur → Composeur social (entité obligatoire) + « Signaler un lieu manquant »
│      ├── Guide → social + Événement + Service de guide
│      ├── Promoteur → social + Événement + Fiche (Commercial)
│      └── Institution → social + Fiche patrimoniale + Enrichir
├── Notifications ─────────────► DetailSheet (deep link selon type)
└── Profil
    ├── Publications (contenus sociaux de l'auteur)
    ├── Contenus publiés (rôles créateurs) ──► reprise brouillon / motif de rejet
    ├── Favoris            ◄──── (les favoris vivent ici, pas en navbar)
    ├── Statistiques (Kwabor + TikTok séparés, V1.2)
    ├── Signaler un lieu manquant ──► file Admin Kwabor
    ├── Modifier le profil
    ├── Sélecteur Personnel/Promoteur ──► Tableau de bord (si habilité)
    │      ├── Mes fiches · Avis (réponse) · Statistiques · Facturation
    │      ├── Promouvoir ──► Ciblage ──► Paiement Mobile Money ──► Reçu
    │      ├── [Guide] Service de guide + Événements
    │      └── [Institution] Enrichissement / stewardship (Patrimonial)
    └── Paramètres ──► sous-écrans ──► Comptes liés ──► Connexion TikTok

Hors Bottom Nav :
1er lancement : Intro vidéo (sautable) ──► « Se connecter ou s'inscrire »
   ├── S'inscrire ──► [Email : OTP → mot de passe → Nom/Prénom → Ville/GPS → Devise]
   │                  [Google/Apple(iOS) : révision Nom/Prénom → Ville/GPS → Devise]
   ├── Se connecter ──► [email → mot de passe] / Google / Apple(iOS) ──► Mot de passe oublié
   └── « Ne pas s'inscrire » ──► MUR SOUPLE (Accueil invité, prix FCFA)
Lien d'invitation Promoteur ──► Activation « Activez votre compte {Nom} » ──► Tableau de bord
Langue : détectée auto ; pastille discrète sur « Se connecter ou s'inscrire » + Paramètres
Toute fiche ──► Donner mon avis · Ajouter une photo (→ composeur social) · Partager · Revendiquer (Commercial/Événementiel)
Tout contenu (fiche · avis · post social) ──► Signaler ──► Report Modal
Tout prix plein ──► Convertisseur de devise
```

**Règle de transition :** toute fiche s'ouvre en **modal sheet remontante** depuis n'importe quel point d'entrée — un seul `DetailSheet` paramétrable. **Mur souple** : Explore/Détail/Recherche/IA/Social consultables sans compte (prix **FCFA**) ; **Like, Favori, avis, publier, suivre, changement de devise** déclenchent l'invite d'inscription (E4). **Création de fiche réservée** aux rôles habilités ; l'utilisateur contribue par le **social** (entité obligatoire) et **« Signaler un lieu manquant »**.

---

## 14. Responsive — Mobile / Tablette / Desktop (PWA)

| Breakpoint | Largeur | Grille Explore | Détail | Navigation |
|---|---|---|---|---|
| **Mobile** | < 600 px | 2 colonnes | Modal sheet 92 % | Bottom Nav |
| **Tablette** | 600–1024 px | 3 colonnes | Modal centrée, max 640, 85 % | **Rail latéral gauche** (5 entrées) |
| **Desktop (PWA)** | > 1024 px | 4 colonnes, contenu plafonné 1280 centré | **Split-view** (grille 60 % / détail 40 %) | Rail latéral gauche |

- Le **FAB IA** reste flottant à toutes les tailles (bas-droite du viewport sur desktop).
- Le **Filter Drawer** passe de bottom sheet (mobile) à **panneau latéral persistant** (desktop).
- Le **tableau de bord Promoteur** passe en layout 2 colonnes (navigation + contenu) sur desktop.
- Pas de version desktop native : le web reste **PWA responsive**.

---

## 15. Motion & micro-interactions

| Interaction | Spécification |
|---|---|
| Ouverture `DetailSheet` / bottom sheet | 300 ms `cubic-bezier(0.32, 0.72, 0, 1)` (décélération nette, sans rebond) |
| **Like (cœur)** | scale 1 → 1.3 → 1, 200 ms |
| Heart Burst (double-tap vidéo) | Lottie 200 ms |
| Transition Bottom Nav | 180 ms ease-out |
| Crossfade Surprenez-moi | 250 ms + léger scale |
| Skeleton → contenu | fade 150 ms |
| Parallax cover Profil / hero | **léger uniquement** (translation ≤ 12 %), CSS/Compose |
| Apparition image (lazy-load) | fade-in 200 ms depuis le placeholder dégradé |
| Speed-dial FAB (appui long) | expansion 180 ms des 2 entrées |

---

## 16. Performance — « premium sans poids » (garde-fou non négociable)

- **Mur d'exploration : P75 < 1,5 s** sur 3G/4G dégradée.
- Overlays/dégradés/profondeur = **CSS/Compose**, jamais des images sombres empilées.
- Photos en **WebP/AVIF**, variantes responsives, **lazy-load**, **placeholder dégradé immédiat**, CDN + compression serveur.
- **Aucun blur temps réel massif**, aucun parallax lourd. Le frosted utilise un blur **léger (≤ 8 px)** sur petites surfaces seulement.
- Webfonts : `font-display: swap` + fallback système.
- Offline : cache du dernier mur (lecture seule), bannière persistante, file locale des likes/favoris à synchroniser.

---

## 17. Internationalisation & accessibilité (transverse)

**i18n.** 6 langues (FR défaut MVP, EN V1.1, puis PT/DE/ES/IT) ; **toute** l'UI via i18n dès le MVP. 4 devises via `PriceTag` (§4) ; **invité en FCFA**, €/$/₦ réservés aux comptes (le changement de devise en invité déclenche l'invite d'inscription — E4). Formats date/heure localisés (ICU/CLDR). **Tolérance à l'expansion de texte** : composants élastiques, **aucune largeur de label figée** (DE +30–40 %), tests pseudo-localisés. Pas de RTL (langues latines). La **langue est détectée automatiquement** depuis la locale système (repli déterministe : première langue livrée de la liste ordonnée de l'OS, mapping sur le sous-tag en ignorant la région, sinon EN puis FR) et appliquée **dès l'intro vidéo** ; **aucun sélecteur imposé** (écran de choix conditionnel uniquement si la locale n'est pas couverte + **pastille de langue** sur l'écran « Se connecter ou s'inscrire » et dans les Paramètres). Le choix explicite de l'utilisateur prime et persiste (§11 E2–E3).

**Accessibilité (WCAG AA).**
- Contraste ≥ 4.5:1 partout, **y compris titre sur hero et texte sur overlay de carte**.
- Labels ARIA / VoiceOver sur tout composant interactif ; **focus order documenté par écran** (modificateur `focusOrder` explicite en Compose).
- Cibles tactiles ≥ 44 px ; **alt text** auto par image (éditable au wizard).
- États annoncés : « Événement terminé », « Interrupteur activé », « Ouvre une page externe », « Paiement en cours ».
- Dark mode = vrai thème accessible (tokens AA §3.3), pas un simple assombrissement.

---

*Document lié : `PRD.md` — vision produit, périmètre, exigences fonctionnelles, parcours, architecture technique, KPIs. Ce `DESIGN.md` est la source de vérité du design system Kwabor : tokens monochrome, en-tête allégée, navbar plate, cartes overlay, fiche immersive, composant prix multidevise, et la spécification écran par écran complète (découverte, notifications, compte, profil, paramètres, ajout, espace Promoteur, paiement, social).*
