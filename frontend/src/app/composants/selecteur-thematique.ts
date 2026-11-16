import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Ton, couleurTon } from '../noyau/statuts';

/** Une thématique de couleur de la carte et sa légende (§ 4.1). */
export interface Thematique {
  code: string;
  libelle: string;
  legende: { libelle: string; ton: Ton }[];
}

/** Les thématiques à légende fixe du § 4.1. Gestionnaire et propriétaire, dont la légende
    dépend des données, s'y ajouteront à l'étape 6. */
export const THEMATIQUES: Thematique[] = [
  { code: 'occupation', libelle: 'Occupation', legende: [
    { libelle: '100 % occupé', ton: 'bon' },
    { libelle: 'Au moins un lot vacant', ton: 'attention' },
    { libelle: 'Plus de la moitié des lots vacants', ton: 'critique' },
    { libelle: 'Hors gestion', ton: 'neutre' },
  ] },
  { code: 'impayes', libelle: 'Impayés', legende: [
    { libelle: 'À jour', ton: 'bon' },
    { libelle: 'Un mois de retard sur au moins un lot', ton: 'attention' },
    { libelle: 'Deux mois ou plus', ton: 'critique' },
  ] },
  { code: 'travaux', libelle: 'Travaux', legende: [
    { libelle: 'Au moins une intervention ouverte', ton: 'info' },
    { libelle: 'Aucune intervention en cours', ton: 'neutre' },
  ] },
  { code: 'echeances', libelle: 'Échéances', legende: [
    { libelle: 'Bail arrivant à terme dans les 60 jours', ton: 'attention' },
    { libelle: 'Aucune échéance proche', ton: 'neutre' },
  ] },
];

/**
 * Sélecteur de thématique (§ 5.4) : des boutons segmentés et la légende associée. Changer
 * de thématique recolore la carte ; la légende se met à jour en fondu (§ 6.3).
 */
@Component({
  selector: 'fact-selecteur-thematique',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="segments" role="radiogroup" aria-label="Thématique de couleur de la carte">
      @for (t of thematiques(); track t.code) {
        <button type="button" role="radio" [attr.aria-checked]="t.code === choisie()" [class.actif]="t.code === choisie()"
                (click)="choix.emit(t.code)">{{ t.libelle }}</button>
      }
    </div>
    @if (courante(); as c) {
      <ul class="legende" [attr.aria-label]="'Légende : ' + c.libelle">
        @for (l of c.legende; track l.libelle) {
          <li><i [style.background]="couleur(l.ton)"></i>{{ l.libelle }}</li>
        }
      </ul>
    }`,
  styles: [`
    :host { display: block; }
    .segments { display: flex; gap: 4px; padding: 4px; border-radius: 12px; background: var(--surface-3); overflow-x: auto; }
    button { flex: 1; min-height: 38px; padding: 0 14px; border: 0; border-radius: 9px; background: transparent; color: var(--encre-2);
             font-weight: 500; white-space: nowrap; transition: background var(--courte) var(--courbe), color var(--courte) var(--courbe); }
    button:hover { color: var(--encre); }
    button.actif { background: var(--surface); color: var(--marque); font-weight: 600; box-shadow: 0 0 0 1px var(--filet); }
    .legende { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 7px; font-size: 13.5px; color: var(--encre-2);
               animation: fondu var(--standard) var(--courbe); }
    .legende li { display: flex; align-items: center; gap: 9px; }
    .legende i { width: 13px; height: 13px; border-radius: 50%; flex: none; box-shadow: 0 0 0 2px var(--surface), 0 0 0 3px var(--filet-fort); }
  `],
})
export class SelecteurThematique {
  readonly thematiques = input<Thematique[]>(THEMATIQUES);
  readonly choisie = input.required<string>();
  readonly choix = output<string>();

  protected readonly courante = computed(() => this.thematiques().find((t) => t.code === this.choisie()));
  protected couleur(ton: Ton): string { return couleurTon(ton); }
}
