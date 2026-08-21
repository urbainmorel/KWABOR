# Runbook : configuration Auth du staging bêta fermée

Ce runbook borne la configuration Supabase Auth du projet `staging`. Le workflow reste manuel,
protégé et sans Docker. Il ne cible jamais `production` et n'applique aucun reset de données.

## Contrat exact dérivé des clients

| Champ Supabase | Valeur staging attendue | Source |
|---|---|---|
| Site URL | `kwabor://app/home` | Route publique Explorer acceptée par Android et iOS |
| Redirect URLs additionnelles | `kwabor://auth/promoter-activate` uniquement | Parcours Promoteur et manifest Android |
| Inscription globale | ouverte (`disable_signup=false`) | PRD §6.9, inscription email/Google/Apple |
| Email / téléphone / anonyme | activé / désactivé / désactivé | Parcours mobile livré |
| Mot de passe minimal | 8 caractères | Contrat onboarding |
| OTP email | 6 chiffres, expiration 3 600 s | Contrat onboarding et configuration locale |
| Renvoi email | 30 s minimum | Contrat onboarding |
| Confirmation email préalable | requise (`mailer_autoconfirm=false`) | Le parcours email doit réellement franchir l’OTP avant la création de session |
| Changement d'email sécurisé | activé | Configuration locale durcie |
| Google | activé, nonce vérifié | ID token natif Android/iOS |
| Apple | activé, audience `com.kwabor.ios` | `AuthenticationServices` natif iOS |
| CAPTCHA | désactivé | Aucun échange de jeton CAPTCHA n'est livré dans les deux clients |

Le Site URL est un deep link mobile final valide : les deux applications enregistrent le schéma
`kwabor`, l'hôte `app` et la route publique `home`. Les emails d'inscription et de récupération
utilisent `{{ .Token }}` et ne dépendent d'aucun callback web. Aucun wildcard, localhost, URL de
preview ou callback OAuth web n'entre dans l'allow-list Auth.

Le callback Google à enregistrer dans Google Cloud est une configuration fournisseur distincte :
`https://<project-ref-staging>.supabase.co/auth/v1/callback`. Il ne doit pas être ajouté à la liste
des redirects post-authentification de l'application.

Le PATCH Management API concatène les audiences Google dans `external_google_client_id`, client Web
en premier puis client iOS. Le GET les restitue en client principal et
`external_google_additional_client_ids` ; l'outil qualifie cette représentation de lecture exacte.

## Autorité GitHub Environment

Le GitHub Environment protégé `staging` doit contenir les variables suivantes :

- `KWABOR_ENVIRONMENT=staging` ;
- `KWABOR_SUPABASE_URL` ;
- `KWABOR_SUPABASE_PROJECT_REF` ;
- `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF`, distinct du staging ;
- `KWABOR_STAGING_PROJECT_REF_SHA256` ;
- `KWABOR_GOOGLE_WEB_CLIENT_ID` ;
- `KWABOR_GOOGLE_SERVER_CLIENT_ID`, strictement identique au client Web ;
- `KWABOR_GOOGLE_IOS_CLIENT_ID`, distinct du client Web ;
- `KWABOR_GOOGLE_REVERSED_CLIENT_ID`, dérivé exactement du client iOS ;
- `KWABOR_AUTH_SMTP_ADMIN_EMAIL` ;
- `KWABOR_AUTH_SMTP_HOST` ;
- `KWABOR_AUTH_SMTP_PORT`.

Secrets requis pour `plan` et `apply` :

- `SUPABASE_ACCESS_TOKEN`, PAT ou jeton fin permettant la lecture et l'écriture de la configuration
  Auth du projet exact ;
- `KWABOR_GOOGLE_WEB_CLIENT_SECRET` ;
- `KWABOR_AUTH_SMTP_USER` ;
- `KWABOR_AUTH_SMTP_PASSWORD`.

Le flux Apple est exclusivement natif. Il exige l'App ID, l'entitlement et le profil signé réels,
mais aucun Services ID ni secret Apple `.p8` dans cette configuration Supabase. Ajouter un flux web
Apple nécessiterait une décision et une rotation de secret séparées.

## Exécution

Lancer `.github/workflows/closed-beta-staging-auth.yml` depuis la branche `main` sélectionnée avec :

1. `operation=plan`, le SHA complet exact et l'identifiant du run CI `push/main` vert de ce SHA ;
2. relire le GEL, ses champs de dérive, puis relever l'identifiant du run, l'identifiant de
   l'artefact `kwabor-gel-g5-staging-auth-plan-*` et son SHA-256 brut ;
3. `operation=apply` sur le même exact-head avec la phrase
   `APPLY-EXACT-STAGING-AUTH`, `validated_plan_run_id`, `validated_plan_artifact_id` et
   `validated_plan_artifact_digest`, après l'approbation du GitHub Environment ;
4. `operation=verify` indépendamment après application.

`plan` effectue uniquement un `GET` Management API. `apply` envoie un unique `PATCH`, puis relit
la configuration. L'apply refuse un plan d'un autre SHA, CI, projet, configuration publique ou jeu
exact de credentials write-only. Il relit aussi Auth avant le PATCH et refuse toute dérive depuis le
snapshot du plan : une modification intermédiaire impose un nouveau plan et une nouvelle
approbation. Une erreur après le début de la mutation produit
`DO_NOT_RETRY_VERIFY_FIRST` : exécuter `verify`, ne pas relancer aveuglément. `verify` exige les
valeurs publiques attendues et la présence des credentials write-only ; il ne peut pas prouver la
justesse d'un secret que l'API ne retourne pas.

Le GEL ne contient ni project ref brut, ni PAT, ni secret Google, ni identité/credential SMTP, ni
adresse d'expéditeur SMTP en clair. Il conserve le digest du project ref, la provenance CI, le
fingerprint de la configuration publique, les hashes des templates et des champs SMTP sensibles,
les champs en dérive et des booléens de présence des credentials.

## Gates externes qui restent obligatoires

Une configuration `verify` verte ne suffit pas à distribuer la bêta. Il faut encore :

- prouver la réception des deux OTP via le SMTP staging sur une adresse synthétique ;
- tester Google sur Android et iOS signés avec les clients/certificats du même tier ;
- tester Apple sur un appareil iOS signé avec l'entitlement réellement présent dans l'archive ;
- vérifier `session_id`, l'AMR finale `password`/`oauth` et la ré-authentification de suppression ;
- vérifier l'activation Promoteur avec un token synthétique sans le journaliser ;
- conserver CAPTCHA désactivé tant qu'aucun échange de token CAPTCHA n'est implémenté dans les deux
  clients ; son activation isolée casserait les parcours plutôt que de les sécuriser.

Les secrets write-only ne sont pas récupérables pour un rollback automatique. Avant toute rotation
ultérieure, le propriétaire conserve l'ancienne configuration dans son coffre et prépare une
opération explicitement revue ; le workflow ne tente jamais de reconstruire un secret depuis le GEL.
