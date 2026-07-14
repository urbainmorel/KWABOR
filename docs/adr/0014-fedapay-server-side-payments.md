# 0014 — Paiements de promotion FedaPay validés côté serveur

- **Statut** : accepté
- **Date** : 2026-07-14
- **Décideurs** : Produit Kwabor, Architecture, Data, Sécurité, Finance
- **Remplace** : —

## Contexte et problème

Les campagnes sponsorisées sont payées en XOF par Mobile Money. Le client ne doit jamais pouvoir confirmer un paiement, créditer un budget ou activer une campagne. Le système doit résister aux doubles soumissions, webhooks dupliqués, replays et retards opérateur.

FedaPay prend actuellement en charge MTN Bénin et MOOV Bénin, les deux opérateurs requis pour le lancement. Les secrets sandbox/live et les signatures webhook doivent rester côté serveur.

## Options envisagées

- **FedaPay primaire via Edge Functions** : couverture MTN/Moov Bénin et webhooks signés.
- **CinetPay primaire** : alternative du PRD, conservée comme option future après évaluation séparée.
- **Validation client** : rejetée, car falsifiable et incompatible avec un ledger fiable.

## Décision

Nous retenons FedaPay comme agrégateur primaire de la V1. Deux Edge Functions bornent l'intégration :

- `payment-create-fedapay` crée ou reprend une transaction à partir d'un devis serveur immuable ;
- `payment-webhook-fedapay` reçoit le payload brut, vérifie `X-FEDAPAY-SIGNATURE`, contrôle le timestamp, déduplique l'événement et journalise la transition.

Le modèle de paiement impose :

- montant saisi, stocké et réglé en XOF ;
- devis serveur, ledger append-only, événements webhook et reçus séparés ;
- clé d'idempotence par tentative logique de paiement ;
- secret distinct pour sandbox et live, stocké dans les secrets Supabase ;
- aucune clé FedaPay ni capacité d'activation dans le client ;
- statut campagne modifié uniquement après événement `transaction.approved` authentifié et rapprochement du montant, de la devise et de la référence ;
- événements inconnus ou incohérents mis en revue, jamais transformés en succès ;
- réponse 2xx rapide après persistance/déduplication, traitement lourd asynchrone ;
- reprise et rapprochement périodique des transactions restées en cours ;
- tests sandbox, replay/duplication/signature invalide, puis une transaction live minimale MTN et Moov avant lancement.

FedaPay documente MTN Bénin et MOOV Bénin ainsi que leurs tarifs : [moyens de paiement](https://docs.fedapay.com/payment-methods/fr/payment-methods-fr), [tarifs](https://www.fedapay.com/pricing). Sa documentation webhook exige HTTPS/TLS, signature `X-FEDAPAY-SIGNATURE`, déduplication et protection contre le replay : [Webhooks et événements](https://docs.fedapay.com/integration-api/fr/webhooks-fr).

## Conséquences

**Positives**

- Le client ne peut ni forger un succès ni dépasser le ledger serveur.
- MTN et Moov utilisent une même intégration et un même modèle de reprise.
- Les doublons et replays deviennent des événements idempotents auditables.

**Négatives / compromis assumés**

- KYC FedaPay et validation live nécessitent le propriétaire.
- Les délais Mobile Money imposent des états `en_cours`, `reussi` et `echoue` explicites.
- Une panne agrégateur empêche l'activation immédiate et doit être communiquée sans message technique brut.

**À revoir si**

- la couverture, les tarifs ou la disponibilité FedaPay au Bénin changent ;
- les tests live révèlent une fiabilité insuffisante ;
- Finance valide un second agrégateur avec un besoin réel de bascule.
