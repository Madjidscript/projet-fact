import { Routes } from '@angular/router';
import { connecteGarde, droitGarde, inviteGarde, personnelGarde, sessionGarde, terrainGarde } from './noyau/gardes';
import { DROITS } from './noyau/modeles';

/**
 * Les routes de l'application de gestion. Tout ce qui est sous la coque (menu latéral ou
 * barre basse) exige une session ; chaque rubrique exige en plus son droit (§ 2.2).
 * La carte arrive à l'étape 5.
 */
export const routes: Routes = [
  {
    path: 'connexion',
    canActivate: [inviteGarde],
    loadComponent: () => import('./pages/connexion/connexion').then((m) => m.Connexion),
    title: 'Connexion — FACT Immobilier',
  },
  // Plein écran, hors de la coque : l'application de terrain, utilisable sans réseau (§ 4.6).
  {
    path: 'terrain',
    canActivate: [terrainGarde],
    loadComponent: () => import('./pages/terrain/accueil-terrain').then((m) => m.AccueilTerrain),
    title: 'Terrain — FACT Immobilier',
  },
  {
    path: 'terrain/recensement',
    canActivate: [terrainGarde],
    loadComponent: () => import('./pages/terrain/terrain').then((m) => m.Terrain),
    title: 'Recensement — FACT Immobilier',
  },
  {
    path: 'terrain/tournee',
    canActivate: [terrainGarde],
    loadComponent: () => import('./pages/terrain/tournee-terrain').then((m) => m.TourneeTerrain),
    title: 'Ma tournée — FACT Immobilier',
  },
  {
    path: 'terrain/encaissement',
    canActivate: [terrainGarde],
    loadComponent: () => import('./pages/terrain/encaissement-terrain').then((m) => m.EncaissementTerrain),
    title: 'Encaisser un loyer — FACT Immobilier',
  },
  {
    path: 'terrain/etat-des-lieux',
    canActivate: [terrainGarde],
    loadComponent: () => import('./pages/terrain/etat-des-lieux-terrain').then((m) => m.EtatDesLieuxTerrain),
    title: 'État des lieux — FACT Immobilier',
  },
  {
    path: 'mot-de-passe',
    canActivate: [sessionGarde],
    loadComponent: () => import('./pages/mot-de-passe/mot-de-passe').then((m) => m.MotDePasse),
    title: 'Mot de passe — FACT Immobilier',
  },
  {
    path: '',
    canActivate: [connecteGarde],
    loadComponent: () => import('./mise-en-page/coque').then((m) => m.Coque),
    children: [
      {
        path: '',
        pathMatch: 'full',
        canActivate: [personnelGarde],
        loadComponent: () => import('./pages/tableau-bord/tableau-bord').then((m) => m.TableauBord),
        title: 'Tableau de bord — FACT Immobilier',
      },
      {
        // L'espace du propriétaire (§ 4.9) ; la Direction y voit celui d'un propriétaire (?proprietaire=).
        path: 'espace',
        loadComponent: () => import('./pages/espace/espace').then((m) => m.EspaceProprietaire),
        title: 'Mon espace — FACT Immobilier',
      },
      {
        path: 'carte',
        canActivate: [droitGarde(DROITS.patrimoineLire)],
        loadComponent: () => import('./pages/carte/carte').then((m) => m.Carte),
        title: 'Carte du patrimoine — FACT Immobilier',
      },
      {
        path: 'patrimoine',
        canActivate: [personnelGarde, droitGarde(DROITS.patrimoineLire)],
        loadComponent: () => import('./pages/patrimoine/batiments').then((m) => m.Batiments),
        title: 'Bâtiments — FACT Immobilier',
      },
      {
        path: 'patrimoine/:id',
        canActivate: [personnelGarde, droitGarde(DROITS.patrimoineLire)],
        loadComponent: () => import('./pages/patrimoine/fiche-batiment').then((m) => m.FicheBatiment),
        title: 'Bâtiment — FACT Immobilier',
      },
      {
        path: 'loyers',
        canActivate: [personnelGarde, droitGarde(DROITS.locationLire)],
        loadComponent: () => import('./pages/loyers/loyers').then((m) => m.Loyers),
        title: 'Loyers — FACT Immobilier',
      },
      {
        path: 'travaux',
        canActivate: [personnelGarde, droitGarde(DROITS.travauxLire)],
        loadComponent: () => import('./pages/travaux/travaux').then((m) => m.Travaux),
        title: 'Travaux — FACT Immobilier',
      },
      {
        path: 'tournees',
        canActivate: [personnelGarde, connecteGarde],
        loadComponent: () => import('./pages/tournees/tournees').then((m) => m.Tournees),
        title: 'Tournées — FACT Immobilier',
      },
      {
        path: 'portefeuilles',
        canActivate: [personnelGarde, droitGarde(DROITS.affectationLire)],
        loadComponent: () => import('./pages/portefeuilles/portefeuilles').then((m) => m.Portefeuilles),
        title: 'Portefeuilles — FACT Immobilier',
      },
      {
        path: 'annonces',
        canActivate: [personnelGarde, connecteGarde],
        loadComponent: () => import('./pages/annonces/annonces').then((m) => m.Annonces),
        title: 'Annonces — FACT Immobilier',
      },
      {
        path: 'demandes',
        canActivate: [personnelGarde, connecteGarde],
        loadComponent: () => import('./pages/demandes/demandes').then((m) => m.Demandes),
        title: 'Demandes — FACT Immobilier',
      },
      {
        path: 'rapports',
        canActivate: [personnelGarde, droitGarde(DROITS.tableauBord)],
        loadComponent: () => import('./pages/rapports/rapports').then((m) => m.Rapports),
        title: 'Rapports — FACT Immobilier',
      },
      {
        path: 'proprietaires',
        canActivate: [personnelGarde, droitGarde(DROITS.patrimoineLire)],
        loadComponent: () => import('./pages/proprietaires/proprietaires').then((m) => m.Proprietaires),
        title: 'Propriétaires — FACT Immobilier',
      },
      {
        path: 'import',
        canActivate: [droitGarde(DROITS.parametresLire)],
        loadComponent: () => import('./pages/import/import').then((m) => m.Import),
        title: 'Import du patrimoine — FACT Immobilier',
      },
      {
        path: 'comptes',
        canActivate: [droitGarde(DROITS.parametresLire)],
        loadComponent: () => import('./pages/comptes/comptes').then((m) => m.Comptes),
        title: 'Comptes — FACT Immobilier',
      },
      {
        path: 'journal',
        canActivate: [droitGarde(DROITS.parametresLire)],
        loadComponent: () => import('./pages/journal/journal').then((m) => m.Journal),
        title: 'Journal — FACT Immobilier',
      },
      {
        // Le guide illustré des essais : À RETIRER À LA LIVRAISON (avec pages/guide et public/guide-essais).
        path: 'guide',
        loadComponent: () => import('./pages/guide/guide').then((m) => m.Guide),
        title: 'Guide — FACT Immobilier',
      },
      {
        path: 'catalogue',
        canActivate: [droitGarde(DROITS.comptes)],
        loadComponent: () => import('./pages/catalogue/catalogue').then((m) => m.Catalogue),
        title: 'Composants — FACT Immobilier',
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
