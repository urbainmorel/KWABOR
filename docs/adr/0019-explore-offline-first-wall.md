# 0019 — Mur Explore offline-first cumulatif et revalidation réseau

- **Statut** : accepté
- **Date** : 2026-08-01
- **Décideurs** : Produit Kwabor, Architecture, Data, Mobile
- **Complète** : ADR-0007, ADR-0011, ADR-0018

## Contexte et problème

Le RPC catalogue fournit une pagination stable par curseur et Room KMP fournit un stockage local,
mais Explore ne consommait encore ni les pages suivantes ni le cache. Une panne réseau effaçait le
mur visible et un démarrage hors ligne ne pouvait pas reconstruire les libellés de villes et de
catégories. La limite Room de 50 fiches créait aussi un risque de saut : conserver 50 fiches après
avoir reçu 60 résultats avec le curseur suivant aurait perdu les fiches 51 à 60 au redémarrage.

Android doit rendre le cache immédiatement, revalider sans détruire les cartes visibles et rester
aligné avec le futur client SwiftUI. Supabase, Room et DataStore ne doivent pas fuiter dans la
présentation ou le domaine.

## Options envisagées

- **Online-only avec retry manuel** : simple, mais contraire au PRD réseau intermittent et aux
  ADR-0007/0011.
- **Cache indépendant par page et curseur** : conserve toute la chaîne, mais rend l'invalidation,
  les pages manquantes et la reconstruction d'un mur cohérent plus complexes.
- **Dernier mur cumulatif par requête canonique** : rend un snapshot directement affichable et
  conserve uniquement un préfixe dont le curseur correspond exactement au dernier item persisté.

## Décision

Nous retenons un dernier mur cumulatif par requête Explore canonique, parce qu'il fournit un fallback
hors ligne directement affichable sans permettre à un curseur persistant de sauter des résultats.

Le domaine expose `ExploreFeedRepository` et des modèles Kotlin purs. L'implémentation data compose le
catalogue distant, le cache Room et l'horloge injectée. La clé versionnée encode sans ambiguïté tous
les filtres qui influencent le RPC ainsi que la taille de page ; elle exclut le curseur, car un
snapshot représente le mur cumulé d'une requête.

Les règles suivantes s'appliquent :

- au démarrage, un snapshot complet avec ses référentiels peut être rendu avant la revalidation ;
- un succès réseau remplace la première page, puis chaque append déduplique les identifiants en
  conservant strictement l'ordre serveur ;
- refresh et append conservent les cartes visibles pendant leur exécution et exposent des erreurs
  non destructives distinctes ;
- une panne réseau sans cache reste un échec bloquant ; avec cache, elle conserve le mur et active
  l'état offline ;
- les erreurs physiques Room sont typées. Un échec d'écriture après un succès réseau n'empêche pas
  l'affichage, mais remonte un avertissement de persistance ; une charge invalide n'est pas masquée ;
- le mur et ses villes/catégories sont publiés dans une même transaction Room v2 afin qu'un échec
  intermédiaire conserve intégralement le dernier snapshot cohérent ;
- avec des pages de 20 et une capacité de 50, seuls 40 éléments, soit le plus grand préfixe complet,
  sont persistés. L'écran peut dépasser 40 en mémoire, mais le curseur Room reste celui du quarantième
  élément tant qu'un préfixe plus long ne peut pas être stocké sans perte ;
- un append n'est pas lancé depuis un snapshot offline avant une revalidation réussie ;
- les appels réseau identiques sont single-flight dans un scope supervisé appartenant au graphe
  Koin et fermé avec lui ; l'annulation d'un observateur n'annule pas l'appel partagé ;
- un refresh partage son snapshot réseau complet. Un append ne partage que la récupération de la
  page distante, car sa fusion et sa persistance dépendent du snapshot propre à chaque appelant ;
- un coordinateur par clé réserve chaque génération avant son départ. Un refresh plus récent
  invalide tout append en vol ; entre deux appends concurrents, seul celui basé sur le snapshot le
  plus récent peut publier. Une génération devenue obsolète impose une revalidation ;
- chaque requête réseau reçoit avant son départ un instant de capture monotone dans le processus ;
  la première réservation est initialisée au-delà du maximum Room des murs, référentiels et contenus
  canoniques de fiches. Un recul d'horloge après redémarrage ne bloque donc pas la persistance ; Room
  refuse ensuite explicitement les écritures plus anciennes ou de même âge ;
- un append conserve l'instant de capture propre à chaque fiche héritée et n'horodate au nouvel
  instant que la page effectivement reçue. Les référentiels gardent leur propre instant de capture ;
  lors d'une réparation atomique, Room applique chaque composante seulement si elle est plus fraîche
  et préserve une version canonique déjà plus récente provenant d'une autre clé de mur ;
- si la lecture du watermark Room est indisponible, le réseau reste utilisable avec l'horloge et un
  avertissement de persistance. L'initialisation est retentée à la réservation suivante et un rejet
  Room n'est jamais interprété comme un succès ;
- une lecture détectant une corruption logique n'efface le mur ou les référentiels que si leur
  timestamp est encore celui effectivement lu. Un remplacement sain concurrent ne peut donc pas
  être supprimé par l'éviction retardée ;
- les coordonnées `BJ` des villes distantes comme persistées doivent appartenir au polygone du Bénin ;
  un couple partiel ou hors frontière rend le payload invalide et évince le référentiel corrompu ;
- une page non terminale doit changer de curseur et contenir au moins un identifiant encore absent
  du mur. Une page terminale peut ne contenir que des doublons afin de fermer proprement la
  pagination ;
- `is_sponsored_placement` reste l'autorité du snapshot serveur. Le client ne reclasse ni ne retire
  localement les badges sponsorisés ;
- la ville appliquée est persistée dans DataStore. Un identifiant devenu inconnu n'est remplacé
  qu'après une réponse de référentiels serveur autoritative ;
- la présentation reste un flux unidirectionnel avec états initial, refresh et append séparés.

## Conséquences

**Positives**

- Explore démarre avec le dernier mur cohérent sur réseau indisponible.
- Aucun curseur persistant ne peut sauter une portion non stockée.
- Les mêmes contrats et règles de cache sont réutilisables par SwiftUI sans partager l'UI.
- Les changements de ville, filtre ou onglet invalident naturellement la clé du mur.
- Une réponse tardive ne remplace pas une requête plus récente dans le ViewModel Android ni dans le
  cache Room du processus.
- Un changement de session Android invalide les interactions privées en vol, purge l'état
  like/favori du viewer précédent et recharge les interactions du nouveau compte.
- Après un échec d'écriture atomique, un append ne peut effacer l'avertissement qu'après avoir réparé
  ensemble le préfixe sûr du mur et ses référentiels. Sans préfixe persistant sûr, l'avertissement est
  conservé jusqu'à une nouvelle revalidation.

**Négatives / compromis assumés**

- Le réseau est revalidé à chaque ouverture ou refresh ; aucun TTL produit n'est introduit sans
  mesure terrain.
- Le préfixe persistant est limité à 40 fiches tant que la capacité du snapshot reste 50.
- La fraîcheur canonique des fiches Room repose encore provisoirement sur un instant de capture
  monotone local décrit dans ADR-0011. Il ordonne les requêtes d'un processus, mais le RPC devra
  exposer une révision serveur avant d'autoriser plusieurs processus ou writers pouvant recevoir des
  versions différentes d'une même fiche.
- Les tris métier, dates d'événement, filtres prix/date et plafond sponsorisé ne sont pas inventés
  dans cet incrément ; ils exigent un contrat catalogue versionné et des décisions produit séparées.

**À revoir si**

- les mesures terrain justifient un TTL, un cache par page ou une capacité supérieure ;
- plusieurs processus ou writers concurrents consomment le même cache canonique ;
- le contrat de tri ou de sponsoring serveur change ;
- le smoke test Room sur simulateur iOS révèle une divergence de migration ou de driver.
