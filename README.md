# FACT Immobilier — site web et application SIG de gestion du patrimoine

Deux réalisations complémentaires sur une même base de biens :

- **Lot 1 — le site web** institutionnel et commercial (catalogue, carte, formulaires de conversion) ;
- **Lot 2 — l'application SIG** de gestion du patrimoine (carte des bâtiments, lots, baux, loyers,
  interventions, PWA de terrain, espace propriétaire).

Références : cahier des charges `CDC-FACT-2026-01` et spécifications fonctionnelles
`SFD-FACT-2026-01`, dans [docs/](docs/). Le plan de réalisation en 18 étapes est au § 9 des
spécifications ; l'avancement est suivi plus bas.

## Architecture

| Brique | Technologie | Rôle |
| --- | --- | --- |
| Application de gestion | Angular 20 (composants autonomes, signaux) | Écrans de gestion, carte, PWA de terrain |
| API | Spring Boot 3.2 (Java 17, Spring JDBC) | API REST, règles de gestion, sécurité |
| Base de données | PostgreSQL + PostGIS | Patrimoine géoréférencé, migrations Flyway |
| Cartographie | Leaflet, fonds OpenStreetMap et satellite | Carte, couches thématiques |

L'interface se compile dans `backend/src/main/resources/static` : le `.jar` produit sert alors
l'application entière.

```
projet-fact/
├── backend/      API Spring Boot et migrations Flyway
├── frontend/     Application Angular
├── database/     Scripts d'exploitation (création de la base, purges)
├── docs/         Cahier des charges, spécifications fonctionnelles, guide d'utilisation (guide-utilisateur.md)
├── demarrer.bat             Lance l'API (et l'application) sur :8791
├── developper_interface.bat Lance Angular en rechargement à chaud sur :4201
└── construire.bat           Produit le .jar complet
```

### Organisation du code

Le backend suit le découpage `controller → facade → service → repository` :

- `controller` : les adresses de l'API, le contrôle des droits, rien de plus ;
- `facade` : la règle de gestion et la transaction ;
- `repository` : le SQL, en Spring JDBC — pas d'ORM, les requêtes restent lisibles ;
- `domain` : les objets du métier, en `record` ;
- `presentation/dto` : ce qui entre et ce qui sort de l'API.

Le frontend sépare `noyau` (services, modèles, formats), `composants` (briques réutilisables),
`mise-en-page` et `pages`. Le code, les noms et les commentaires sont en français, comme
l'interface (§ 8 des spécifications).

## Installation

### Ce qu'il faut

- **Java 17** ou plus
- **Node.js 20** ou plus
- **PostgreSQL 14** ou plus, avec **PostGIS**

### 1. Créer la base

```sql
CREATE USER fact_immobilier WITH PASSWORD 'fact_immobilier';
CREATE DATABASE fact_immobilier OWNER fact_immobilier;
```

Puis, connecté à `fact_immobilier` **en superutilisateur** (les extensions l'exigent) :

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS unaccent;
```

Le script [database/creer_base.sql](database/creer_base.sql) reprend ces commandes.

Les tables, elles, sont créées par Flyway au démarrage de l'API : rien d'autre à jouer à la main.

### 2. Lancer l'API

```
demarrer.bat
```

L'API démarre sur <http://localhost:8791>, applique les migrations et sert l'interface si elle a
été compilée. Pour vérifier : <http://localhost:8791/api/sante>.

### 3. Lancer l'interface en développement

```
developper_interface.bat
```

Angular démarre sur <http://localhost:4201> et transmet les appels `/api` à l'API
(voir `frontend/proxy.conf.json`).

### 4. Produire la version livrable

```
construire.bat
java -jar backend\build\libs\fact-immobilier.jar
```

## Configuration

Toute valeur de `backend/src/main/resources/application.properties` se surcharge par une variable
d'environnement :

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `FACT_DB_URL` | `jdbc:postgresql://localhost:5432/fact_immobilier` | Adresse de la base |
| `FACT_DB_UTILISATEUR` | `fact_immobilier` | Utilisateur de la base |
| `FACT_DB_MOT_DE_PASSE` | `fact_immobilier` | Mot de passe |
| `FACT_JWT_SECRET` | valeur de développement | **À remplacer en production** : 32 caractères au moins |
| `FACT_CORS` | `http://localhost:4201` | Origines autorisées à appeler l'API |
| `FACT_DOSSIER_FICHIERS` | `~/fact-immobilier/uploads` | Racine de stockage des photos et documents |
| `SERVER_PORT` | `8791` | Port de l'API |

## L'API

| Adresse | Accès | Rend |
| --- | --- | --- |
| `GET /api/sante` | public | Version, base joignable, dernière migration, PostGIS |
| `GET /api/referentiel` | public | Communes et quartiers, listes de valeurs, seuils, profils, cadrage de la carte |
| `GET /api/referentiel/communes` | public | Les communes, sans leurs quartiers |
| `GET /api/referentiel/communes/{id}/quartiers` | public | Les quartiers d'une commune |
| `GET /api/referentiel/listes` | public | Les listes de valeurs par domaine |

| `POST /api/auth/connexion` | public | Ouvre une session : jeton, échéance, compte |
| `GET /api/auth/moi` | connecté | Le compte connecté et ses droits |
| `POST /api/auth/mot-de-passe` | connecté | Change son mot de passe ; ferme ses autres sessions |
| `GET /api/comptes` | droit `parametres.lire` | Les comptes, les profils et les droits |
| `POST /api/comptes` | droit `comptes` | Crée ou modifie un compte ; un compte se désactive, il ne se supprime pas |
| `POST /api/comptes/{identifiant}/reinitialiser` | droit `comptes` | Pose un mot de passe provisoire |
| `GET /api/journal` | droit `parametres.lire` | Le journal des actions : auteur, date, ancienne et nouvelle valeur |
| `GET /api/batiments` | droit `patrimoine.lire` | Les bâtiments du périmètre du compte, avec le décompte de leurs lots |
| `GET /api/batiments/{id}` | droit `patrimoine.lire` | La fiche d'un bâtiment, ses lots, son propriétaire |
| `GET /api/batiments/{id}/historique` | droit `patrimoine.ecrire` | Les modifications du bâtiment et de ses lots |
| `POST /api/batiments` | droit `patrimoine.ecrire` | Crée ou modifie ; position et propriétaire obligatoires |
| `POST /api/batiments/{id}/valider` | gestionnaire, Direction | Valide une fiche venue du terrain ou d'un import |
| `POST /api/batiments/{id}/archiver` | Direction | Archive ou restaure (`?archive=false`) |
| `POST /api/lots` | droit `patrimoine.ecrire` | Crée ou modifie un lot ; numéro de porte unique dans le bâtiment |
| `POST /api/lots/{id}/archiver` | gestionnaire, Direction | Archive un lot non occupé |
| `GET /api/proprietaires` | droit `patrimoine.lire` | Les propriétaires ; coordonnées réservées aux profils qui gèrent |
| `POST /api/proprietaires` | droit `patrimoine.ecrire` | Crée ou modifie un propriétaire |
| `GET /api/loyers/synthese` | droit `location.lire` | Attendu, réglé et encaissé des six derniers mois ; impayés ; derniers encaissements |
| `GET /api/baux` | droit `location.lire` | Les baux du périmètre (`?filtre=actifs` ou `retard`, `?batiment=`) avec leur situation |
| `GET /api/lots/{id}/location` | droit `location.lire` | Le bail actif d'un lot, ses échéances, ses encaissements, ses baux passés |
| `POST /api/baux` | droit `location.ecrire` | Signe un bail (locataire choisi ou créé) ; le lot passe « Occupé » |
| `POST /api/baux/{id}/preavis` · `/terminer` | droit `location.ecrire` | Préavis (lot « En préavis »), sortie (lot « Vacant ») |
| `POST /api/encaissements` | droit `encaissement.ecrire` | Enregistre un paiement ; il solde les mois les plus anciens d'abord |
| `POST /api/encaissements/{id}/annuler` | Direction | Annule un encaissement erroné, avec son motif ; il reste visible |
| `GET /api/encaissements/{id}/quittance` | droit `location.lire` | La quittance PDF (reçu si le paiement est partiel) |
| `GET /api/interventions` | droit `travaux.lire` | Les interventions du périmètre (`?filtre=ouvertes`, `?batiment=`) |
| `GET /api/interventions/synthese` | droit `travaux.lire` | Ouvertes, urgentes, délai moyen, coût du mois ; seuil d'accord du propriétaire |
| `GET /api/interventions/{id}` | droit `travaux.lire` | La fiche : déroulé, photos avant et après, actions permises au compte |
| `POST /api/interventions` | droit `travaux.ecrire` | Signale une panne sur un bâtiment ou un lot |
| `POST /api/interventions/{id}/{action}` | selon l'étape | `affecter`, `demarrer` (constat, devis), `valider` (accord du propriétaire au-delà du seuil), `terminer` (coût), `cloturer` (à la charge de qui), `annuler` |
| `POST /api/interventions/{id}/photos?moment=AVANT\|APRES` | droit `travaux.ecrire` | Ajoute des photos (réduites à 2048 px) |
| `GET /api/interventions/photos/{id}` | droit `travaux.lire` | Une photo, servie seulement aux comptes qui voient l'intervention |
| `GET /api/prestataires` · `POST /api/prestataires` | droit `travaux.lire` · responsable travaux, Direction | Le répertoire des prestataires |
| `GET /api/alertes` · `GET /api/alertes/compte` | connecté | Les alertes du compte (fins de bail, loyers en retard, lots vacants, mandats, urgences, bâtiments sans gestionnaire) et le nombre de non lues |
| `POST /api/alertes/{id}/lue` · `POST /api/alertes/lues` | connecté | Marque une alerte, ou toutes, comme lues |
| `POST /api/mandats` · `POST /api/mandats/{id}/resilier` | Direction | Crée, modifie ou résilie un mandat (durée, honoraires, seuil de travaux, biens couverts) |
| `POST /api/mandats/{id}/document` · `GET /api/mandats/{id}/document` | Patrimoine (dépôt) · propriétaire concerné | Dépose ou ouvre le mandat signé (PDF ou photo, 10 Mo au plus) |
| `GET /api/tournees` · `/la-mienne` · `/agents` · `/suggestions` · `/{id}` · `POST /api/tournees` | Agent (la sienne), gestionnaire, Direction | Tournées d'un jour, préparation (ordre de passage calculé), bâtiments à visiter en priorité |
| `POST /api/tournees/{id}/etapes/{idEtape}/pointage` · `POST /api/tournees/{id}/terminer` | Agent de la tournée | Pointage sur place (distance au bâtiment mesurée), compte rendu final, alerte aux gestionnaires |
| `POST /api/etats-des-lieux` (multipart) · `GET /api/baux/{id}/etats-des-lieux` · `GET /api/etats-des-lieux/{id}/pdf` | Agent, gestionnaire | État des lieux signé, rejouable sans doublon, un d'entrée et un de sortie par bail, PDF |
| `GET /api/public/recus/{jeton}` | Public (lien du message) | Le reçu PDF d'un paiement, ouvert par le locataire sans compte |
| `POST /api/proprietaires/{id}/espace` | Direction | Ouvre l'espace en ligne : compte à l'e-mail du propriétaire, mot de passe provisoire rendu une fois |
| `GET /api/espace` (`?proprietaire=` pour la Direction) | Propriétaire, Direction en aperçu | Biens, lots, loyers sur 12 mois, impayés, travaux, mandats, gestionnaires — sans données des locataires |
| `POST /api/espace/devis/{id}` | Propriétaire | Accord ou refus (motif obligatoire) d'un devis au-delà du seuil du mandat |
| `GET /api/rapports/indicateurs?du=AAAA-MM&au=AAAA-MM` | droit `tableau-bord` | Occupation, recouvrement, impayés par ancienneté, vacance, loyer au m², délai des interventions (périmètre du compte) |
| `GET /api/rapports/export/pdf` · `/export/excel` | droit `tableau-bord` | Les mêmes indicateurs en PDF ou en classeur Excel |
| `GET /api/proprietaires/{id}/releve` · `/releve/pdf` | Direction, le propriétaire lui-même | Relevé de gestion : loyers encaissés, honoraires, travaux à sa charge, montant à reverser |
| `GET /api/annonces` · `GET /api/annonces/{id}` · `GET /api/lots/{id}/annonce` | annonce.ecrire ou annonce.valider | Les annonces du périmètre ; l'annonce d'un lot |
| `POST /api/annonces` · `/{id}/soumettre` · `/{id}/valider` · `/{id}/refuser` · `/{id}/retirer` · `/{id}/une` | rédaction ; validation pour valider, refuser, mettre à la une | Préparer, proposer (au moins 3 photos, prix, surface, pièces, position), mettre en ligne, renvoyer, retirer |
| `POST /api/annonces/{id}/photos` · `/photos/ordre` · `/photos/{id}/supprimer` | rédaction | Photos de l'annonce ; la première est la photo principale |
| `GET /api/demandes` · `GET /api/demandes/{id}` · `POST /api/demandes/{id}/suivi` | Direction, éditeur web ; le gestionnaire pour ses bâtiments | Le tableau unique des demandes du site et leur suivi (Nouvelle, Prise en charge, Rendez-vous fixé, Conclue, Sans suite) |
| `GET /api/demandes/messages` · `POST /api/demandes/messages/{id}?envoye=` | Direction, éditeur web | La boîte d'envoi : accusés de réception et alertes aux abonnés, à envoyer d'un geste |
| `GET /api/public/biens` · `/biens/une` · `/biens/{slug}` · `/photos/{id}` · `/chiffres` | public | Le catalogue du site : recherche du § 3.2 (et cadre de la carte), fiche, photos, chiffres clés |
| `POST /api/public/demandes` · `POST /api/public/alertes` · `POST /api/public/alertes/arreter` | public, 8 envois par heure et par appareil | Visite, confier un bien, devis (pièces jointes, 10 Mo), estimation, contact ; alertes de recherche |
| `GET /api/affectations/portefeuilles` · `GET /api/affectations/historique` | affectation.lire | Portefeuilles des gestionnaires, bâtiments à réaffecter, historique des réaffectations |
| `POST /api/affectations` | affectation.ecrire (Direction) | Réaffecte un ou plusieurs bâtiments (gestionnaire, suppléant, motif) ; le nouveau gestionnaire est prévenu |
| `GET /api/terrain/preparation` | connecté | Ce que le téléphone garde pour saisir hors connexion (propriétaires) |
| `POST /api/terrain/recensements` | droit `patrimoine.ecrire` | Un recensement d'un bloc : bâtiment « À valider », lots et photos ; rejouable sans doublon (clé tirée sur le téléphone) |
| `POST /api/batiments/{id}/photos` · `GET /api/batiments/photos/{id}` | `patrimoine.ecrire` · `patrimoine.lire` | Photos d'un bâtiment |
| `GET /api/import/modele` | Direction | Le classeur modèle d'import |
| `POST /api/import/patrimoine` | Direction | Contrôle (`simuler=true`, par défaut) ou importe un classeur |

Les listes de référence sont publiques à dessein : elles remplissent le moteur de recherche du
site. Tout le reste de l'API exige un compte, et chaque adresse revérifie le droit demandé.

### Première connexion

Au premier démarrage, l'API crée le compte **`admin`** et écrit son mot de passe provisoire dans
`backend/comptes_provisoires.txt` (fichier ignoré par Git, à détruire une fois le mot de passe
changé). L'application impose de le remplacer à la première connexion. Les autres comptes se
créent ensuite depuis la rubrique « Comptes », chacun avec son profil.

Règles appliquées : mot de passe de 10 caractères au moins mêlant lettres et chiffres ; cinq
essais manqués bloquent le poste cinq minutes ; la session se ferme après trente minutes
d'inactivité ; désactiver un compte, changer son profil ou son mot de passe ferme ses sessions.

### Tests

```
cd backend
gradlew.bat test
```

Ils vérifient la matrice des droits du § 2.2 ligne par ligne, les jetons de session, le limiteur
d'essais et la politique des mots de passe. Aucun ne touche la base.

Les tests du patrimoine (règles du § 4.2, périmètres, import Excel) tournent contre la base, dans
une transaction annulée à la fin ; ils ne se lancent que sur demande :

```
set FACT_TESTS_BASE=1
gradlew.bat test
```

Toutes les erreurs sortent sous la même forme : `{"statut", "message", "code"}`, le message rédigé
en français à destination de la personne connectée.

## La base de données

| Schéma | Contenu |
| --- | --- |
| `adm` | Communes et quartiers du District d'Abidjan |
| `ref` | Listes de valeurs et seuils réglables |
| `pat` | Propriétaires, bâtiments, lots, mandats |
| `loc` | Occupants, baux, échéances, encaissements |
| `tra` | Interventions, travaux, chantiers |
| `web` | Annonces publiées et demandes venues du site |
| `app` | Comptes de la plateforme et journal des actions |
| `tdb` | Vues de restitution des tableaux de bord |

Stockage en **EPSG:4326** (celui du GPS des téléphones), calculs métriques en **EPSG:32630**
(UTM 30 N, zone d'Abidjan).

Les migrations sont dans `backend/src/main/resources/flyway/schema` et s'appliquent au démarrage.
Une migration déjà jouée ne se modifie jamais : on en ajoute une nouvelle.

> Mise en ligne : l'adresse du site public doit figurer dans `FACT_CORS` côté API, ou le site doit être servi sous le même nom de domaine que l'API.

> Application de terrain : le mode hors connexion ne fonctionne qu'avec la version de production
> (`ng build`, servie par l'API) et, sur un téléphone, seulement en **HTTPS** (les navigateurs
> n'autorisent le service worker qu'en HTTPS ou sur `localhost`). La mise en service (étape 18)
> doit donc prévoir un certificat.

## Avancement

Le plan de réalisation du § 9 des spécifications, étape par étape.

| Nº | Étape | État |
| --- | --- | --- |
| 1 | Socle technique | **Fait** — les trois briques démarrent et communiquent |
| 2 | Design system | **Fait** — polices embarquées, thème sombre, composants et mouvement ; catalogue sur `/catalogue` |
| 3 | Comptes et droits | **Fait** — connexion, profils, matrice du § 2.2, comptes, journal ; tests des droits au vert |
| 4 | Référentiel (propriétaires, bâtiments, lots, import Excel) | **Fait** — fiches, règles du § 4.2, import Excel contrôlé, reprise des 3 annonces du site |
| 5 | Carte | **Fait** — carte de tous les biens, regroupement, recherche, biens à localiser |
| 6 | Thématiques et filtres | **Fait en partie** — couleurs occupation, impayés, travaux, gestionnaire, validation ; filtre par commune |
| 7 | Qui gère quoi | **Fait** — écran Portefeuilles (chiffres par gestionnaire), réaffectation en masse depuis la liste ou la carte, suppléant, historique daté, alerte au nouveau gestionnaire |
| 8 | PWA de terrain | **Fait** — application installable (icône, plein écran, mise à jour automatique), recensement GPS avec précision et satellite, photos, lots, saisie hors connexion et envoi au retour du réseau (sans doublon) ; tournée du jour (préparée au bureau ou par l'agent, ordre de passage au plus court, itinéraire tracé, pointage GPS avec distance au bâtiment, compte rendu aux gestionnaires) ; encaissement sur place hors connexion avec reçu envoyé au locataire (lien public vers sa quittance) ; état des lieux pièce par pièce, photos, deux signatures au doigt, PDF comparant l'entrée à la sortie |
| 9 | Baux et loyers | **Fait** — locataires, baux, échéances mensuelles, encaissements, quittances PDF, impayés, préavis et sortie |
| 10 | Interventions | **Fait** — signalement avec photos, prestataires, affectation, constat et devis, accord du propriétaire au-delà du seuil, fin, clôture et imputation |
| 11 | Alertes et tableaux de bord | **Fait** — alertes dans l'application (cloche), page Rapports, exports PDF et Excel, mandats, relevé de gestion du propriétaire en PDF ; envois par e-mail, SMS ou WhatsApp en attente des comptes d'envoi |
| 12 | Espace propriétaire | **Fait** — la Direction ouvre l'espace depuis la fiche (connexion par e-mail, mot de passe provisoire) ; le propriétaire voit ses seuls biens lot par lot sur carte, ses loyers et impayés sans les noms des locataires, ses travaux, accepte ou refuse en ligne les devis au-delà de son seuil, télécharge relevé et mandat signé, contacte son gestionnaire |
| 13 | Site : structure et contenus | **Fait** — application `site/` (port 4320, `demarrer_site.bat`), pages, animations GSAP ; annonces de démonstration jusqu'à l'étape 14 |
| 14 | Site : catalogue | **Fait** — publication depuis la fiche du lot, validation, retrait automatique au bail ; site : recherche complète, liste et carte liées, fiche avec galerie, favoris et comparaison |
| 15 | Site : conversion | **Fait** — visite, confier un bien (avec carte), devis et estimation (pièces jointes), contact, alertes de recherche ; suivi des demandes et boîte d'envoi dans l'application |
| 16 | Animations et finitions | À faire |
| 17 | Qualité | À faire |
| 18 | Mise en service | À faire |

Règle de conduite retenue au § 9 : une étape n'est commencée que lorsque la précédente est
validée, et chaque étape se termine par une démonstration au référent de FACT Immobilier.

### Ce qui reste à obtenir de l'agence

Ces éléments bloquent certaines étapes (chapitre 11 du cahier des charges) :

- logo en haute définition et couleurs de l'agence — la charte actuelle part des couleurs du § 5.2
  des spécifications et sera ajustée au logo ;
- numéro et date de l'agrément, attestation d'adhésion à la CDAIM ;
- noms, fonctions et photos des associés et de l'équipe ;
- chiffres réels (bâtiments et lots gérés, transactions) ;
- liste du patrimoine géré : bâtiments, propriétaires, lots, locataires, loyers ;
- répartition actuelle des bâtiments entre gestionnaires ;
- liens exacts des réseaux sociaux et horaires d'ouverture ;
- accès à l'hébergement et au nom de domaine actuels.
