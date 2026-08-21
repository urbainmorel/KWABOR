# Gate du pilote bêta fermée

Ce gate transforme des preuves réelles pseudonymisées en un reçu agrégé `go` ou `no-go`. Il est local, sans dépendance fournisseur, ne contacte aucun service et échoue fermé. Le validateur Python est l’autorité ; le schéma JSON décrit le contrat d’entrée.

## Données autorisées

- Utiliser uniquement les pseudonymes `T-A01…T-A10`, `T-I01…T-I05`, `D-A01…D-A10` et `D-I01…D-I05`.
- Conserver la table de correspondance des testeurs hors du dépôt, chiffrée et à accès restreint.
- Ne jamais mettre dans le JSON un nom, courriel, téléphone, adresse, IP, GPS, UDID, IMEI, numéro de série, jeton, secret, texte ou commentaire libre.
- Chaque preuve contient uniquement un URN immuable `urn:kwabor:evidence:ev-` suivi exactement des 32 premiers caractères hexadécimaux minuscules de son SHA-256 déclaré. Chaque URN et chaque SHA-256 sont uniques dans le manifeste ; aucun identifiant ne peut pointer vers deux digests. Les URI HTTP(S), `artifact`, noms d’hôtes, identifiants de connexion, requêtes, fragments et percent-encoding sont interdits. Le suffixe hex conforme est traité comme un identifiant opaque, jamais comme un téléphone ; toute URI non conforme reste soumise au scan PII/secrets.
- Les clés JSON dupliquées sont interdites à toute profondeur : leur ordre ne doit jamais pouvoir changer un consentement, le marqueur fictif ou une décision.
- Les fichiers de preuves réelles et les reçus associés restent dans l’espace protégé du pilote ; seuls les exemples fictifs restent dans le dépôt.

## Collecte d’un run

1. Figer un seul SHA, nom de version, build et identifiant RC, puis lier les preuves CI et de distribution Android/iOS.
2. Enregistrer 10 testeurs Android et 5 iOS, chacun sur un appareil physique unique et avec consentement analytics et diagnostics.
3. Exécuter le canary avec exactement 3 testeurs pendant au moins 2 heures. Il doit finir au plus tard au début de J1.
4. Déclarer le marqueur de reset, un `RUN-*` actif et zéro session reportée. Les runs antérieurs sont ordonnés du plus ancien au plus récent avec les générations `0…N-1`. Après chaque P0/P1, son successeur immédiat — y compris le run actif après la dernière entrée — doit utiliser à la fois un nouveau RC et un nouveau SHA, puis recommencer à J1. RC, SHA et paire RC/SHA restent en outre uniques sur toute la chaîne : une valeur ancienne ne peut jamais être recyclée.
5. Collecter J1 à J7 : sept fenêtres UTC consécutives de 24 heures, les 15 testeurs, le même RC et au moins 200 sessions consenties.
6. Joindre les preuves du corpus exact de 60 fiches et 180 médias, de la suppression de compte, de la révocation du consentement et de l’absence d’événement après révocation.
7. Tester réellement TalkBack sur Android et VoiceOver sur iOS, sur des appareils physiques du cohort, avec labels, annonces, ordre de focus, cibles tactiles et contraste AA.
8. Mesurer sur chaque plateforme 10 lancements cold puis 20 warm, sous le profil 1600/750 kbit/s et 150 ms RTT, avec horloge monotone. Conserver les 30 valeurs, sans suppression d’outlier. Le P75 nearest-rank est la 23e valeur triée et doit être strictement inférieur à 1500 ms.
9. Pour chaque incident, utiliser un instant UTC calendaire réel. Un incident ouvert n’a pas de résolution ; un incident clos en a une, postérieure ou égale à sa détection. Lier ensuite la décision au RC et au run actifs, puis faire signer exactement les rôles contenu, produit, sécurité-vie privée et technique.

Le résultat ne peut être `go` que si le taux crash-free est au moins 99,5 %, qu’aucun incident P0/P1 n’existe dans le run et que tous les autres contrôles sont verts. `no-go`, `go-with-corrections`, une signature absente ou une preuve fictive restent `no-go`.

## Validation

Depuis la racine du dépôt :

```text
python -B tools/validate-closed-beta-pilot-evidence.py CHEMIN_PREUVE.json
python -B tools/validate-closed-beta-pilot-evidence.py CHEMIN_PREUVE.json --receipt CHEMIN_RECU.json
```

Codes de sortie : `0` pour un GO éligible, `1` pour un NO-GO et `2` pour une erreur de lecture/écriture. Le reçu est canonique, déterministe, agrégé et ne recopie aucune URI de preuve. `source_sha256` lie le document canonique ; `source_bytes_sha256` lie exactement les octets lus, espaces et ordre inclus. Une entrée non analysable ou avec clé dupliquée conserve uniquement l’empreinte des octets bruts. Ne jamais écrire le reçu sur le fichier d’entrée.

Pour vérifier l’outil :

```text
python -B -m py_compile tools/validate-closed-beta-pilot-evidence.py tools/test_validate_closed_beta_pilot_evidence.py
python -B -m unittest tools/test_validate_closed_beta_pilot_evidence.py
```

`demo/pilot/v1/pilot-evidence.example.json` est volontairement fictif et incomplet. Son reçu versionné `go-no-go.example.json` doit toujours rester `no-go`.

## Limite de confiance

Le gate vérifie la cohérence du manifeste et les empreintes déclarées ; il ne prouve pas à lui seul l’authenticité d’un artefact externe. Avant production, les quatre signataires ouvrent chaque URI dans l’espace protégé, recalculent son SHA-256 et confirment l’appareil, la distribution et le run réels.
