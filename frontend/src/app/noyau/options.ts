import type { OptionChoix } from '../composants/choix';
import type { Batiment, Commune, Lot, Occupant, Prestataire, Proprietaire, Quartier } from './modeles';
import type { Gestionnaire } from './patrimoine.service';

/** Les fabriques d'options des listes de choix, partagées par tous les écrans. */
export const OPTIONS = {
  commune: (c: Commune): OptionChoix => ({ valeur: c.id, libelle: c.libelle }),
  quartier: (q: Quartier): OptionChoix => ({ valeur: q.id, libelle: q.libelle }),
  batiment: (b: Batiment): OptionChoix => ({ valeur: b.id, libelle: b.nom, detail: b.quartier ? `${b.quartier}, ${b.commune}` : b.commune }),
  lot: (l: Lot): OptionChoix => ({ valeur: l.id, libelle: 'Lot ' + l.numero, detail: l.libelleType }),
  proprietaire: (p: Proprietaire): OptionChoix => ({ valeur: p.id, libelle: p.nomComplet, detail: p.reference }),
  /** Le contexte écarte un compte : le titulaire, de la liste des suppléants. */
  gestionnaire: (g: Gestionnaire, sauf: number | null): OptionChoix | null =>
    g.id === sauf ? null : { valeur: g.id, libelle: g.nom, detail: g.fonction },
  occupant: (o: Occupant): OptionChoix => ({ valeur: o.id, libelle: o.nomComplet, detail: o.telephone ?? o.reference }),
  prestataire: (p: Prestataire): OptionChoix => ({
    valeur: p.id, libelle: p.nom,
    detail: [p.libelleSpecialite, p.enCours ? p.enCours + ' en cours' : null].filter(Boolean).join(' · ') || null,
  }),
  /** Le contexte est le propriétaire ouvert : un compte déjà lié à un autre n'est pas proposé. */
  compte: (c: { id: number; nom: string; identifiant: string; idProprietaire: number | null }, idProprietaire: number | null): OptionChoix | null =>
    c.idProprietaire && c.idProprietaire !== idProprietaire ? null : { valeur: c.id, libelle: c.nom, detail: c.identifiant },
};
