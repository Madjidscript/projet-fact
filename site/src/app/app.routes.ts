import { Routes } from '@angular/router';

/** Les pages du site public (§ 3). Chaque route porte son titre et sa description. */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/accueil').then((m) => m.Accueil),
    title: '',
    data: { description: 'FACT Immobilier, agence immobilière à Abidjan depuis 2014 : gestion locative, location et vente, construction, travaux. Trouvez un logement ou confiez-nous votre bien.' },
  },
  {
    path: 'louer',
    loadComponent: () => import('./pages/biens').then((m) => m.Biens),
    title: 'Biens à louer à Abidjan',
    data: { operation: 'louer', description: 'Appartements, villas, bureaux et magasins à louer à Abidjan, visités et vérifiés par FACT Immobilier.' },
  },
  {
    path: 'acheter',
    loadComponent: () => import('./pages/biens').then((m) => m.Biens),
    title: 'Biens à vendre à Abidjan',
    data: { operation: 'acheter', description: 'Maisons, appartements et terrains à vendre à Abidjan avec FACT Immobilier.' },
  },
  {
    path: 'biens/:slug',
    loadComponent: () => import('./pages/fiche-bien').then((m) => m.FicheBien),
    title: 'Bien',
  },
  {
    path: 'services',
    loadComponent: () => import('./pages/services').then((m) => m.Services),
    title: 'Nos services',
    data: { description: 'Gestion immobilière, transactions, construction, travaux : les quatre métiers de FACT Immobilier à Abidjan.' },
  },
  {
    path: 'services/:slug',
    loadComponent: () => import('./pages/metier').then((m) => m.MetierPage),
    title: 'Nos services',
  },
  {
    path: 'agence',
    loadComponent: () => import('./pages/agence').then((m) => m.Agence),
    title: 'L’agence',
    data: { description: 'FACT Immobilier : agence créée en 2013 à Abidjan, membre de la CDAIM. Sérieux, dynamisme, professionnalisme.' },
  },
  {
    path: 'confier-mon-bien',
    loadComponent: () => import('./pages/confier').then((m) => m.Confier),
    title: 'Confier mon bien',
    data: { description: 'Propriétaire à Abidjan ? Confiez la gestion, la location ou la vente de votre bien à FACT Immobilier.' },
  },
  {
    path: 'contact',
    loadComponent: () => import('./pages/contact').then((m) => m.Contact),
    title: 'Contact',
    data: { description: 'Contactez FACT Immobilier : Cocody Riviera 3, Cité Allabra. 27 22 46 91 38 · 05 55 43 55 55.' },
  },
  {
    path: 'devis',
    loadComponent: () => import('./pages/devis').then((m) => m.Devis),
    title: 'Devis et estimation',
    data: { description: 'Demandez un devis de construction ou de travaux, ou l’estimation de votre bien, à FACT Immobilier : joignez vos plans et photos.' },
  },
  {
    path: 'favoris',
    loadComponent: () => import('./pages/favoris').then((m) => m.Favoris),
    title: 'Mes favoris',
    data: { description: 'Vos biens favoris, gardés sur votre appareil, et leur comparaison côte à côte.' },
  },
  {
    path: 'alertes/arreter',
    loadComponent: () => import('./pages/alerte-arreter').then((m) => m.AlerteArreter),
    title: 'Arrêter une alerte',
  },
  {
    path: 'mentions-legales',
    loadComponent: () => import('./pages/mentions').then((m) => m.Mentions),
    title: 'Mentions légales',
    data: { description: 'Mentions légales et protection des données du site de FACT Immobilier.' },
  },
  {
    path: '**',
    loadComponent: () => import('./pages/introuvable').then((m) => m.Introuvable),
    title: 'Page introuvable',
  },
];
