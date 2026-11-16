# Base de données

## Mise en place

1. `creer_base.sql` — l'utilisateur et la base, en superutilisateur sur `postgres`.
2. `creer_extensions.sql` — PostGIS et `unaccent`, en superutilisateur sur `fact_immobilier`.
3. Les tables : rien à jouer, les migrations Flyway s'appliquent au démarrage de l'API.

## Migrations

Dans `backend/src/main/resources/flyway/` :

- `schema/` — le modèle de données, appliqué partout ;
- `demo/` — un jeu de démonstration fictif (préfixe `V9x`), chargé seulement avec le profil
  `demo` (`demarrer.bat demo`).

Une migration déjà jouée ne se modifie jamais : Flyway en vérifie l'empreinte et refuserait
de démarrer. On en ajoute une nouvelle.

| Migration | Contenu |
| --- | --- |
| `V1__socle.sql` | Extensions, schémas, listes de valeurs, seuils réglables, comptes, journal |
| `V2__referentiel_abidjan.sql` | Communes du District d'Abidjan et leurs quartiers |
| `V3__patrimoine.sql` | Propriétaires, bâtiments, lots ; vue `pat.v_batiment` |
| `V4__reprise_site_actuel.sql` | Les trois annonces du site actuel, à valider et à localiser |
| `V5__location.sql` | Occupants, baux, échéances, encaissements ; production des échéances ; situation de paiement |
| `V6__interventions.sql` | Prestataires, interventions et leurs étapes, photos ; seuil d'accord du propriétaire ; travaux dans la vue des bâtiments |
| `V7__mandats_alertes.sql` | Mandats et bâtiments couverts, échéance avec reconduction ; alertes automatiques et leur production |
| `V8__alertes_tournures.sql` | Tournures des alertes (« d'octobre », « lot A1, Immeuble X ») |
| `V9__site_annonces_demandes.sql` | Annonces et photos (retrait automatique à la signature d'un bail), demandes du site et pièces jointes, alertes de recherche, boîte d'envoi, alertes internes du site |
| `V10__affectations.sql` | Historique des changements de gestionnaire et de suppléant (tenu par un déclencheur), alerte de réaffectation |
| `V11__terrain.sql` | Photos des bâtiments ; recensements reçus du terrain (clé unique contre les doublons) |
| `V12__espace_proprietaire.sql` | Réponse du propriétaire aux devis, mandat signé déposé, alertes et relevé mensuel du propriétaire |
| `V13__vue_intervention_accord.sql` | Vue des interventions recréée pour reprendre la réponse du propriétaire |
| `V14__tournees_etats_des_lieux.sql` | Tournées et étapes (schéma `ter`), états des lieux et leurs photos, clé et jeton de reçu sur les encaissements, point de départ des tournées |

## Limites des communes et quartiers

Les communes ne portent pour l'instant qu'un **point de centrage approximatif** (colonne
`centre`), suffisant pour cadrer la carte et rattacher un bâtiment à sa commune. Leurs limites
officielles (colonne `geom`, `MultiPolygon` en 4326) restent nulles.

Pour les charger, une fois un GeoJSON des limites obtenu (District autonome d'Abidjan, ou
extraction OpenStreetMap des relations administratives de niveau 8) :

```bash
# Exemple avec ogr2ogr (GDAL) : charge le GeoJSON dans une table de travail,
# puis reporte les géométries sur adm.commune par rapprochement des noms.
ogr2ogr -f PostgreSQL \
  "PG:host=localhost dbname=fact_immobilier user=fact_immobilier" \
  communes_abidjan.geojson \
  -nln adm.import_commune -t_srs EPSG:4326 -overwrite
```

```sql
UPDATE adm.commune c
   SET geom = ST_Multi(ST_CollectionExtract(ST_MakeValid(i.wkb_geometry), 3))
  FROM adm.import_commune i
 WHERE unaccent(lower(i.nom)) = unaccent(lower(c.libelle));

-- Contrôle : aucune commune ne doit rester sans limites
SELECT libelle FROM adm.commune WHERE geom IS NULL ORDER BY ordre;

DROP TABLE adm.import_commune;
```

Le rapprochement par nom demande un contrôle visuel : « Le Plateau » et « Plateau »,
« Port-Bouët » et « Port Bouet » ne s'écrivent pas de la même façon selon les sources.

## Quartiers

La liste des quartiers de `V2` couvre les principaux quartiers des communes où l'agence
travaille, Cocody en premier. Elle est à compléter avec l'agence au cadrage (§ 7.2 des
spécifications) : l'administrateur pourra l'enrichir depuis l'application à l'étape 4.

## Sauvegarde

À sauvegarder ensemble, quotidiennement (§ 9.2 du cahier des charges) :

```bash
pg_dump -U fact_immobilier -Fc fact_immobilier > fact_immobilier_$(date +%Y%m%d).dump
```

et le dossier des fichiers déposés (`FACT_DOSSIER_FICHIERS`, par défaut
`~/fact-immobilier/uploads`) : photos des biens, documents des mandats, états des lieux.
La restauration doit être testée, pas seulement prévue.
