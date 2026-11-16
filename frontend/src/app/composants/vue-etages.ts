import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { tonStatut } from '../noyau/statuts';

/** Un lot tel que la vue par étage le dessine. */
export interface LotEtage {
  id: number;
  numero: string;
  etage: number;
  type: string;
  statut: string;
  libelleStatut: string;
}

/**
 * Vue par étage (§ 5.4) : la grille des lots d'un bâtiment, colorés par statut, le dernier
 * étage en haut comme sur la façade. Les étages se déploient un à un ; un lot survolé se
 * soulève (§ 6.3). Chaque lot est un bouton : la couleur est doublée du libellé du statut.
 */
@Component({
  selector: 'fact-vue-etages',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="facade">
      @for (e of etages(); track e.niveau; let i = $index) {
        <div class="etage cascade" [style.--rang]="i">
          <span class="niveau donnee">{{ e.niveau === 0 ? 'RDC' : 'R+' + e.niveau }}</span>
          <div class="lots">
            @for (l of e.lots; track l.id) {
              <button type="button" class="lot" [class]="ton(l.statut)" [class.choisi]="l.id === choisi()"
                      (click)="lot.emit(l)" [attr.aria-label]="'Lot ' + l.numero + ', ' + l.type + ', ' + l.libelleStatut">
                <strong class="donnee">{{ l.numero }}</strong>
                <span>{{ l.type }}</span>
                <em>{{ l.libelleStatut }}</em>
              </button>
            }
          </div>
        </div>
      }
    </div>`,
  styles: [`
    :host { display: block; }
    .facade { display: grid; gap: 8px; }
    .etage { display: grid; grid-template-columns: 44px 1fr; gap: 10px; align-items: stretch; }
    .niveau { display: grid; place-items: center; font-size: 12px; color: var(--discret); background: var(--surface-3); border-radius: 8px; }
    .lots { display: grid; grid-template-columns: repeat(auto-fill, minmax(104px, 1fr)); gap: 8px; }
    .lot { display: grid; gap: 1px; padding: 9px 10px; min-height: 64px; text-align: left; border-radius: var(--rayon-2);
           border: 1px solid transparent; border-left-width: 4px; background: var(--surface-3); color: var(--encre);
           transition: transform var(--courte) var(--courbe), border-color var(--courte) var(--courbe); }
    .lot:hover { transform: translateY(-3px); }
    .lot.choisi { outline: 3px solid var(--ocre); outline-offset: 1px; }
    .lot strong { font-size: 13px; }
    .lot span { font-size: 12px; color: var(--encre-2); }
    .lot em { font-style: normal; font-size: 12px; font-weight: 600; }
    .lot.bon { background: var(--bon-clair); border-left-color: var(--bon); } .lot.bon em { color: var(--bon-texte); }
    .lot.attention { background: var(--attention-clair); border-left-color: var(--attention); } .lot.attention em { color: var(--attention-texte); }
    .lot.critique { background: var(--critique-clair); border-left-color: var(--critique); } .lot.critique em { color: var(--critique-texte); }
    .lot.info { background: var(--info-clair); border-left-color: var(--info); } .lot.info em { color: var(--info-texte); }
    .lot.accent { background: var(--ocre-clair); border-left-color: var(--ocre); } .lot.accent em { color: var(--ocre-texte); }
    .lot.neutre { border-left-color: var(--discret); } .lot.neutre em { color: var(--encre-2); }
  `],
})
export class VueEtages {
  readonly lots = input.required<LotEtage[]>();
  readonly choisi = input<number | null>(null);
  readonly lot = output<LotEtage>();

  protected readonly etages = computed(() => {
    const parNiveau = new Map<number, LotEtage[]>();
    for (const l of this.lots()) {
      parNiveau.set(l.etage, [...(parNiveau.get(l.etage) ?? []), l]);
    }
    return [...parNiveau.entries()]
      .sort(([a], [b]) => b - a)
      .map(([niveau, lots]) => ({ niveau, lots: lots.sort((a, b) => a.numero.localeCompare(b.numero, 'fr', { numeric: true })) }));
  });

  protected ton(statut: string): string { return tonStatut(statut); }
}
