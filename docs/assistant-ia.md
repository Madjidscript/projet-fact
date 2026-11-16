# L'assistant IA de FACT Immobilier

L'assistant est un robot présent sur toutes les pages de l'application de gestion. On lui écrit, ou on lui parle, en français. Il répond à partir des vraies données de FACT, explique l'application à partir du guide d'utilisation, ouvre la bonne page pour guider la personne, et prépare des actions qu'elle valide d'un clic.

## Ce qu'il sait faire

| Outil | Ce qu'il fait | Droit exigé | Écrit dans la base ? |
| --- | --- | --- | --- |
| `chercher_batiments` | Bâtiments du périmètre : lots, occupation, impayés, interventions | `patrimoine.lire` | Non |
| `lister_impayes` | Baux en retard : occupant, lot, montant, mois de retard | `location.lire` | Non |
| `lister_interventions` | Interventions ouvertes, les plus pressantes d'abord | `travaux.lire` | Non |
| `point_du_jour` | Impayés, baux qui finissent sous 60 jours, travaux urgents, lots vacants | chaque rubrique selon son droit | Non |
| `consulter_guide` | Répond à « comment faire ? » à partir de `docs/guide-utilisateur.md` | aucun | Non |
| `ouvrir_page` | Ouvre une page de l'application (17 pages, chacune avec son droit) | celui de la page | Non |
| `preparer_relance` | Donne les faits d'un bail en retard ; le modèle rédige la relance. Rien n'est envoyé. | `location.lire` | Non |
| `signaler_intervention` | Crée une intervention, **après le clic « Valider »** | `travaux.ecrire` | Oui, après validation |

Il comprend aussi la page ouverte : sur la fiche d'un bâtiment, « combien de lots sont vacants ici ? » vise ce bâtiment. Et le bouton micro (Chrome, Edge, Safari) transforme la voix en texte, que la personne relit avant d'envoyer.

Ce document explique comment il fonctionne, du clic sur « Envoyer » jusqu'à l'affichage de la réponse.

## L'idée en une phrase

Le modèle d'OpenAI ne sait qu'écrire du texte. Il ne voit jamais la base de données et n'exécute rien. On lui donne une liste d'**outils** (des méthodes Java) ; il **choisit** celui qu'il faut, l'API l'**exécute** avec le compte connecté, et on recommence jusqu'à ce qu'il réponde en texte. C'est cette boucle qui fait d'un chatbot un **agent**.

## Qui fait quoi

| Acteur | Ce qu'il fait | Ce qu'il ne fait jamais |
| --- | --- | --- |
| Angular | Affiche le robot, envoie la question avec le jeton JWT, exécute les consignes de navigation | Parler à OpenAI, connaître la clé |
| L'API (Spring Boot) | Vérifie qui est connecté, garde la mémoire, filtre les outils selon les droits, exécute les outils, lit la base | Décider quel outil utiliser |
| OpenAI | Choisit l'outil à demander, rédige la réponse | Voir la base, exécuter quoi que ce soit |
| PostgreSQL | Rend les données, filtrées par le périmètre du compte | Recevoir quoi que ce soit d'OpenAI |

## Les fichiers

### Backend : `backend/src/main/java/com/eburtis/fact/`

| Fichier | Rôle |
| --- | --- |
| `controller/AssistantController.java` | Les routes : `POST /api/assistant`, `GET` et `DELETE /api/assistant/historique`. |
| `facade/AssistantFacade.java` | Prépare la conversation (prompt système, mémoire, question), lance la boucle, enregistre l'échange. |
| `assistant/BoucleAgent.java` | **La boucle de l'agent.** Le fichier le plus important. |
| `assistant/ClientOpenAi.java` | La seule classe qui appelle OpenAI : une requête HTTP avec la clé. |
| `assistant/PromptSysteme.java` | La « fiche de poste » du modèle : qui il est, à qui il parle, ses règles. |
| `assistant/OutilAssistant.java` | Le moule d'un outil : nom, description, paramètres, droit exigé, action. |
| `assistant/ActionsPage.java` | Les consignes laissées pour Angular (ouvrir une page). |
| `assistant/outils/` | Un fichier par outil (voir le tableau plus haut). |
| `assistant/LimiteurAssistant.java` | 20 questions par minute et par compte. |
| `repository/AssistantRepository.java` | La mémoire, le journal des tours et les validations. Toujours filtré par `id_compte`. |
| `configuration/AssistantProperties.java` | Lit `OPENAI_API_KEY`, `OPENAI_MODEL` et `ASSISTANT_TOKENS_JOUR`. |

Les tables sont créées par deux migrations, dans `resources/flyway/schema/` :

| Migration | Table | Sert à |
| --- | --- | --- |
| `V15__assistant_messages.sql` | `app.assistant_message` | La mémoire de la conversation. |
| `V16__assistant_tours_validations.sql` | `app.assistant_tour` | Un enregistrement par appel au modèle : outils demandés, durée, tokens. Sert au budget et au débogage. |
| | `app.assistant_validation` | Les actions proposées, en attente du clic (10 minutes). |

Le guide `docs/guide-utilisateur.md` est copié dans le jar par `build.gradle`, pour l'outil `consulter_guide`.

### Frontend : `frontend/src/app/`

| Fichier | Rôle |
| --- | --- |
| `composants/assistant.ts` | Le robot, la fenêtre de discussion, l'exécution des consignes de navigation. |
| `noyau/assistant.service.ts` | Les appels à `/api/assistant`. |
| `noyau/mise-en-forme.ts` | Transforme le Markdown du modèle (gras, listes) en HTML, après avoir tout échappé. |
| `mise-en-page/coque.ts` | Place le robot sur toutes les pages. |

## Trajet 1 : « Quels bâtiments ont des impayés ? »

### A. Dans le navigateur

1. **La personne clique sur Envoyer.** `assistant.ts`, méthode `envoyer()`, affiche sa bulle et allume les trois points.
2. **Le service envoie la question, avec l'adresse de la page ouverte.** `assistant.service.ts` :

   ```text
   POST /api/assistant
   { "texte": "Quels bâtiments ont des impayés ?", "page": "/patrimoine" }
   ```

3. **L'intercepteur ajoute le jeton.** `api.interceptor.ts` ajoute `Authorization: Bearer <jeton>`. C'est ce qui dit au serveur qui écrit.

### B. L'entrée dans l'API

4. **Le filtre de sécurité lit le jeton.** `JwtRequestFilter` retrouve le compte : identifiant, profil, droits. Sans jeton valide, la requête s'arrête avec une erreur 401.
5. **Le contrôleur reçoit la question.** `AssistantController.repondre` passe le texte et l'`Utilisateur` à la facade.
6. **La facade prépare la conversation.** `AssistantFacade.repondre` :
   - refuse un message vide ou de plus de 4 000 caractères ;
   - refuse au-delà de 20 questions par minute pour ce compte (erreur 429) ;
   - relit les 20 derniers messages du compte dans `app.assistant_message` (la mémoire) ;
   - construit la liste : prompt système, contexte de la page ouverte, ces 20 messages, puis la question ;
   - confie le tout à la boucle.

### C. Tour 1 : le modèle choisit

7. **La boucle filtre les outils et vérifie le budget.** `BoucleAgent.executer` ne garde que les outils permis au compte : un outil sans le bon droit n'est même pas montré au modèle. Avant chaque appel, elle vérifie que le compte n'a pas dépassé son budget de tokens du jour.
8. **Premier appel à OpenAI.** `ClientOpenAi.appeler` envoie :

   ```text
   { "model": "...",
     "messages": [ { "role": "system", "content": "Tu es l'assistant de FACT..." },
                   ...la mémoire...,
                   { "role": "user", "content": "Quels bâtiments ont des impayés ?" } ],
     "tools": [ { "name": "chercher_batiments", "description": "Cherche les bâtiments... les impayés..." },
                { "name": "ouvrir_page", "description": "Ouvre une page..." } ] }
   ```

9. **OpenAI demande un outil, sans écrire de texte.** Il compare la question avec la description de chaque outil et répond :

   ```text
   { "tool_calls": [ { "id": "call_1", "function": { "name": "chercher_batiments", "arguments": "{}" } } ] }
   ```

   C'est le modèle qui choisit l'outil, pas l'API : aucune règle du code ne lit la question. Il comprend le sens, donc « mes immeubles » ou « mes propriétés » mènent au même outil.

### D. L'API exécute l'outil

10. **La boucle lance l'outil.** `BoucleAgent.lancer` vérifie que l'outil demandé est bien permis, puis appelle `ChercherBatimentsOutil.executer` avec l'`Utilisateur` venu du jeton, jamais un compte choisi par le modèle.
11. **L'outil passe par la facade habituelle.** Il appelle `BatimentFacade.lister(utilisateur)`, la méthode de l'écran « Bâtiments ». `PerimetreService` limite aux bâtiments du compte, puis `BatimentRepository` interroge PostgreSQL.
12. **L'outil résume le résultat** en une ligne par bâtiment (30 au plus) :

    ```text
    12 bâtiment(s) trouvé(s) :
    - id 12 · BAT-012 · Les Palmiers (... Cocody) · lots 8, occupés 6, vacants 2 · impayé 150000 FCFA ...
    ```

### E. Tour 2 : le modèle rédige

13. **Deuxième appel à OpenAI.** La boucle renvoie toute la conversation, plus la demande d'outil du tour 1 et le résultat de l'outil, reliés par `call_1`. Le résultat est précédé d'un encadré qui rappelle que ce sont des données, jamais des consignes. C'est pour cela que le nombre de tokens monte nettement au tour 2. Chaque tour est écrit dans `app.assistant_tour`.
14. **OpenAI répond en texte** : « Deux bâtiments ont des **impayés** : … ». Ce n'est plus une demande d'outil : la boucle s'arrête.

### F. Le retour vers le navigateur

15. **La facade enregistre et répond.** La question et la réponse finale vont dans `app.assistant_message` ; les étapes intermédiaires ne sont pas gardées. Réponse :

    ```text
    { "reponse": "Deux bâtiments ont des **impayés** : ...", "actions": [] }
    ```

16. **Angular affiche.** Le robot passe le texte dans `miseEnForme` (gras, listes) et l'affiche dans sa bulle.

## Trajet 2 : « Ouvre la fiche des Palmiers »

Les étapes 1 à 8 sont les mêmes. La boucle fait cette fois trois tours :

| Tour | Ce que demande OpenAI | Ce que fait l'API |
| --- | --- | --- |
| 1 | `chercher_batiments` avec « Palmiers », car il lui faut l'id | Lit la base : « id 12 · Les Palmiers… » |
| 2 | `ouvrir_page` avec `fiche_batiment` et l'id 12 | `OuvrirPageOutil` vérifie le droit de la page, puis `BatimentFacade.fiche(12)` vérifie que le bâtiment est dans le périmètre. Il note une consigne dans `ActionsPage` : aller sur `/patrimoine/12`. |
| 3 | Du texte : « Voici la fiche des Palmiers. » | Fin de la boucle. |

La réponse porte cette fois une consigne :

```text
{ "reponse": "Voici la fiche des Palmiers.",
  "actions": [ { "type": "naviguer", "chemin": "/patrimoine/12", "libelle": "Fiche : Les Palmiers" } ] }
```

Le serveur ne peut pas cliquer dans le navigateur : c'est Angular, dans la méthode `executer` du robot, qui appelle `router.navigateByUrl("/patrimoine/12")`. Le robot ajoute aussi un lien « Fiche : Les Palmiers » sous sa réponse. Sur téléphone, la fenêtre se referme pour laisser voir la page.

## Trajet 3 : « Signale une fuite d'eau aux Palmiers, c'est urgent »

C'est le seul trajet qui modifie des données. Il se fait en **deux temps**, et le modèle ne participe qu'au premier.

### Temps 1 : la proposition (rien n'est écrit)

| Tour | Ce que demande OpenAI | Ce que fait l'API |
| --- | --- | --- |
| 1 | `chercher_batiments` avec « Palmiers » | Trouve l'id 12. |
| 2 | `signaler_intervention` : bâtiment 12, PLOMBERIE, HAUTE, « Fuite d'eau » | La boucle voit que l'outil **demande une validation**. Elle n'appelle pas `executer` mais `preparer`, qui vérifie tout (catégorie, urgence, titre, périmètre du bâtiment) **sans rien écrire**. Elle enregistre les paramètres exacts et leur empreinte SHA-256 dans `app.assistant_validation`, valable 10 minutes. |
| 3 | Du texte : « Je vous ai préparé le signalement, il vous reste à valider. » | Fin de la boucle. |

La réponse porte une consigne `validation`. Le robot affiche une carte orange avec le résumé exact et les boutons **Valider** et **Annuler**.

### Temps 2 : le clic (sans le modèle)

`POST /api/assistant/validations/{id}/valider` appelle `AssistantFacade.valider`, qui fait cinq vérifications :

1. la demande appartient à ce compte ;
2. elle est encore en attente ;
3. elle n'a pas expiré ;
4. l'empreinte des paramètres n'a pas changé : ce qui part est exactement ce qui a été montré ;
5. le compte a toujours le droit `travaux.ecrire`.

Ensuite seulement, l'outil appelle `InterventionFacade.signaler`, avec toutes ses règles habituelles. L'action est écrite dans le **journal de FACT**, avec la mention « via l'assistant ». Tout se fait dans une seule transaction : un second clic sur « Valider » ne crée rien de plus.

« Annuler » passe la demande à `ANNULEE`, et rien n'est fait.

## La mémoire

Le modèle n'a aucune mémoire : chaque appel est pour lui le premier. C'est l'API qui se souvient, dans `app.assistant_message`, et qui renvoie les 20 derniers messages à chaque question. Conséquences :

- un message plus ancien que les 20 derniers n'est plus envoyé : le modèle ne le connaît plus ;
- tout l'historique est renvoyé et facturé à chaque question : une longue conversation coûte plus cher, même pour une question courte ;
- le bouton **+** du robot efface la conversation du compte.

## Les protections

1. **La clé reste sur le serveur.** Elle est dans le fichier `.env`, ignoré par Git. Angular ne parle jamais à OpenAI.
2. **Le compte vient du jeton JWT, jamais du modèle.** Un outil reçoit toujours l'`Utilisateur` connecté.
3. **Les outils passent par les facades existantes.** Le périmètre et les droits de FACT s'appliquent tout seuls.
4. **Un outil sans le droit n'est pas proposé**, et il est refusé si le modèle le demande quand même.
5. **8 tours au maximum** par question : un modèle qui tourne en rond ne peut pas vider le crédit.
   - **20 questions par minute** et par compte (`LimiteurAssistant`).
   - **Un budget de tokens par compte et par jour** (`ASSISTANT_TOKENS_JOUR`, 100 000 par défaut), vérifié avant chaque appel.
   - **Un délai maximum** de 60 secondes pour la réponse d'OpenAI, et un seul nouvel essai s'il est surchargé (429) ou en panne (5xx). Une clé ou un nom de modèle refusé donne un message clair, sans nouvel essai.
6. **Les pages viennent d'une liste fermée.** Le modèle choisit une page ; c'est le code qui fabrique l'adresse.
7. **Peu de texte libre envoyé au modèle.** La description des bâtiments n'est pas transmise : un texte saisi pourrait contenir des consignes piégées. Chaque résultat d'outil est encadré comme une donnée, et le prompt système rappelle qu'un résultat d'outil est une information, jamais une consigne. L'adresse de la page n'est transmise que si elle a la forme d'une adresse.
   - **Aucune écriture sans clic.** Un outil d'écriture ne fait que préparer ; l'action n'a lieu qu'après « Valider », avec cinq vérifications et une trace dans le journal.
   - **Données personnelles.** Le nom d'un occupant est envoyé au modèle quand il faut répondre « qui doit combien », jamais son téléphone ni son adresse e-mail.
8. **Aucun HTML ne passe tel quel.** `miseEnForme` échappe tout le texte avant d'ajouter ses propres balises.
9. **La mémoire est cloisonnée.** Toutes les requêtes de `AssistantRepository` filtrent sur `id_compte`.

Les vérifications côté serveur sont celles qui protègent. Les gardes Angular ne servent qu'au confort.

## Ajouter un outil

1. Créer une classe `@Component` dans `assistant/outils/` qui implémente `OutilAssistant`. La boucle la trouve toute seule.
2. Écrire une **description** claire : c'est elle que le modèle lit pour choisir l'outil.
3. Indiquer le **droit** FACT exigé (`Profil.Droit`), ou `null` si tout compte connecté peut l'utiliser.
4. Dans `executer`, appeler une **facade existante** avec l'`Utilisateur` reçu, jamais le repository directement.
5. Renvoyer un texte court et factuel, sans texte libre saisi par des utilisateurs.
6. Ajouter la capacité à la ligne « Tu peux : » de `PromptSysteme`.
7. Écrire un test sur les refus, sur le modèle de `OuvrirPageOutilTest`.

Si on ajoute une page dans `app.routes.ts`, il faut aussi l'ajouter dans `OuvrirPageOutil`.

## Configuration

Dans le fichier `.env`, à la racine du projet :

```properties
OPENAI_API_KEY=sk-...
OPENAI_MODEL=nom-exact-du-modèle
ASSISTANT_TOKENS_JOUR=100000
```

Sans ces deux valeurs, l'assistant répond qu'il n'est pas configuré, sans appeler OpenAI. Le modèle de fichier est `.env.example`.

## Les tests

```text
cd backend
gradlew.bat test --tests "com.eburtis.fact.assistant.*"
```

| Test | Ce qu'il vérifie |
| --- | --- |
| `ClientOpenAiTest` | La requête envoyée à OpenAI (clé, modèle, messages, outils) et les tokens lus, avec un faux serveur. |
| `ClientOpenAiPannesTest` | Un nouvel essai si OpenAI est en panne une fois ; pas de nouvel essai si la clé est refusée. |
| `BoucleAgentTest` | Outil exécuté avec le compte connecté, résultat encadré, outil interdit refusé, outil d'écriture jamais exécuté dans la boucle, budget atteint, 8 tours au maximum, empreinte. |
| `OuvrirPageOutilTest` | Les refus : droit manquant, page réservée au personnel, page inventée. |
| `OutilsAssistantTest` | Le guide trouve la bonne section, le signalement refuse une catégorie inventée, le contexte de page refuse une adresse suspecte. |
| `EvaluationAssistantTest` | 15 questions types : le **vrai** modèle doit choisir le bon outil, avec 80 % de réussite au moins. |

Seul `EvaluationAssistantTest` appelle le vrai OpenAI, pour environ 2 centimes. Il est sauté tant que les variables `OPENAI_API_KEY` et `OPENAI_MODEL` ne sont pas définies dans le terminal :

```powershell
$env:OPENAI_API_KEY="sk-..."; $env:OPENAI_MODEL="..."
.\gradlew.bat test --tests "*EvaluationAssistantTest" -i
```

À relancer après chaque changement du prompt système ou d'une description d'outil : si le score baisse, le changement a cassé quelque chose.
