/** Les objets échangés avec l'API. Les noms suivent ceux du § 7.1 des spécifications. */

/** Un quartier d'une commune (adm.quartier). */
export interface Quartier {
  id: number;
  idCommune: number;
  code: string;
  libelle: string;
}

/** Une commune du District d'Abidjan (adm.commune). */
export interface Commune {
  id: number;
  code: string;
  libelle: string;
  district: string;
  latitude: number;
  longitude: number;
  /** Vrai dès que ses limites officielles sont chargées ; sinon seul son centre est connu. */
  limitesConnues: boolean;
  quartiers?: Quartier[];
}

/** Un choix d'une liste de référence (ref.valeur). */
export interface Valeur {
  domaine: string;
  code: string;
  libelle: string;
  ordre: number;
}

/** Un profil et les droits qu'il porte (§ 2.1 et § 2.2). */
export interface Profil {
  code: string;
  libelle: string;
  description: string;
  portee: 'SES_BIENS' | 'SON_PORTEFEUILLE' | 'TOUT';
  droits: string[];
}

/** Le cadrage de départ de la carte. */
export interface CadrageCarte {
  latitude: number;
  longitude: number;
  zoom: number;
}

/** Tout le référentiel, chargé en un appel au démarrage. */
export interface Referentiel {
  communes: Commune[];
  /** Les listes de valeurs par domaine : listes['TYPE_LOT'], listes['EQUIPEMENT']… */
  listes: Record<string, Valeur[]>;
  /** Les seuils et délais réglables, par leur clé. */
  reglages: Record<string, string>;
  profils: Profil[];
  carte: CadrageCarte;
}

/** L'état de la plateforme, tel que /api/sante le rend. */
export interface Sante {
  application: string;
  version: string;
  revision?: string;
  base: 'disponible' | 'indisponible';
  migration?: string;
  postgis?: string;
  communes?: number;
  quartiers?: number;
}

/** Un message bref affiché en bas d'écran (§ 5.4, composant « Notification »). */
export interface Avis {
  id: number;
  genre: 'succes' | 'erreur' | 'info';
  texte: string;
}

/** La personne connectée, telle que /api/auth la rend : de quoi composer son menu. */
export interface Compte {
  identifiant: string;
  nom: string;
  fonction: string | null;
  email: string | null;
  profil: string;
  libelleProfil: string;
  portee: 'SES_BIENS' | 'SON_PORTEFEUILLE' | 'TOUT';
  droits: string[];
  /** Mot de passe provisoire : il doit être changé avant toute autre chose. */
  doitChanger: boolean;
}

/** Une session ouverte : son jeton, son échéance, son titulaire. */
export interface Session {
  jeton: string;
  expiration: string;
  compte: Compte;
}

/** Un compte vu par l'administration des comptes. */
export interface CompteAdmin {
  id: number;
  identifiant: string;
  nom: string;
  fonction: string | null;
  email: string | null;
  telephone: string | null;
  profil: string;
  libelleProfil: string;
  /** Les droits accordés en plus de ceux du profil. */
  droits: string[];
  actif: boolean;
  doitChanger: boolean;
  derniereConnexion: string | null;
  creeLe: string;
}

/** Un droit et son libellé. */
export interface DroitLibelle {
  code: string;
  libelle: string;
}

/** Une ligne du journal des actions (app.journal). */
export interface LigneJournal {
  id: number;
  auteur: string;
  objet: string;
  cle: string | null;
  action: string;
  avant: Record<string, unknown> | null;
  apres: Record<string, unknown> | null;
  adresse_ip: string | null;
  le: string;
}

/** Les noms des droits, tels que l'API les connaît (matrice du § 2.2). */
export const DROITS = {
  patrimoineLire: 'patrimoine.lire',
  patrimoineEcrire: 'patrimoine.ecrire',
  affectationLire: 'affectation.lire',
  affectationEcrire: 'affectation.ecrire',
  locationLire: 'location.lire',
  locationEcrire: 'location.ecrire',
  encaissementEcrire: 'encaissement.ecrire',
  travauxLire: 'travaux.lire',
  travauxEcrire: 'travaux.ecrire',
  travauxValider: 'travaux.valider',
  annonceEcrire: 'annonce.ecrire',
  annonceValider: 'annonce.valider',
  contenuEcrire: 'contenu.ecrire',
  demandeLire: 'demande.lire',
  demandeEcrire: 'demande.ecrire',
  tableauBord: 'tableau-bord',
  parametresLire: 'parametres.lire',
  parametresEcrire: 'parametres.ecrire',
  terrain: 'terrain',
  comptes: 'comptes',
} as const;

/** Un bâtiment et le décompte de ses lots (vue pat.v_batiment). */
export interface Batiment {
  id: number;
  reference: string;
  nom: string;
  type: string;
  adresse: string | null;
  idCommune: number;
  commune: string;
  idQuartier: number | null;
  quartier: string | null;
  /** Nulles tant que le bâtiment est « à localiser » (§ 7.3). */
  latitude: number | null;
  longitude: number | null;
  precisionGps: number | null;
  niveaux: number | null;
  anneeConstruction: number | null;
  description: string | null;
  idProprietaire: number;
  referenceProprietaire: string;
  proprietaire: string;
  idGestionnaire: number | null;
  gestionnaire: string | null;
  idSuppleant: number | null;
  suppleant: string | null;
  validation: 'VALIDE' | 'A_VALIDER';
  origine: string;
  archiveLe: string | null;
  creeLe: string;
  creePar: string | null;
  majLe: string | null;
  majPar: string | null;
  lots: number;
  lotsOccupes: number;
  lotsVacants: number;
  loyersReference: number;
  /** Baux actifs ou en préavis. */
  bauxActifs: number;
  /** Loyers en retard, en FCFA. */
  impaye: number;
  /** Le plus grand nombre de mois de retard parmi ses baux. */
  moisRetardMax: number;
  loyersBaux: number;
  /** Interventions ni clôturées ni annulées. */
  interventionsOuvertes: number;
  /** Dont urgentes ou critiques. */
  interventionsUrgentes: number;
}

/** Un lot d'un bâtiment (pat.lot). */
export interface Lot {
  id: number;
  idBatiment: number;
  numero: string;
  etage: number;
  type: string;
  libelleType: string;
  surface: number | null;
  pieces: number | null;
  chambres: number | null;
  sallesEau: number | null;
  equipements: string[];
  loyerReference: number | null;
  charges: number | null;
  statut: string;
  libelleStatut: string;
  statutDepuis: string;
  description: string | null;
  archiveLe: string | null;
}

/** Un propriétaire mandant. Ses coordonnées ne sont rendues qu'aux profils qui gèrent le patrimoine. */
export interface Proprietaire {
  id: number;
  reference: string;
  typePersonne: string;
  nom: string;
  prenoms: string | null;
  nomComplet: string;
  origine: string;
  archiveLe: string | null;
  idCompte: number | null;
  batiments: number;
  telephone?: string | null;
  telephone2?: string | null;
  email?: string | null;
  adresse?: string | null;
  pieceType?: string | null;
  pieceNumero?: string | null;
  reversementMode?: string | null;
  reversementCoordonnees?: string | null;
  notes?: string | null;
}

/** Le compte rendu d'un contrôle ou d'un import Excel. */
export interface RapportImport {
  simulation: boolean;
  importe: boolean;
  proprietaires: { nouveaux: number; existants: number };
  batiments: { nouveaux: number; existants: number; aLocaliser: number };
  lots: { nouveaux: number; existants: number };
  erreurs: { feuille: string; ligne: number; message: string }[];
}

/** Un locataire. */
export interface Occupant {
  id: number;
  reference: string;
  typePersonne: string;
  nom: string;
  prenoms: string | null;
  nomComplet: string;
  telephone: string | null;
  telephone2: string | null;
  email: string | null;
  pieceType: string | null;
  pieceNumero: string | null;
  profession: string | null;
  employeur: string | null;
  garantNom: string | null;
  garantTelephone: string | null;
  notes: string | null;
  archiveLe: string | null;
  bauxActifs: number;
}

/** Un bail et sa situation de paiement, calculée par la base. */
export interface Bail {
  id: number;
  reference: string;
  idLot: number;
  numeroLot: string;
  etage: number;
  typeLot: string;
  idBatiment: number;
  batiment: string;
  referenceBatiment: string;
  commune: string;
  quartier: string | null;
  gestionnaire: string | null;
  proprietaire: string;
  idOccupant: number;
  referenceOccupant: string;
  occupant: string;
  telephoneOccupant: string | null;
  dateEffet: string;
  dureeMois: number;
  dateFin: string;
  loyer: number;
  charges: number;
  caution: number;
  avanceMois: number;
  jourExigibilite: number;
  statut: 'ACTIF' | 'PREAVIS' | 'RESILIE' | 'ECHU';
  datePreavis: string | null;
  dateSortie: string | null;
  motifFin: string | null;
  notes: string | null;
  /** Ce qui était exigible jusqu'à ce jour. */
  duACeJour: number;
  paye: number;
  /** Positif : reste dû ; négatif : avance. */
  solde: number;
  /** Ce qui est en retard (au-delà du délai de grâce). */
  impaye: number;
  moisRetard: number;
  retardDepuis: string | null;
}

/** Le loyer attendu d'un mois, et ce qui en est réglé. */
export interface Echeance {
  id: number;
  periode: string;
  dateExigibilite: string;
  loyer: number;
  charges: number;
  montantDu: number;
  regle: number;
  reste: number;
  statutPaiement: 'A_JOUR' | 'PARTIEL' | 'IMPAYE';
  enRetard: boolean;
}

/** Un paiement de loyer. */
export interface Encaissement {
  id: number;
  numero: string;
  idBail?: number;
  datePaiement: string;
  montant: number;
  mode: string;
  referencePaiement: string | null;
  notes: string | null;
  origine: string;
  annuleLe: string | null;
  annulePar: string | null;
  motifAnnulation: string | null;
  creeLe: string;
  creePar: string | null;
}

/** Une période soldée, en tout ou partie, par un paiement. */
export interface PeriodeReglee {
  mois: string;
  du: number;
  regle: number;
  reste: number;
}

/** La location d'un lot : son bail actif, ses échéances, ses encaissements, ses baux passés. */
export interface LocationLot {
  lot: Lot;
  bail: Bail | null;
  echeances: Echeance[];
  encaissements: Encaissement[];
  historique: Bail[];
}

/** Les chiffres des loyers d'un périmètre. */
export interface SyntheseLoyers {
  synthese: {
    mois: { periode: string; attendu: number; regle: number; encaisse: number }[];
    impaye: number;
    bauxActifs: number;
    bauxEnRetard: number;
  };
  derniersEncaissements: (Encaissement & { idBail: number; occupant: string; numeroLot: string; batiment: string; idBatiment: number })[];
}

/** Une intervention : une panne ou des travaux, et son cycle de traitement. */
export interface Intervention {
  id: number;
  reference: string;
  idBatiment: number;
  batiment: string;
  referenceBatiment: string;
  commune: string;
  quartier: string | null;
  latitude: number | null;
  longitude: number | null;
  idLot: number | null;
  numeroLot: string | null;
  idGestionnaire: number | null;
  gestionnaire: string | null;
  categorie: string;
  libelleCategorie: string;
  urgence: 'BASSE' | 'NORMALE' | 'HAUTE' | 'CRITIQUE';
  libelleUrgence: string;
  titre: string;
  description: string | null;
  statut: 'SIGNALEE' | 'AFFECTEE' | 'EN_COURS' | 'VALIDEE' | 'TERMINEE' | 'CLOTUREE' | 'ANNULEE';
  libelleStatut: string;
  signaleLe: string;
  signalePar: string | null;
  idPrestataire: number | null;
  prestataire: string | null;
  telephonePrestataire: string | null;
  datePrevue: string | null;
  affecteLe: string | null;
  affectePar: string | null;
  constat: string | null;
  devisMontant: number | null;
  debuteLe: string | null;
  accordProprietaire: boolean;
  noteValidation: string | null;
  valideLe: string | null;
  validePar: string | null;
  coutReel: number | null;
  noteFin: string | null;
  termineLe: string | null;
  terminePar: string | null;
  imputation: string | null;
  noteCloture: string | null;
  clotureLe: string | null;
  cloturePar: string | null;
  motifAnnulation: string | null;
  annuleLe: string | null;
  annulePar: string | null;
  photos: number;
  delaiJours: number | null;
  ouverte: boolean;
}

/** Une photo d'intervention. */
export interface PhotoIntervention {
  id: number;
  moment: 'AVANT' | 'APRES';
  nomOrigine: string | null;
  largeur: number;
  hauteur: number;
  taille: number;
  creeLe: string;
  creePar: string | null;
}

/** La fiche d'une intervention, avec ce que le compte peut en faire. */
export interface FicheIntervention {
  intervention: Intervention;
  photos: PhotoIntervention[];
  historique: { id: number; auteur: string; action: string; le: string }[];
  seuilAccordProprietaire: number;
  /** affecter, demarrer, valider, terminer, cloturer, annuler, photos */
  actions: string[];
}

/** Un technicien ou une entreprise de travaux. */
export interface Prestataire {
  id: number;
  nom: string;
  specialite: string | null;
  libelleSpecialite: string | null;
  telephone: string | null;
  email: string | null;
  notes: string | null;
  actif: boolean;
  enCours: number;
}

/** Les chiffres des travaux d'un périmètre. */
export interface SyntheseTravaux {
  synthese: {
    parStatut: Record<string, number>;
    urgentes: number;
    delaiMoyen: number | null;
    coutMois: number;
  };
}
