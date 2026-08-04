# 0032 — Lire les favoris par propriétaire et faire évoluer leur mutation sans rupture

- **Statut** : accepté
- **Date** : 2026-08-04
- **Décideurs** : Produit Kwabor, Architecture, Data et Sécurité
- **Complète** : ADR-0005, ADR-0007, ADR-0011, ADR-0017, ADR-0018 et ADR-0022
- **Remplace** : —

## Contexte et problème

Le modèle `favorites` est déjà propriétaire et distinct des likes, mais le client ne dispose que de
mutations unitaires historiques. L'écran Profil → Favoris exige une projection de cartes en un appel,
un ordre stable et une pagination compatible avec un réseau lent. Les anciennes RPC relisent aussi
l'interaction publique après chaque retrait : si la fiche a été dépubliée entre-temps, la relation est
supprimée puis cette relecture lève `no_data_found`, ce qui transforme une réussite en erreur client.

Cette tranche précède le miroir Room, l'outbox et les interfaces Android/iOS. Elle doit donc établir
une autorité serveur versionnée sans inventer dès maintenant un protocole offline. Les clients
actuellement distribués consomment encore `add_listing_to_favorites` et
`remove_listing_from_favorites` ; les supprimer dans une migration backend isolée casserait ces
versions.

## Décision

Nous retenons un read model propriétaire paginé et une mutation d'état idempotente, tous deux
`SECURITY INVOKER`, parce que les RLS existantes restent ainsi l'autorité de ligne et qu'aucun
contournement privilégié n'est nécessaire.

### Lecture propriétaire

`public.list_favorite_listing_summaries_v1` :

- exige une session dont l'onboarding est terminé et prend le verrou partagé utilisé par la
  suppression de compte ;
- filtre explicitement `favorites.user_id = auth.uid()` en plus de la RLS propriétaire ;
- retourne seulement les fiches encore `publie` avec leur résumé de carte, couverture officielle,
  ville, état Like du viewer et dates événementielles ;
- trie uniquement par dernier ajout au favori, puis UUID décroissant. Une fiche sponsorisée ne gagne
  aucune place et `is_sponsored_placement` vaut toujours `false` dans ce contexte privé ;
- accepte le filtre optionnel `listing_type`, une limite `1..50` et un curseur opaque lié à la
  version, au propriétaire et au filtre ;
- utilise une pagination keyset `(favorites.created_at, favorites.listing_id)` et retourne au plus
  une ligne sentinelle au-delà de la limite ; la première page emploie une borne interne infinie
  plutôt qu'un prédicat `cursor IS NULL OR ...`, afin que le plan générique garde la borne dans
  l'index ;
- fige `as_of` dans le curseur, refuse les temps non interopérables ou futurs et ne considère pas le
  curseur comme une frontière d'autorisation.

Le classement « dernier favori » est volontairement indépendant de la popularité, de la note, de la
date de publication et du sponsoring.

### Cycle de vie d'une fiche

Une dépublication ou un archivage conserve la relation personnelle mais la masque du read model. La
relation reste retirable par son propriétaire sans que la fiche soit lisible ; si la fiche est
republiée, elle réapparaît à sa position d'origine. Une suppression physique de fiche conserve le
`ON DELETE CASCADE` existant et retire donc la relation devenue orpheline.

Un événement publié dont la fin — ou, à défaut, le début — est passée reste visible avec
`is_event_ended = true`. L'état « Terminé » n'est pas une dépublication automatique dans ce contrat.

### Mutation et compatibilité

`public.set_listing_favorite_v1(listing_id, favorited)` exprime le dernier état souhaité :

- `true` conserve directement une relation déjà présente, même si une modération ultérieure masque
  la fiche ; une création exige une fiche publiée et applique un `INSERT ... ON CONFLICT DO NOTHING`.
  Toute répétition conserve ainsi le `favorited_at` initial ;
- `false` supprime la relation propriétaire sans joindre `listings` et retourne toujours un état
  absent, y compris en retry, après dépublication ou après cascade de la fiche ;
- chaque mutation prend le verrou exclusif du compte avant de revérifier l'onboarding et le tombstone,
  dans le même espace que la suppression de compte.

Les RPC historiques restent présentes, gardent leurs grants et leurs politiques RLS, et deviennent
des wrappers dépréciés vers cette mutation. Le retrait historique retourne une ligne de compatibilité
même pour une fiche masquée. Dans ce seul cas, son ancien champ agrégé `likes_count` vaut zéro plutôt
que de contourner RLS pour révéler une métrique privée ; l'état Favori, seule autorité consommée par
le retrait, reste exact. Le même résultat neutre est utilisé lorsqu'un ancien client répète un ajout
déjà réussi après que la fiche a été masquée : la relation reste vraie, sans exposer la métrique.

La suppression des anciennes RPC, de leurs grants ou des accès table compatibles est reportée à un
lot KMP atomique : les deux applications doivent d'abord utiliser le contrat V1, puis une migration
backend ultérieure pourra réduire cette surface sans casser une version Store encore active.

Les écritures table historiques restent donc temporairement autorisées, mais leurs politiques RLS
appellent un garde privé `VOLATILE SECURITY DEFINER`, sans écriture et avec `search_path` vide. Il
vérifie d'abord que `auth.uid()` est le propriétaire, verrouille le compte dans le même espace que
la suppression, puis revérifie l'onboarding et l'absence de tombstone avec la visibilité acquise
après l'attente. Une écriture REST déjà démarrée ne peut ainsi recréer une relation après le nettoyage.
Le grant `INSERT` de compatibilité est limité à `user_id` et `listing_id` : `created_at` reste rempli
par son défaut serveur, afin qu'un client ne puisse ni falsifier l'ordre privé ni placer une relation
au-delà de la borne `as_of`.

### Intégrité, concurrence et IA

Un index `(user_id, created_at DESC, listing_id DESC)` sert le scan propriétaire. Une contrainte
borne `favorites.created_at` à `[0001-01-01, 10000-01-01)`, ce qui refuse les infinis et correspond
exactement à la plage de curseur interopérable ; la migration valide l'historique et échoue plutôt
que de normaliser silencieusement une valeur invalide.

La préparation et le nettoyage de suppression de compte continuent de supprimer les favoris. Le
verrou partagé de lecture et le verrou exclusif de mutation garantissent qu'une opération commencée
avant le nettoyage termine d'abord, tandis qu'une opération arrivée après le tombstone échoue.

Le futur Assistant IA peut utiliser uniquement les favoris **actifs**, c'est-à-dire les relations
dont la fiche est encore publiée au moment de la lecture. FAVORITES-001A ne crée aucun journal
d'événements, aucune trace de retrait et aucun signal dérivé. Toute personnalisation reste soumise à
son contrôle produit distinct ; le sponsoring ne consomme jamais ces favoris.

## Hors de cette décision

- Le repository/runtime KMP, Room, l'outbox, les retries offline et la réconciliation de session.
- Les écrans Android Compose et iOS SwiftUI, leurs filtres visuels et leur accessibilité.
- Un journal d'activité Favori, une rétention analytique ou un modèle de personnalisation IA.
- Le déploiement Supabase distant et la suppression des contrats legacy.

## Conséquences

**Positives**

- Une page Favoris nécessite un seul appel et conserve un ordre total indépendant du sponsoring.
- Une fiche modérée peut être retirée sans erreur ni élévation de privilèges.
- Les retries de mutation ont une réponse déterministe et ne déplacent pas un favori existant.
- La suppression de compte et les opérations Favori partagent une frontière de concurrence testable.
- Les versions mobiles actuelles continuent de fonctionner pendant la migration progressive.

**Négatives / compromis assumés**

- Une relation masquée occupe encore une ligne jusqu'au retrait, à la republication, à la suppression
  physique de la fiche ou à la suppression du compte.
- Le curseur est invalide pour un autre compte, un autre filtre ou une future version du contrat.
- Les anciennes RPC et les ACL directes maintiennent temporairement une surface plus large que la
  cible finale.
- Le champ `likes_count` du wrapper legacy n'est pas autoritatif pour une fiche devenue invisible.

## À revoir si

- la UI exige un autre ordre, plusieurs filtres combinés ou une recherche dans les favoris ;
- le protocole Room/outbox fixe une clé d'idempotence ou une révision serveur supplémentaire ;
- le cycle de vie des fiches remplace la dépublication par une suppression physique systématique ;
- un usage IA demande autre chose que le snapshot publié actif, ce qui exige une décision de
  confidentialité distincte.

## Références

- [PRD — Favoris, Assistant IA et suppression de compte](../../PRD.md)
- [DESIGN — F3 Favoris et état événement terminé](../../DESIGN.md)
- [ADR-0017 — Suppression de compte](0017-native-federated-auth-promoter-activation-account-deletion.md)
- [ADR-0018 — Pagination catalogue par curseur](0018-cursor-paginated-catalog-summary-rpc.md)
- [ADR-0022 — Read model catalogue détaillé](0022-versioned-catalog-detail-read-model.md)
