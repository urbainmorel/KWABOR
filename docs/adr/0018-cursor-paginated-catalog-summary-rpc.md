# 0018 — Résumé catalogue public paginé par curseur et RPC

- **Statut** : accepté
- **Date** : 2026-07-31
- **Décideurs** : Équipe
- **Remplace** : —

## Contexte et problème

Le chargement Explore interrogeait d'abord `listings`, puis `listing_media` une fois par fiche pour
obtenir sa couverture. Une page de vingt fiches déclenchait ainsi vingt-et-une requêtes réseau et
téléchargeait des champs de détail inutiles aux cartes. La pagination par offset ne définissait pas
non plus de dernier critère de tri unique et annonçait à tort une page suivante lorsqu'une page
terminale contenait exactement la limite demandée.

Android et iOS doivent consommer le même contrat public, sans exposer Supabase dans le domaine ou
reproduire la sélection de couverture, le tri et la logique de curseur dans chaque client. Les règles
RLS doivent continuer à s'appliquer, y compris lorsqu'un utilisateur authentifié peut lire ses
propres brouillons par une autre politique.

## Options envisagées

- **Requête PostgREST puis lecture média par fiche** : conserve le code existant, mais maintient le
  N+1, les charges inutiles et une pagination instable.
- **Vue de résumé et pagination client par offset** : supprime le N+1, mais disperse la logique de
  tri et de continuation dans les clients et ne corrige pas la dérive des offsets.
- **RPC `security invoker` avec résumé plat et curseur opaque** : centralise les filtres, la
  couverture, le tri et la continuation tout en conservant RLS et des grants explicites.

## Décision

Nous retenons un RPC public `security invoker` qui retourne un DTO de résumé plat et au plus une
ligne sentinelle au-delà de la limite, parce qu'il garantit une requête réseau par page et un contrat
identique pour Android et iOS sans contourner RLS.

Le RPC filtre toujours `status = 'publie'`, même pour une session authentifiée. Les workflows de
gestion des brouillons utiliseront un contrat séparé. La couverture est choisie de façon
déterministe par `is_cover`, `display_order`, puis `id`.

L'ordre de cette première infrastructure catalogue privilégie un sponsoring encore actif à l'instant
figé dans le curseur, puis la note, les likes, la date de publication ou création et enfin l'identifiant
UUID. Le curseur opaque et versionné contient cet instant, l'empreinte canonique des filtres et toutes
les clés de tri. Une réutilisation avec d'autres filtres ou un format inconnu est refusée. L'identifiant
final rend l'ordre total ; une mutation concurrente des notes ou compteurs reste toutefois soumise à
la cohérence éventuelle normale du catalogue.

Le RPC retourne aussi `is_sponsored_placement`, calculé avec ce même instant serveur. La carte utilise
ce booléen comme autorité du badge « Sponsorisé » au lieu de recalculer l'expiration avec l'horloge de
l'appareil, afin qu'un placement payé ne puisse jamais être classé comme tel sans transparence visuelle.

Les types `ListingPageRequest` et `ListingSummaryPage` appartiennent au domaine catalogue. Le
`PageRequest` générique par offset reste inchangé pour les autres modules tant qu'ils n'ont pas leur
propre contrat de curseur. CATALOG-002 ne rend pas encore le bouton ou le geste de chargement suivant
visible : cette consommation UI appartient à EXPLORE-002.

## Conséquences

**Positives**

- Une page catalogue effectue un seul appel RPC et aucune requête média unitaire.
- La dernière page est détectée exactement grâce à la ligne sentinelle.
- Les clients partagent les mêmes filtres, règles de publication et critères de continuation.
- Le classement sponsorisé et son badge reposent sur le même snapshot serveur.
- RLS reste l'autorité, complétée par une restriction publique explicite et des droits d'exécution
  bornés à `anon` et `authenticated`.

**Négatives / compromis assumés**

- Le curseur est lié aux filtres et à sa version ; un changement de contrat invalide les anciens
  curseurs.
- Les notes et compteurs peuvent évoluer entre deux pages. Une stabilité snapshot stricte exigerait
  un rang persisté ou un ordre entièrement immuable.
- Les tris métier distincts Lieux, Événements et Établissements restent à définir et livrer dans
  EXPLORE-002.

**À revoir si**

- Les tris métier exigent un rang matérialisé, un moteur de recherche dédié ou plusieurs stratégies
  de curseur.
- Les mesures sur le corpus de staging montrent qu'un index de classement supplémentaire est
  nécessaire.
- Un workflow public doit exposer autre chose que les fiches publiées.
