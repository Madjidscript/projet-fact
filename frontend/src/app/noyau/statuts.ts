/**
 * La couleur de chaque statut. Les couleurs d'état portent la lecture de la carte
 * (§ 5.2) : vert ce qui va bien, orange ce qui demande attention, rouge ce qui presse.
 */
export type Ton = 'bon' | 'attention' | 'critique' | 'info' | 'accent' | 'neutre';

const TONS: Record<string, Ton> = {
  // Statuts d'un lot (§ 4.2)
  VACANT: 'attention',
  A_LOUER: 'accent',
  A_VENDRE: 'accent',
  RESERVE: 'info',
  OCCUPE: 'bon',
  EN_PREAVIS: 'attention',
  EN_TRAVAUX: 'info',
  HORS_GESTION: 'neutre',
  // Paiement
  A_JOUR: 'bon',
  PARTIEL: 'attention',
  IMPAYE: 'critique',
  EN_RETARD: 'critique',
  // Baux
  ACTIF: 'bon',
  PREAVIS: 'attention',
  RESILIE: 'neutre',
  ECHU: 'critique',
  // Interventions (§ 4.5)
  SIGNALEE: 'attention',
  AFFECTEE: 'info',
  EN_COURS: 'info',
  VALIDEE: 'info',
  TERMINEE: 'bon',
  CLOTUREE: 'neutre',
  ANNULEE: 'neutre',
  // Urgence
  BASSE: 'neutre',
  NORMALE: 'info',
  HAUTE: 'attention',
  CRITIQUE: 'critique',
  // Demandes du site (§ 3.8)
  NOUVELLE: 'attention',
  PRISE_EN_CHARGE: 'info',
  RENDEZ_VOUS: 'info',
  CONCLUE: 'bon',
  SANS_SUITE: 'neutre',
  // Fiches venues du terrain (§ 4.6)
  A_VALIDER: 'attention',
};

export function tonStatut(code: string | null | undefined): Ton {
  return (code && TONS[code]) || 'neutre';
}

/** La couleur pleine d'un ton, pour les marqueurs de carte et les graphiques. */
export function couleurTon(ton: Ton): string {
  switch (ton) {
    case 'bon': return 'var(--bon)';
    case 'attention': return 'var(--attention)';
    case 'critique': return 'var(--critique)';
    case 'info': return 'var(--info)';
    case 'accent': return 'var(--ocre)';
    default: return 'var(--discret)';
  }
}
