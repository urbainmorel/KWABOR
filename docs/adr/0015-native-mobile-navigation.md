# 0015 — Utiliser une navigation mobile native avec routes racines typées

- **Statut** : accepté
- **Date** : 2026-07-14
- **Décideurs** : Équipe
- **Remplace** : [ADR-0008](0008-navigation-shell.md)

## Contexte et problème

Le shell Compose partagé de l'ADR-0008 ne respecte plus la frontière retenue entre l'interface Android Compose et l'interface iOS SwiftUI. Le DESIGN impose cinq destinations racines de même niveau : Accueil, Social, Ajouter, Notifications et Profil. Les liens entrants doivent sélectionner une destination connue sans accepter arbitrairement une chaîne comme route.

Le domaine public à utiliser pour des liens universels iOS et des App Links Android n'est pas encore détenu ni configuré dans le dépôt. L'inventer rendrait les entitlements et les fichiers d'association non vérifiables.

## Options envisagées

- **Conserver un routeur Compose partagé** : incompatible avec l'interface SwiftUI native exigée sur iOS.
- **Utiliser uniquement des chaînes propres à chaque plateforme** : simple, mais duplique le contrat et accepte facilement des routes inconnues.
- **Partager le vocabulaire racine et parser strictement, naviguer nativement** : conserve un contrat commun pur tout en laissant chaque hôte posséder sa pile de navigation.

## Décision

Le module `shared` expose les cinq destinations racines et un parseur pur du format `kwabor://app/<destination>`. Il n'importe aucun framework de navigation. Le parseur rejette un schéma ou un hôte différent, une destination inconnue, un chemin imbriqué, une query, un fragment ou des espaces parasites.

Android utilise Navigation Compose et des objets de route sérialisables. La bottom navigation restaure l'état de chaque racine et passe par le même mur souple d'authentification pour les destinations protégées.

iOS utilise une `TabView` SwiftUI avec un `NavigationStack` propre à chaque racine. Le bridge partagé transforme un lien valide en clé de destination, puis SwiftUI sélectionne l'onglet natif correspondant.

Le schéma `kwabor` est enregistré sur les deux plateformes. Le host `app` est réservé à la navigation racine ; le host `auth` déjà prévu pour les callbacks d'authentification reste distinct. Les liens universels/App Links seront ajoutés seulement après validation d'un domaine Kwabor, des fichiers d'association et des entitlements.

Les routes de détail ne sont ajoutées que par les features qui implémentent réellement leur écran et leur contrat d'identifiant. La navigation ne crée pas de destination de détail factice.

## Conséquences

**Positives**

- Les interfaces utilisent les primitives de navigation recommandées par leur plateforme.
- Le vocabulaire racine, la validation et les deep links restent cohérents entre Android et iOS.
- Une URL malformée ou inconnue ne peut pas provoquer une navigation arbitraire.
- Les futures destinations imbriquées restent propriétaires de leur feature.

**Négatives / compromis assumés**

- Les implémentations des piles de navigation restent spécifiques à chaque plateforme.
- Les liens HTTPS vérifiés sont différés jusqu'à la disponibilité d'un domaine et de sa configuration serveur.
- Le mur souple iOS sera relié à l'état d'authentification lors de la livraison de l'interface Auth SwiftUI.

**À revoir si**

- Le domaine public Kwabor et les exigences d'association Android/iOS sont validés.
- Une destination racine change dans le DESIGN.
