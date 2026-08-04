# 0029 — Borner l’historique et séparer ses usages

- **Statut** : accepté
- **Date** : 2026-08-04
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile et Sécurité ; Juridique/DPO requis avant
  activation de la rétention serveur et de la personnalisation
- **Gate externe** : rétention serveur et personnalisation activée interdites avant validation Juridique/DPO
- **Complète** : ADR-0027, ADR-0028
- **Remplace** : —

## Contexte et problème

Search doit afficher des recherches récentes utiles sans transformer chaque frappe en donnée durable.
Pour un compte connecté, ces récents doivent survivre à une réinstallation et rester cohérents entre
appareils. Pour un invité, ils doivent rester liés à l’appareil. Le même historique peut aussi produire
des signaux d’intérêt pour le futur Assistant IA et le fil organique, mais cette finalité est distincte
de l’affichage des récents et reste désactivée par défaut.

L’ADR-0027 a déjà décidé que Room est une copie locale liée à l’appareil et que toute donnée durable de
compte possède une autorité Supabase protégée par RLS. L’ADR-0028 a déjà séparé la soumission Search de
l’historique et interdit le texte brut dans Analytics et les logs. Les règles de scope, les plafonds, la
resoumission et le défaut de personnalisation sont donc actés. La durée de rétention du texte brut côté
serveur exige encore une validation Juridique/DPO avant toute migration persistante activée en production.

## Options envisagées

- **Historique uniquement local** : simple, mais ne survit pas à la réinstallation et ne permet pas la
  synchronisation d’un même compte.
- **Historique serveur unique, y compris pour les invités** : facilite la synchronisation, mais crée une
  identité serveur implicite et un transfert silencieux contraire au parcours invité.
- **Autorité selon le scope et usages séparés** : invité local, compte serveur avec miroir local,
  personnalisation distincte et protocoles d’effacement synchronisables.

## Proposition

Le socle domaine expose l’autorité selon le scope, les usages séparés, la capture après soumission valide,
l’import invité explicite, les plafonds et la resoumission sans doublon. La durée de rétention recommandée
reste volontairement hors des invariants de code, de schéma et de configuration tant que la validation
Juridique/DPO n’est pas tracée.

### Capture et confidentialité

- Seule une requête canonique ayant franchi une soumission Search valide peut être enregistrée. Une frappe,
  une suggestion survolée, une autocomplétion non validée, un retry, un refresh ou une pagination ne crée
  jamais d’entrée.
- Le texte brut sert uniquement à afficher et rejouer un récent. Il n’entre jamais dans Analytics, les logs,
  les rapports de crash ni les paramètres du modèle IA. Les modèles domaine qui le portent expurgent leur
  représentation texte afin qu’un log accidentel ne révèle pas la requête.
- L’Assistant IA et le fil organique pourront consommer uniquement un résumé structuré et borné exposé par
  un contrat distinct. Le classement et l’attribution sponsorisés ne consomment jamais ces signaux.

### Scopes et synchronisation

- Le scope invité reste local à l’appareil. Il n’est jamais envoyé au serveur.
- Le scope authentifié est lié à l’identifiant du compte. Supabase avec RLS propriétaire sera l’autorité ;
  Room n’en sera qu’un miroir local lié à l’appareil.
- Une déconnexion change immédiatement le scope visible vers l’invité sans supprimer le miroir du compte.
  Un autre compte ne peut jamais lire ce miroir.
- L’import invité exige une confirmation explicite représentée par une demande domaine validée. Il n’existe
  aucun chemin de fusion automatique à la connexion.
- La réconciliation d’un compte sera un détail du repository et de la couche data, pas une action domaine
  manuelle. Le serveur attribuera le timestamp autoritatif d’une soumission authentifiée ; le client ne peut
  pas imposer son horloge à l’autorité du compte.
- La future synchronisation devra utiliser des mutations idempotentes, des révisions serveur, des tombstones
  pour l’effacement unitaire et un watermark pour l’effacement global. Ces mécanismes appartiennent aux
  futures couches Supabase, Room et data ; ils ne sont pas simulés par ce premier lot domaine.

### Bornes et défaut actés

- Conserver au maximum **200 requêtes canoniques distinctes actives** par compte côté serveur.
- Conserver au maximum **50 requêtes canoniques distinctes** par scope et par appareil en local.
- Initialiser la personnalisation par activité à **désactivée**. Son activation exige une action explicite.
- Une éviction du miroir local d’un compte ne déclenche jamais une suppression serveur. Pour un invité,
  l’éviction locale est définitive.

La désactivation n’efface pas les récents et n’empêche pas leur synchronisation ; elle interdit seulement la
production et l’usage des signaux dérivés. Avant d’activer la personnalisation en production, Juridique/DPO
doit encore valider l’information utilisateur, le retrait et le traitement éventuel de l’historique antérieur
au consentement.

### Resoumission canonique

La resoumission d’un texte canonique identique remonte l’entrée existante au lieu de créer un doublon :

- l’unicité porte sur le scope et le texte déjà canonisé ;
- `created_at` reste inchangé et `last_submitted_at` est actualisé ;
- le serveur attribue `last_submitted_at` pour un compte, tandis qu’une future horloge injectée gouvernera
  le scope invité ;
- aucun compteur de soumissions n’est stocké tant qu’un besoin produit distinct ne le justifie.

### Rétention serveur bloquée

La recommandation de travail est une rétention glissante de **180 jours** depuis `last_submitted_at`. Une
resoumission repousserait cette échéance et la purge supprimerait le texte brut expiré. Cette durée n’est pas
encore active : la migration Supabase et son job de purge ne doivent pas être déployés tant qu’un humain
Juridique/DPO n’a pas validé la durée, les sauvegardes, les tombstones et la durée de vie des signaux dérivés.

### Effacement déjà requis

- L’effacement unitaire et global doit retirer immédiatement le texte brut du scope visible. La suppression
  du compte purge l’historique serveur, son miroir local et tous les signaux dérivés.

### Frontière de ce lot

Le domaine Kotlin pur expose les valeurs validées et les contrats repository. Il ne dépend ni de
Room, ni de Supabase, ni de Compose, ni de SwiftUI, ni d’un SDK externe. Le schéma, les RLS, l’outbox, les
watermarks, le coordinateur de session et les interfaces plateforme seront livrés par des lots séparés.

## Conséquences

**Positives**

- Les règles déjà actées sont testables avant d’introduire un schéma persistant.
- Les scopes rendent explicite l’isolation invité, compte A et compte B.
- L’import silencieux est refusé et le domaine initialise explicitement la personnalisation à désactivée.
- La future data peut rester compatible avec les suppressions hors ligne sans exposer une commande de
  synchronisation prématurée au domaine.

**Négatives / compromis assumés**

- Room et Supabase doivent appliquer des plafonds différents et une éviction asymétrique compte/invité.
- L’absence temporaire de durée de rétention approuvée interdit de déployer la migration persistante
  correspondante en production.
- Les tombstones et mutations idempotentes ajoutent un coût de stockage et de nettoyage côté serveur.

**À revoir si**

- Juridique/DPO approuve ou modifie la durée de rétention et les modalités de personnalisation ;
- une exigence légale impose une exportation ou une autre politique de conservation ;
- le support d’une fenêtre offline plus longue exige de conserver les tombstones plus longtemps ;
- un futur usage demande le texte brut hors de Search — il exige alors une nouvelle décision explicite.

## Références

- [PRD — Recherche, Assistant IA et paramètres](../../PRD.md)
- [Design — A4, B1, G4 et G8](../../DESIGN.md)
- [ADR-0027 — Persistance locale liée à l’appareil](0027-device-bound-local-persistence.md)
- [ADR-0028 — Recherche par mots-clés](0028-versioned-keyword-search-and-bounded-offline-fallback.md)
