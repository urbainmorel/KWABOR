# ADR-0039 — Auth de staging et builds internes sous la responsabilité du mainteneur unique

**Statut** : accepté le 23 novembre 2026.

## Contexte

L’ADR-0038 permet au mainteneur du dépôt canonique de tester le catalogue fictif sur staging.
Le test d’inscription et des favoris sur ses appareils poursuit ce jalon avec un compte
synthétique. Les workflows Auth et de builds internes exigeaient un relecteur distinct alors
que les Environments ont déjà la politique de branche du mainteneur unique.

## Décision

Les workflows Auth de staging, AAB Play Internal et archive iOS/TestFlight interne utilisent
le même contrôle d’identité que le catalogue. Ils exigent le dépôt `urbainmorel/KWABOR`, la
branche protégée `main`, l’identifiant GitHub du mainteneur `146450999` et une exécution ou
relance initiée par `urbainmorel`. Les Environments `staging`, `play-internal` et
`testflight-internal` ont une seule règle de branches protégées et interdisent le bypass
administrateur.

Le mainteneur relit le plan Auth lié au projet avant l’apply et conserve sa confirmation
explicite. Une CI `push/main` réussie du commit ciblé est requise. Les contrôles de
configuration SMTP et OAuth, de signature, de provenance des artefacts et de confirmation
de publication interne restent des conditions d’exécution.

Le profil Auth `email-test` utilise le SMTP réel et des modèles d’e-mail qui portent le
code à six chiffres. Il produit une preuve dédiée au test individuel. Le profil complet
reste la référence pour qualifier Google et Apple dans la bêta.

## Conséquences

Le mainteneur assume seul la revue des opérations Auth et de préparation interne. Le compte
utilisé pour les essais sur le Tecno et l’iPhone est synthétique. L’accueil d’une cohorte,
les données réelles et la production relèvent des décisions et contrôles prévus pour ces
environnements.
