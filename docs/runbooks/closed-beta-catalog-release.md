# Runbook : catalogue de la bêta fermée

> Procédure de qualification, publication staging et rollback du corpus de 60 fiches.

## En un coup d'œil

| Élément | Valeur |
| --- | --- |
| Statut | Tooling prêt — exécution interdite avant CI exacte et provisioning du staging protégé |
| Environnement autorisé | Staging uniquement |
| Corpus | 60 fiches et 180 JPEG |
| Autorité | Manifeste versionné `demo/catalog/v1/manifest.json` |
| Déploiement | `Closed-beta demo Storage`, puis `Closed beta demo catalog database` |
| Rollback | archivage des 60 UUID, puis retrait séparé des 180 chemins exacts si confirmé |

> [!WARNING]
> Les scripts sont versionnés mais le GitHub Environment `staging` n'est pas encore provisionné.
> Ne jamais contourner ses gardes, lancer un reset local ou persistant, ni utiliser `--include-seed`
> vers la production.

## Prérequis

- branche candidate propre et revue ;
- manifeste, 180 JPEG et checksums versionnés ;
- GitHub Environment `staging` avec reviewer obligatoire et sans bypass administrateur ;
- variables `KWABOR_SUPABASE_URL`, `KWABOR_SUPABASE_PROJECT_REF`,
  `KWABOR_PRODUCTION_SUPABASE_PROJECT_REF`, `KWABOR_STAGING_PROJECT_REF_SHA256` et
  `KWABOR_DEMO_MEDIA_BASE_URL` ;
- secrets `KWABOR_SUPABASE_SERVICE_ROLE_KEY` et `KWABOR_STAGING_DATABASE_URL`, présents uniquement
  dans ce GitHub Environment ;
- sauvegarde/restauration staging qualifiées ;
- opérateur autorisé à interrompre et restaurer le lot.

## Séquence de qualification prévue

1. Vérifier le manifeste et chaque JPEG localement avec le script versionné.
2. Ouvrir une PR non-draft seulement lorsque build, lint, tests et documentation sont verts.
3. Laisser la CI exécuter Supabase, pgTAP et les validations média sans Docker local.
4. Depuis `main`, déclencher `Closed-beta demo Storage` avec son SHA exact et `publish`.
5. Rejouer ce workflow avec `verify` pour contrôler les 180 objets immuables.
6. Déclencher `Closed beta demo catalog database` sur le même SHA avec `publish`.
7. Rejouer ce workflow avec `verify` et contrôler 60 publiées/180 médias.
8. Exécuter les smoke tests RPC et les parcours Android/iOS signés.
9. Archiver manifeste, checksums, rapport SQL et planches-contact comme preuves de la release.

## Contrôles après publication

- 15 lieux, 15 événements, 15 hôtels et 15 restaurants visibles ;
- 20 fiches pour chacune des trois villes ;
- détail de chaque famille lisible et trois médias ordonnés ;
- aucun CTA de contact réservé `.test` visible ;
- aucune action Storage d'écriture autorisée depuis anon/authenticated ;
- cache hors ligne et redémarrage de processus fonctionnels sur Android/iOS ;
- bannière `Données fictives — bêta fermée` visible sur toutes les surfaces concernées.

## Conditions d'arrêt

Arrêter l'ouverture ou la distribution si :

- un P0/P1 est ouvert ;
- un hash, un objet ou un compte de fiches diverge du manifeste ;
- une fiche ou une image n'a pas passé la revue éditoriale ;
- un secret apparaît dans un log ou un artefact ;
- staging ne peut pas être restauré ;
- un CTA fictif, placeholder ou chemin de navigation masqué reste atteignable.

## Rollback prévu

1. Fermer la distribution interne et conserver les preuves de l'incident.
2. Archiver transactionnellement les 60 UUID exacts du manifeste sans supprimer parents, enfants,
   médias ni relations utilisateur.
3. Vérifier que les quatre fixtures canoniques et les données hors manifeste ne sont pas touchées.
4. Conserver les objets content-hashés pour une restauration rapide tant que la décision de retrait
   Storage n'est pas signée.
5. Après acceptation explicite du retrait Storage, supprimer uniquement les 180 chemins exacts listés ; ne jamais
   calculer ou supprimer récursivement un préfixe de bucket.
6. Restaurer la sauvegarde staging si l'atomicité du rollback n'est pas prouvée.
7. Créer une nouvelle version de manifeste et de chemins pour toute correction média ; ne jamais
   écraser v1.

## Escalade et preuves

L'opérateur produit décide de l'ouverture et de la reprise. Toute divergence sécurité, données,
droits média ou suppression de compte bloque la cohorte et est traitée comme un no-go. La réussite
de sept jours autorise uniquement une nouvelle décision propriétaire ; elle ne déclenche jamais une
publication publique automatique.

Documents liés : [contrat du catalogue](../closed-beta-catalog.md),
[déploiement](../deployment.md) et [ADR-0036](../adr/0036-closed-beta-catalog-delivery-profile.md).
