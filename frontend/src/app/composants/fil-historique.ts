import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { dateHeure } from '../noyau/format';
import { Ton } from '../noyau/statuts';

/** Un événement daté, avec son auteur (ligne de app.journal). */
export interface Evenement {
  le: string;
  auteur: string;
  action: string;
  detail?: string;
  ton?: Ton;
}

/**
 * Fil d'historique (§ 5.4) : les événements datés d'une fiche, le plus récent en haut.
 * « Chaque création ou modification est tracée (auteur, date, ancienne et nouvelle valeur) » (§ 8).
 */
@Component({
  selector: 'fact-fil-historique',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol>
      @for (e of evenements(); track $index; let i = $index) {
        <li class="cascade" [style.--rang]="i">
          <span class="point" [class]="e.ton ?? 'neutre'" aria-hidden="true"></span>
          <div>
            <p class="action">{{ e.action }}</p>
            @if (e.detail) { <p class="detail">{{ e.detail }}</p> }
            <p class="qui"><span class="donnee">{{ f(e.le) }}</span> · {{ e.auteur }}</p>
          </div>
        </li>
      } @empty {
        <li class="aucun">Aucun événement enregistré.</li>
      }
    </ol>`,
  styles: [`
    :host { display: block; }
    ol { list-style: none; margin: 0; padding: 0; }
    li { position: relative; display: grid; grid-template-columns: 14px 1fr; gap: 12px; padding-bottom: 18px; }
    li:not(:last-child)::before { content: ''; position: absolute; left: 6px; top: 16px; bottom: 0; width: 2px; background: var(--filet); }
    .point { width: 14px; height: 14px; margin-top: 4px; border-radius: 50%; border: 3px solid var(--surface); box-shadow: 0 0 0 1px var(--filet-fort); background: var(--discret); }
    .point.bon { background: var(--bon); }
    .point.attention { background: var(--attention); }
    .point.critique { background: var(--critique); }
    .point.info { background: var(--info); }
    .point.accent { background: var(--ocre); }
    p { margin: 0; }
    .action { font-weight: 500; }
    .detail { font-size: 14px; color: var(--encre-2); margin-top: 2px; }
    .qui { font-size: 12.5px; color: var(--discret); margin-top: 3px; }
    .aucun { display: block; color: var(--discret); font-style: italic; }
  `],
})
export class FilHistorique {
  readonly evenements = input.required<Evenement[]>();
  protected readonly f = dateHeure;
}
