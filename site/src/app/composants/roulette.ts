import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Un nombre qui se met à jour en roulant, chiffre par chiffre (nombre de résultats) : chaque
 * chiffre est une colonne 0-9 qui glisse jusqu'à la bonne valeur. Seule la transformation est
 * animée ; avec la réduction des animations, le chiffre change sans glisser.
 */
@Component({
  selector: 'site-roulette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.aria-label]': 'valeur()', role: 'text' },
  template: `
    @for (c of chiffres(); track $index) {
      <span class="fenetre" aria-hidden="true"><span class="bande" [style.transform]="'translateY(' + -c * 10 + '%)'">
        @for (d of dix; track d) { <span>{{ d }}</span> }
      </span></span>
    }`,
  styles: [`
    :host { display: inline-flex; font-variant-numeric: tabular-nums; vertical-align: bottom; }
    .fenetre { display: inline-block; height: 1.15em; overflow: hidden; line-height: 1.15; }
    .bande { display: flex; flex-direction: column; transition: transform 600ms cubic-bezier(.2, 0, 0, 1); }
    .bande span { height: 1.15em; }
    @media (prefers-reduced-motion: reduce) { .bande { transition: none; } }
  `],
})
export class Roulette {
  readonly valeur = input.required<number>();
  protected readonly dix = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
  protected readonly chiffres = computed(() => String(Math.max(0, Math.round(this.valeur()))).split('').map(Number));
}
