# 0033 — Cloisonner le client Favoris par session et synchroniser ses vues dans les deux sens

- **Statut** : accepté
- **Date** : 2026-08-09
- **Décideurs** : Produit Kwabor, Architecture mobile et Sécurité
- **Complète** : ADR-0027 et ADR-0032
- **Remplace** : —

## Contexte et problème

La même relation Favori est modifiable depuis Explore et depuis Profil → Favoris. Ces deux surfaces
ont des cycles de vie distincts, alors que la relation est privée et liée au compte courant. Une
réponse réseau, un effet bufferisé ou une pile de navigation sauvegardée pour le compte A ne doit
jamais être publié après une transition vers le compte B, y compris après déconnexion puis
reconnexion au même identifiant.

Une synchronisation seulement dirigée de Favoris vers Explore laisse aussi l'écran Favoris obsolète
après une mutation réussie dans Explore. À l'inverse, fusionner naïvement les résultats Like et
Favori permet à une réponse Like construite sur un ancien état de rétablir un Favori supprimé.

Le lot FAVORITES-001A2 doit résoudre ces problèmes sans anticiper Room ni l'outbox persistante du lot
SYNC-001.

## Décision

### Contrat partagé et séparation des responsabilités

Le domaine expose un `FavoritesRepository` distinct du catalogue. La couche data consomme uniquement
les RPC versionnées de l'ADR-0032, mappe explicitement DTO et modèles de domaine, et refuse une page
dont les identifiants, curseurs ou l'ordre `(favorited_at, listing_id) DESC` divergent du contrat.

`FavoritesPresenter` reste sans état. `FavoritesRuntime` possède l'état d'écran et traite les intents
dans un flux unidirectionnel. Android rend l'écran avec Compose Multiplatform et iOS avec SwiftUI
natif ; Favoris reste une destination enfant du Profil et ne devient jamais une sixième destination
racine.

### Frontière de session commune

Une instance `ViewerSessionScopeTracker` appartient au `KwaborCompositionRoot`. Elle produit un
`ViewerSessionScope(accountId, epoch)` normalisé :

- l'identifiant est nul tant que le compte ou son onboarding n'est pas prêt ;
- `epoch` augmente à chaque vraie transition de compte ou de connexion ;
- une publication identique est idempotente ;
- A → invité → A produit trois scopes distincts.

Explore et Favoris reçoivent exactement la même valeur. Tous les effets privés inter-feature portent
ce scope et sont rejetés par la source, l'adaptateur plateforme et la cible s'il ne correspond plus
au scope courant. Les générations internes des deux runtimes ne sont jamais comparées entre elles.

Le changement de scope purge synchroniquement les états privés Favoris et Explore par une écriture
atomique qui remplace ensemble le scope et les données visibles. Côté Explore, cette purge retire
aussi le snapshot de feed susceptible de contenir les interactions du viewer. Les commits de page,
de feed et de mutation utilisent un CAS sur le snapshot scopé ; ils ne peuvent donc pas réécrire un
état A après la purge B. Les révisions et overrides Explore ne sont consultés que pour leur scope
d'origine. Explore annule ensuite ses travaux viewer et recharge le feed lors de la restauration
initiale d'un compte authentifié.

Les commandes privées sont estampillées avec le scope visible dès leur entrée dans le runtime. Le
scope est revalidé sous mutex juste avant toute lecture de baseline et avant tout appel repository.
Une commande A encore en file, ou un child coroutine A qui n'a pas encore démarré, ne peut donc pas
émettre une requête avec la session réseau B.

La fermeture volontaire du mur souple utilise un intent explicite
`ClearPendingAuthentication`. Republier un scope invité identique ne signifie pas « annuler » et ne
doit pas supprimer l'action protégée qui attend la connexion. Les effets d'ouverture du mur souple
et de replay analytique portent eux aussi le scope exact et sont revalidés au dernier point de
consommation. Une seule action protégée est conservée : la dernière action utilisateur valide
remplace la précédente.

### Cohérence bidirectionnelle et concurrence

Une mutation Favori confirmée par le serveur publie un événement scoped :

- Favoris → Explore applique uniquement le champ `favorited` et conserve `liked` et `likes_count` ;
- Explore → Favoris retire immédiatement une carte, ou déclenche/planifie une lecture autoritative
  après un ajout.

Les échecs d'authentification, les échecs réseau et les intentions seulement optimistes ne publient
pas cet événement comme s'il s'agissait d'une confirmation serveur.

Explore et Favoris partagent la même instance singleton de `FavoritesRepository`. Les écritures
`setFavorite` sont sérialisées à cette frontière data avant le transport : une intention plus récente
ne peut donc pas être confirmée puis dépassée côté serveur par une requête locale plus ancienne
encore en vol. Le dernier appel entré dans cette séquence est la dernière écriture exécutée.

Explore maintient une révision par `(listingId, interactionKind)`. Un événement Favori invalide une
ancienne mutation Favori de la même fiche, mais n'invalide pas une mutation Like en vol. Le résultat
Like fusionne seulement `liked` et `likes_count`; le résultat Favori fusionne seulement `favorited`.
Les entrées de file volatile sont fusionnées avec la même clé au lieu de remplacer toute la liste.

Favoris conserve un tombstone en mémoire lorsqu'un retrait est confirmé pendant une page en vol.
Un chargement complet ou un refresh autoritatif commencé ensuite peut le purger ; l'append ne le
purge jamais. Un événement externe invalide aussi toute mutation locale plus ancienne de la même
fiche. Les messages et l'indicateur offline d'un retrait sont associés à sa fiche : la réussite ou
le démarrage d'un retrait concurrent ne peut pas effacer l'échec encore visible d'une autre fiche.

Si un retrait ou un tombstone vide la page visible alors qu'un curseur existe, le runtime enchaîne
automatiquement les pages jusqu'à retrouver une carte visible ou atteindre la fin. Une erreur
d'append arrête ce remplissage et reste actionnable ; aucun événement de retrait répété ne lance une
boucle de retry implicite. La demande de remplissage est aussi mémorisée lorsqu'un retrait survient
pendant un load, refresh ou append dont la réponse tardive peut devenir vide après filtrage.

Explore et Favoris distinguent la source hors ligne du contenu de celle des mutations volatiles.
L'état public est toujours la composition des deux sources : commencer une pagination ou recevoir
un événement d'une autre surface ne peut pas masquer une panne réseau ou une action encore en file.

### Cycle de vie et navigation

`ScreenAppeared` charge la première visite et rafraîchit les visites suivantes. `ScreenDisappeared`
marque la surface comme non visible. Un ajout externe reçu hors écran marque l'état sale, puis la
prochaine apparition recharge le serveur ; reçu à l'écran, il déclenche immédiatement ce reload.
Cette règle ferme la course où un refresh d'entrée finirait avant une mutation Explore.

Lors d'un changement de compte, Android purge la pile Profil active et toute pile Profil sauvegardée
par `saveState`. iOS masque et réinitialise la surface privée avant de publier le nouveau viewer. Les
adaptateurs filtrent encore les effets au moment de leur consommation, afin qu'un effet déjà
bufferisé ne puisse ni ouvrir une fiche privée ni modifier l'autre runtime.

## Hors de cette décision

- Room, outbox persistante, backoff durable et réconciliation après redémarrage (`SYNC-001`).
- Journal d'activité Favori, historique des retraits et personnalisation IA.
- Suppression des RPC legacy encore requises par les versions Store antérieures.
- Modification distante de l'interface ou ajout d'un client autre qu'Android/iOS.

## Conséquences

**Positives**

- aucune page ni effet Favori d'une ancienne session ne peut franchir une transition de compte ;
- Explore et Profil → Favoris convergent après toute mutation serveur réussie ;
- Like et Favori restent indépendants même lorsque leurs réponses arrivent dans un ordre opposé ;
- les retours à l'écran sont autoritatifs sans multiplier les requêtes pendant une même apparition ;
- la frontière offline durable reste explicite et n'est pas simulée par un état volatile présenté
  comme production-ready.

**Négatives / compromis assumés**

- une réentrée sur Favoris entraîne un refresh réseau ;
- les mutations Favori hors ligne d'Explore restent volatiles jusqu'au lot SYNC-001 ;
- les événements scoped ajoutent un contrat commun aux adaptateurs Android et iOS ;
- une incohérence de page serveur est refusée en bloc plutôt que partiellement affichée.

## Validation exigée

- A → B et A → invité → A avec page, mutation et effet A retardés ;
- effet bufferisé consommé après transition, rejeté par scope exact ;
- Like en vol pendant un changement Favori, sans restauration du Favori ni de sa file ;
- retrait et ajout concurrents depuis les deux surfaces, avec ordre transport sérialisé et dernier
  appel conservé côté serveur ;
- retrait pendant page en vol, ajout externe visible et hors écran, réentrée autoritative ;
- remplissage automatique après retrait, y compris réponse tardive entièrement tombstonée, avec
  arrêt terminal ou sur erreur d'append ;
- indicateur offline conservé pendant load, refresh et append tant qu'une de ses sources subsiste ;
- pile Profil Android sauvegardée puis changement de compte ;
- contrôleurs et vues iOS avec la même instance de tracker ;
- accessibilité du ruban diagonal « Terminé », des cartes et du retrait séparé.

## Références

- [ADR-0027 — Persistance locale liée à l'appareil](0027-device-bound-local-persistence.md)
- [ADR-0032 — Read model et mutation Favoris](0032-owner-favorites-read-model-and-compatible-mutation.md)
- [PRD — Favoris, mur souple et offline](../../PRD.md)
- [DESIGN — F3 Favoris et événement terminé](../../DESIGN.md)
