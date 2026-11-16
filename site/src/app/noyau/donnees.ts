/**
 * Les contenus du site. Ils reprennent ce que l'agence publie déjà (site actuel, relevé du
 * 12 novembre 2026) ; ce qu'elle n'a pas encore fourni (équipe, agrément, témoignages) n'est
 * pas inventé. Les biens et les chiffres clés viennent de l'API publique.
 */

export const AGENCE = {
  nom: 'FACT Immobilier',
  creation: 2013,
  demarrage: 2014,
  adresse: 'Cocody Riviera 3, Cité Allabra, Résidence GT',
  ville: 'Abidjan, Côte d’Ivoire',
  telephones: [
    { libelle: '27 22 46 91 38', lien: 'tel:+2252722469138' },
    { libelle: '05 55 43 55 55', lien: 'tel:+2250555435555' },
  ],
  email: 'info@factimmobilier-ci.com',
  /** Le numéro mobile de l'agence, utilisé pour WhatsApp. À confirmer avec l'agence. */
  whatsapp: 'https://wa.me/2250555435555',
  valeurs: [
    { titre: 'Sérieux', texte: 'Des engagements tenus, des comptes clairs et un suivi écrit de chaque dossier.', icone: 'bouclier' },
    { titre: 'Dynamisme', texte: 'Une équipe présente sur le terrain, qui répond vite et relance pour vous.', icone: 'synchro' },
    { titre: 'Professionnalisme', texte: 'Des méthodes rigoureuses, de l’état des lieux à la remise des clés.', icone: 'etoile' },
  ],
};

/** Années d'activité depuis le démarrage (2014). */
export const anneesActivite = () => new Date().getFullYear() - AGENCE.demarrage;

export interface Metier {
  slug: string;
  /** Le code METIER du référentiel, transmis avec les demandes de devis. */
  code: 'GESTION' | 'TRANSACTION' | 'CONSTRUCTION' | 'TRAVAUX';
  titre: string;
  court: string;
  accroche: string;
  icone: string;
  texte: string[];
  etapes: { titre: string; texte: string }[];
  appel: { libelle: string; lien: string };
}

export const METIERS: Metier[] = [
  {
    slug: 'gestion-immobiliere',
    code: 'GESTION',
    titre: 'Gestion immobilière',
    court: 'Gestion locative',
    accroche: 'Votre bien loué, entretenu et suivi, sans vous en occuper.',
    icone: 'gestion',
    texte: [
      'Nous trouvons vos locataires, vérifions leurs dossiers, rédigeons les baux et réalisons les états des lieux d’entrée et de sortie.',
      'Chaque mois, nous encaissons les loyers, relançons les retards et vous remettons un relevé clair : loyers reçus, honoraires, travaux, montant reversé.',
      'Une panne ? Nous faisons intervenir un prestataire, suivons les travaux et vous demandons votre accord au-delà du montant fixé dans votre mandat.',
    ],
    etapes: [
      { titre: 'Visite et estimation', texte: 'Nous visitons votre bien et proposons un loyer juste pour le quartier.' },
      { titre: 'Signature du mandat', texte: 'Durée, honoraires et seuil de travaux : tout est écrit.' },
      { titre: 'Mise en location', texte: 'Annonce, visites, sélection du locataire, bail et état des lieux.' },
      { titre: 'Gestion au quotidien', texte: 'Loyers, relances, entretien, relevé de gestion régulier.' },
    ],
    appel: { libelle: 'Confier mon bien', lien: '/confier-mon-bien' },
  },
  {
    slug: 'transactions',
    code: 'TRANSACTION',
    titre: 'Transactions immobilières',
    court: 'Achat, vente, location',
    accroche: 'Acheter, vendre ou louer en toute sécurité.',
    icone: 'transaction',
    texte: [
      'Vous cherchez un logement ou un local : nous vous proposons des biens visités et vérifiés, et vous accompagnons jusqu’à la remise des clés.',
      'Vous vendez : nous estimons votre bien au prix du marché, le présentons aux bons acquéreurs et sécurisons la vente jusqu’à la signature.',
    ],
    etapes: [
      { titre: 'Votre besoin', texte: 'Budget, quartier, type de bien : nous précisons ensemble votre recherche.' },
      { titre: 'Sélection et visites', texte: 'Nous vous présentons des biens qui correspondent vraiment.' },
      { titre: 'Vérifications', texte: 'Documents du bien et du propriétaire contrôlés avant tout engagement.' },
      { titre: 'Signature', texte: 'Bail ou acte de vente, état des lieux et remise des clés.' },
    ],
    appel: { libelle: 'Faire estimer mon bien', lien: '/devis' },
  },
  {
    slug: 'construction',
    code: 'CONSTRUCTION',
    titre: 'Construction',
    court: 'Du terrain aux clés',
    accroche: 'De l’achat du terrain à la livraison, un seul interlocuteur.',
    icone: 'construction',
    texte: [
      'Nous vous accompagnons à chaque étape de votre projet : vérification du terrain, plans, autorisations, choix des entreprises et suivi du chantier.',
      'Vous suivez l’avancement des travaux et le budget, et nous vous remettons un ouvrage conforme à ce qui a été convenu.',
    ],
    etapes: [
      { titre: 'Étude du projet', texte: 'Vos besoins, votre budget, votre terrain.' },
      { titre: 'Plans et autorisations', texte: 'Conception, devis détaillé et démarches administratives.' },
      { titre: 'Chantier', texte: 'Suivi régulier, contrôle de la qualité et des délais.' },
      { titre: 'Livraison', texte: 'Réception des travaux et remise des clés.' },
    ],
    appel: { libelle: 'Demander un devis', lien: '/devis' },
  },
  {
    slug: 'travaux',
    code: 'TRAVAUX',
    titre: 'Travaux et rénovation',
    court: 'Entretien, rénovation',
    accroche: 'Rénover, réparer, remettre en état avant relocation.',
    icone: 'travaux',
    texte: [
      'Plomberie, électricité, peinture, étanchéité, menuiserie : nous faisons intervenir des prestataires de confiance et contrôlons le travail rendu.',
      'Pour un logement à relouer ou un bien à valoriser, nous établissons un devis clair et suivons les travaux jusqu’à leur réception.',
    ],
    etapes: [
      { titre: 'Constat', texte: 'Visite sur place et description précise des travaux.' },
      { titre: 'Devis', texte: 'Un montant clair, soumis à votre accord.' },
      { titre: 'Réalisation', texte: 'Travaux suivis, photos avant et après.' },
      { titre: 'Réception', texte: 'Contrôle final et coût réel communiqué.' },
    ],
    appel: { libelle: 'Demander un devis', lien: '/devis' },
  },
];

/** Les catégories de biens de la recherche (§ 3.2), comme l'API les nomme. */
export const TYPES_BIEN = ['Studio', 'Appartement', 'Villa', 'Duplex', 'Bureau', 'Magasin', 'Entrepôt', 'Terrain', 'Immeuble'];

/** « 375 000 FCFA / mois » : le format unique des prix (§ 3.2). */
export function prix(b: { prix: number; operation: string }): string {
  const n = montant(b.prix);
  return b.operation === 'LOCATION' ? `${n} FCFA / mois` : `${n} FCFA`;
}

/** « 1 250 000 » : espace simple entre les milliers. */
export function montant(n: number): string {
  return Math.round(n).toLocaleString('fr-FR').replace(/[\u202f\u00a0]/g, ' ');
}
