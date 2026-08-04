# 0024 — Séparer la route interne de fiche du futur lien public

- **Statut** : accepté
- **Date** : 2026-08-03
- **Décideurs** : Architecture, Mobile
- **Complète** : ADR-0015, ADR-0022

## Contexte et problème

Android et iOS disposent maintenant d'une fiche catalogue réelle, pilotée par un identifiant UUID
et présentée globalement depuis Accueil. Une notification, un parcours interne ou un futur lien
universel doivent pouvoir ouvrir cette fiche précisément, y compris après un démarrage à froid et
la restauration de session.

Le dépôt ne contient toutefois aucun domaine public Kwabor validé, aucun fichier d'association
Android/iOS et aucun fallback Store ou serveur. Utiliser un domaine inventé serait invérifiable ;
présenter le schéma applicatif `kwabor` comme un lien public rendrait le partage inutilisable lorsque
l'application n'est pas installée. La route interne et le futur contrat public doivent donc rester
explicitement distincts.

## Options envisagées

- **Attendre le domaine avant toute route de détail** : évite une étape intermédiaire, mais empêche
  les entrées internes et reporte des comportements natifs déjà implémentables et testables.
- **Partager directement `kwabor://listing/<id>`** : fonctionne lorsque l'application est installée,
  mais n'offre ni fallback ni preuve d'association et ne constitue pas un lien public de production.
- **Livrer une route interne stricte et différer son équivalent HTTPS** : établit maintenant le
  contrat d'identifiant et le routage natif sans prétendre résoudre le partage public.

## Décision

Nous retenons une route **interne uniquement** au format canonique minuscule
`kwabor://listing/<uuid>`. Le générateur et toute URI confiée au résolveur système utilisent cette
casse, car le manifeste Android ne garantit pas la résolution d'un schéma ou hôte en majuscules. Une
fois l'URL remise à l'application, le parseur pur partagé tolère défensivement la casse ASCII,
accepte un UUID canonique RFC 4122 de version 1 à 5, normalise ses hexadécimaux en minuscules et
rejette tout schéma ou hôte différent, chemin supplémentaire, query, fragment, userinfo, port,
espace parasite ou payload surdimensionné.

Après restauration de session, Android et iOS sélectionnent Accueil, ouvrent la fiche globale avec
l'identifiant validé, puis consomment la demande exactement une fois. Une fiche reste publique : une
session authentifiée ou un accès invité déjà explicitement établi l'ouvre sans déclencher le mur
souple E4. La route ne contourne toutefois ni l'intro, ni la restauration de session, ni le choix E3.
Sans compte ni accès invité établi, la demande reste en attente jusqu'à la connexion, l'inscription
ou au choix explicite « Ne pas s'inscrire ». Une connexion ou inscription ouvre ensuite la fiche avec
les droits et la devise d'affichage du compte ; le choix invité l'ouvre en lecture seule et en XOF.

Une seule destination interne peut être en attente. Le dernier lien valide différent remplace la
destination précédente ; un doublon identique reçu avant consommation est coalescé et une URL
invalide ne modifie jamais l'état. Le même lien reçu après consommation constitue une nouvelle
livraison. L'acquittement est conditionné à la destination encore courante ; Android lui associe en
plus un identifiant de livraison afin qu'un effet obsolète ne puisse pas effacer un remplacement
plus récent. Android conserve cet état restaurable pendant une recréation d'Activity ; les remises à
zéro sensibles le suppriment et refusent une nouvelle livraison tant que leur état fail-closed n'est
pas stabilisé.

Cette URL personnalisée ne doit apparaître ni dans un panneau de partage, ni dans « Copier le lien »,
ni dans un contenu envoyé à un tiers. Le futur lien public utilisera un domaine HTTPS officiel et une
route typée équivalente, avec App Links Android, Universal Links iOS, fichiers d'association et
fallback validés. Cette évolution réutilisera l'identifiant et le routage natif sans relâcher leur
validation.

## Conséquences

**Positives**

- Une entrée interne peut ouvrir la même fiche sur Android et iOS, à froid comme à chaud.
- Le contrat UUID et les rejets sont communs aux deux plateformes.
- Les courses entre livraison, navigation et acquittement ont un résultat déterministe et testable.
- Le partage public ne dépend d'aucun domaine fictif ni d'un schéma interceptable par une autre
  application.
- Le futur lien HTTPS pourra réutiliser la destination native existante.

**Négatives / compromis assumés**

- Ce sous-lot ne livre pas encore « Copier le lien » ni le partage public demandé par ACTIONS-001C.
- Le lien interne n'a aucun fallback lorsque Kwabor n'est pas installé.
- La validation App Links/Universal Links reste dépendante d'un domaine et de fichiers d'association
  fournis par le propriétaire.

## À revoir si

- un domaine public Kwabor et ses environnements sont validés ;
- le format public de la fiche ou son fallback Store/serveur est décidé ;
- l'identifiant public cesse d'être l'UUID catalogue ou doit être remplacé par un identifiant opaque.
