# 0012 — IA serveur multi-provider ancrée au catalogue

- **Statut** : accepté
- **Date** : 2026-07-14
- **Décideurs** : Produit Kwabor, Architecture, Data, Sécurité
- **Remplace** : —

## Contexte et problème

La V1 requiert un assistant de découverte, « Surprenez-moi », la modération automatique et la traduction à l'affichage. Les clés fournisseurs, quotas et règles de sélection ne peuvent pas vivre dans les clients. Les recommandations doivent rester ancrées aux fiches publiées et ne jamais inventer une disponibilité, un prix ou une réservation.

Un fournisseur unique créerait un point de panne. Un routage client exposerait les clés et empêcherait un contrôle central des budgets, de la modération et des journaux.

## Options envisagées

- **Orchestrateur Supabase Edge Function multi-provider** : routage et validation côté serveur, contrats de sortie communs.
- **OpenRouter seul** : intégration simple, mais dépendance unique à un intermédiaire pour tous les appels.
- **Appels directs depuis les clients** : latence parfois moindre, mais clés exposées, quotas contournables et contrôle serveur insuffisant.

## Décision

Nous retenons un orchestrateur serveur `ai-discover` dans Supabase Edge Functions avec l'ordre verrouillé suivant :

1. OpenAI direct avec le snapshot explicitement demandé `gpt-5-nano-2025-08-07` ;
2. Gemini direct avec `gemini-2.5-flash-lite` ;
3. OpenRouter comme dernier repli, avec collecte interdite quand le provider le permet.

Les modèles restent configurables côté serveur afin de permettre une désactivation ou une migration contrôlée sans release mobile. Le snapshot OpenAI retenu est une baseline produit explicite, pas une promesse qu'il restera le modèle recommandé ; toute évolution passe par mesure qualité/coût et décision tracée.

L'architecture impose aussi :

- aucune clé ou configuration privée dans Android/iOS ;
- un contrat provider interne et une réponse structurée commune ;
- une recherche hybride texte + pgvector sur les seules fiches publiées ;
- des embeddings canoniques `text-embedding-3-small` générés côté serveur ;
- 3 à 5 identifiants candidats, puis revalidation serveur de chaque fiche avant réponse ;
- un résultat vide explicite plutôt qu'une recommandation inventée ;
- des quotas initiaux de 5 requêtes/jour invité et 20/jour connecté ;
- un plafond global de 100 USD/mois, avec alertes à 70 %, 90 % et coupure à 100 % ;
- des logs sans texte utilisateur brut ni PII ;
- « Surprenez-moi » comme requête serveur pondérée, sans génération libre ;
- la modération texte/image via `omni-moderation-latest` et un comportement fail-closed : indisponibilité signifie quarantaine, jamais auto-approbation ;
- la traduction mise en cache côté serveur, avec conservation du texte source.

Références officielles : [GPT-5 nano](https://developers.openai.com/api/docs/models/gpt-5-nano), [text-embedding-3-small](https://developers.openai.com/api/docs/models/text-embedding-3-small), [omni-moderation-latest](https://developers.openai.com/api/docs/models/omni-moderation-latest), [routage OpenRouter](https://openrouter.ai/docs/guides/routing/provider-selection).

## Conséquences

**Positives**

- Secrets, quotas, budgets et changements de modèle centralisés.
- Recommandations limitées aux données réellement publiées.
- Continuité de service contrôlée entre trois routes.
- Modération indépendante du cycle de release mobile.

**Négatives / compromis assumés**

- Trois adaptateurs provider à tester et observer.
- Les réponses de fournisseurs différents doivent être normalisées et évaluées.
- L'Edge Function reste soumise à des limites de durée ; les travaux lourds doivent être asynchrones.

**À revoir si**

- un provider modifie ou retire le modèle retenu ;
- le budget, la latence ou la qualité réelle invalident l'ordre de routage ;
- les limites Edge Functions imposent un worker asynchrone dédié.
