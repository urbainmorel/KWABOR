# ADR-0041 — Informations du profil facultatives après l’inscription par e-mail

- **Statut** adopté
- **Date** 2026-11-23
- **Décideur** mainteneur
- **Met à jour** ADR-0026 et ADR-0040.

La dernière étape e-mail présente les quatre devises d’affichage et une seule case pour les CGU et la politique de confidentialité. Chaque intitulé ouvre sa révision. Supabase valide les deux révisions et enregistre deux preuves d’acceptation dans la même transaction que la finalisation du compte.

Le prénom, le nom et la ville sont facultatifs à l’inscription par e-mail. Supabase stocke les champs absents à NULL. Le formulaire Google et Apple demande ces informations. Les paramètres du profil les rendent modifiables après inscription.

La RPC de modification cible le propriétaire authentifié d’un profil terminé et protège la mise à jour contre un changement de compte ou une suppression en cours. Elle valide la longueur des noms et la ville au Bénin. Les appels d’inscription des versions précédentes gardent leur signature.
