import { Batiment } from './modeles';
import { Ton } from './statuts';

/** La couleur d'un bâtiment sur la carte, et le libellé de légende qui l'explique. */
export interface Teinte {
  couleur: string;
  legende: string;
  /** Bâtiment en alerte : son marqueur pulse lentement. */
  alerte?: boolean;
}

/** Les couleurs d'état fixes : elles portent la lecture de la carte. */
export const COULEURS: Record<Ton, string> = {
  bon: '#2e9e6b',
  attention: '#e08a1e',
  critique: '#d64545',
  info: '#3a78c2',
  accent: '#c8862b',
  neutre: '#8a93a0',
};

/** Une couleur par gestionnaire, lisibles sur le plan comme sur le satellite. */
const PALETTE = ['#3a78c2', '#c8862b', '#7b5ea7', '#1f9c9c', '#c2507a', '#5b8c2a', '#a5682a', '#4a5fc1', '#b84f3a', '#2f7d5b'];

export type CodeThematique = 'occupation' | 'impayes' | 'travaux' | 'gestionnaire' | 'validation';

/**
 * Occupation : vert, tous les lots occupés ; orange, au moins un lot vacant ; rouge, plus de
 * la moitié des lots vacants ; gris, aucun lot géré.
 */
export function teinteOccupation(b: Batiment): Teinte {
  if (!b.lots) return { couleur: COULEURS.neutre, legende: 'Aucun lot géré' };
  const libres = b.lotsVacants;
  if (libres === 0) return { couleur: COULEURS.bon, legende: 'Tous les lots occupés' };
  if (libres > b.lots / 2) return { couleur: COULEURS.critique, legende: 'Plus de la moitié des lots vacants', alerte: true };
  return { couleur: COULEURS.attention, legende: 'Au moins un lot vacant' };
}

/**
 * Impayés : vert, loyers à jour ; orange, un mois de retard sur au moins un lot ; rouge, deux
 * mois ou plus. Les seuils viennent des réglages de l'administrateur.
 */
export function teinteImpayes(b: Batiment, leger: number, grave: number): Teinte {
  if (!b.bauxActifs) return { couleur: COULEURS.neutre, legende: 'Aucun bail suivi' };
  if (b.moisRetardMax >= grave) return { couleur: COULEURS.critique, legende: grave + ' mois de retard ou plus', alerte: true };
  if (b.moisRetardMax >= leger) return { couleur: COULEURS.attention, legende: leger + ' mois de retard' };
  return { couleur: COULEURS.bon, legende: 'Loyers à jour' };
}

/** Travaux : bâtiments ayant au moins une intervention ouverte ; en rouge, une urgence. */
export function teinteTravaux(b: Batiment): Teinte {
  if (b.interventionsUrgentes) return { couleur: COULEURS.critique, legende: 'Intervention urgente', alerte: true };
  if (b.interventionsOuvertes) return { couleur: COULEURS.info, legende: 'Intervention en cours' };
  return { couleur: COULEURS.neutre, legende: 'Aucune intervention' };
}

export function teinteValidation(b: Batiment): Teinte {
  return b.validation === 'A_VALIDER'
    ? { couleur: COULEURS.attention, legende: 'Fiche à valider' }
    : { couleur: COULEURS.bon, legende: 'Fiche validée' };
}

/** La couleur de chaque gestionnaire, attribuée dans l'ordre alphabétique pour rester stable. */
export function couleursGestionnaires(batiments: Batiment[]): Map<number, { nom: string; couleur: string }> {
  const noms = new Map<number, string>();
  for (const b of batiments) if (b.idGestionnaire !== null && b.gestionnaire) noms.set(b.idGestionnaire, b.gestionnaire);
  const tries = [...noms.entries()].sort((a, b) => a[1].localeCompare(b[1], 'fr'));
  return new Map(tries.map(([id, nom], i) => [id, { nom, couleur: PALETTE[i % PALETTE.length] }]));
}

export function teinteGestionnaire(b: Batiment, couleurs: Map<number, { nom: string; couleur: string }>): Teinte {
  const g = b.idGestionnaire === null ? undefined : couleurs.get(b.idGestionnaire);
  return g ? { couleur: g.couleur, legende: g.nom } : { couleur: COULEURS.neutre, legende: 'Sans gestionnaire', alerte: true };
}
