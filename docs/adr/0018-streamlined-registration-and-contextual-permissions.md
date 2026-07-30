# 0018 — Simplifier l'inscription et différer les permissions au contexte

- **Statut** : accepté
- **Date** : 2026-07-30
- **Décideurs** : Équipe
- **Complète** : ADR-0016
- **Remplace** : le séquencement d'inscription AUTH-003, sans remplacer ses garanties serveur

## Contexte

Le tunnel AUTH-003 demandait successivement identité, ville/GPS, devise, documents légaux,
consentements d'observabilité et priming notifications. Il satisfaisait les exigences serveur mais
créait une friction disproportionnée avant la découverte du produit. Il pouvait aussi demander des
permissions sans lien immédiat avec l'intention ayant déclenché l'authentification.

Le contrat Supabase impose toujours une identité, une ville, une devise et les trois révisions
légales. La RPC `complete_user_onboarding`, son DTO, les migrations et les politiques RLS ne doivent
pas changer.

## Décision

Le parcours email est borné à quatre écrans : email, OTP, mot de passe unique et profil final
compact. Google et Apple ouvrent directement ce profil final. L'état `Completed` reste interne et
ferme immédiatement le tunnel.

Le profil final regroupe prénom, nom, ville recherchable, devise XOF par défaut et les trois
acceptations légales séparées. Villes et documents sont préchargés dès l'ouverture. Leur résultat
est fusionné dans l'état courant sans écraser une saisie plus récente. Une erreur expose un retry
ciblé. Une ville n'est suggérée que par un `suggestedCityId` valide issu d'un lieu ayant ouvert la
softwall.

L'intro vidéo affiche les CTA d'accès dès la première frame. La vidéo ne bloque jamais
l'interaction ; fin, échec, mouvement réduit ou « Passer » conservent la même surface sur le
fallback statique. L'ADR-0016 continue de régir le média distant et son consentement.

La softwall est contextuelle et conserve l'action protégée dans la couche plateforme. Après une
authentification réussie, l'action est rejouée une seule fois puis effacée. Une annulation explicite
ou « Plus tard » l'efface ; une erreur récupérable la conserve.

La géolocalisation, le priming notifications et la collecte de nouveaux consentements
d'observabilité sont retirés de l'inscription. Les consentements existants sont préservés. Les
nouveaux comptes restent à `false` jusqu'à leur future gestion dans Réglages ou à un besoin
réellement contextuel.

Les événements de méthode, OTP, résultat du profil et reprise d'action ne contiennent aucune PII et
passent exclusivement par les adaptateurs natifs qui refusent l'envoi sans consentement Analytics
déjà enregistré.

## Invariants

- Aucun OTP ou mot de passe n'est conservé dans l'état, les logs ou `toString`.
- Une soumission OTP concurrente est ignorée pendant l'opération en vol.
- `CompleteOnboardingRequest`, les trois identifiants légaux, la devise, la RPC et les RLS restent
  inchangés.
- Un compte incomplet reprend au mot de passe ou au profil selon sa session ; un compte complet va
  directement à l'accueil.
- Android Compose et iOS SwiftUI utilisent leurs composants natifs et les tokens Kwabor, avec
  contraste AA et cibles tactiles accessibles.

## Conséquences

**Positives**

- Quatre écrans maximum par email et un seul après un fournisseur.
- Aucune permission prématurée ni écran de succès intermédiaire.
- Le profil reste juridiquement et techniquement complet.
- Le contexte du lieu améliore la pertinence sans présélection générique.
- Les saisies survivent aux erreurs et aux réponses réseau tardives.

**Compromis**

- Les réglages de consentement et notifications doivent être livrés dans leur tranche dédiée.
- Le média distant reste indisponible pour un nouveau compte non consenti ; le fallback local de
  l'ADR-0016 est la source normale.
- La reprise contextuelle iOS ne peut être exercée depuis Explore avant la livraison de l'écran
  Explore SwiftUI, mais le contrôleur accepte déjà `suggestedCityId`.

## À revoir si

- Les obligations légales ou la RPC d'onboarding changent.
- Une permission devient indispensable avant l'accueil pour une fonction explicitement choisie.
- La plateforme centralise les actions protégées dans un orchestrateur partagé plutôt que natif.
