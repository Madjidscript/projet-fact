import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Ton } from '../noyau/statuts';

/**
 * Carte indicateur (§ 5.4) : valeur, libellé, évolution et mini-courbe.
 *   <fact-carte-indicateur libelle="Taux d'occupation" valeur="92,5 %" [evolution]="1.8" [serie]="[88, 90, 89, 92]" />
 * {@code evolution} est en points ou en pourcentage, au choix de l'appelant ({@code unite}).
 * {@code hausseBonne} = false pour les indicateurs où monter est une mauvaise nouvelle (impayés).
 */
@Component({
  selector: 'fact-carte-indicateur',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="carte">
      <p class="libelle">{{ libelle() }}</p>
      <p class="valeur" [class]="ton()">{{ valeur() }}</p>
      <div class="bas">
        @if (evolution() !== null) {
          <span class="evolution" [class]="sens()">
            <span aria-hidden="true">{{ evolution()! > 0 ? '▲' : evolution()! < 0 ? '▼' : '=' }}</span>
            {{ texteEvolution() }}
          </span>
        }
        @if (note()) { <span class="note">{{ note() }}</span> }
      </div>
      @if (trace()) {
        <svg class="courbe" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">
          <path [attr.d]="aire()" class="aire" />
          <path [attr.d]="trace()" class="trait" pathLength="1" />
        </svg>
      }
    </article>`,
  styles: [`
    :host { display: block; }
    .carte { position: relative; overflow: hidden; padding-bottom: 46px; height: 100%; }
    .libelle { margin: 0; font-size: 13px; color: var(--encre-2); }
    .valeur { margin: 6px 0 4px; font: 700 28px/1.1 var(--titre); color: var(--marque); }
    .valeur.bon { color: var(--bon-texte); }
    .valeur.attention { color: var(--attention-texte); }
    .valeur.critique { color: var(--critique-texte); }
    .bas { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; }
    .evolution { font-weight: 600; font-family: var(--donnee); }
    .evolution.bien { color: var(--bon-texte); }
    .evolution.mal { color: var(--critique-texte); }
    .evolution.stable { color: var(--discret); }
    .note { color: var(--discret); }
    .courbe { position: absolute; left: 0; right: 0; bottom: 0; width: 100%; height: 38px; color: var(--marque); }
    .trait { fill: none; stroke: currentColor; stroke-width: 1.6; vector-effect: non-scaling-stroke;
             stroke-dasharray: 1; animation: trace 900ms var(--courbe) both; }
    .aire { fill: currentColor; opacity: .08; }
    /* Les courbes se tracent à l'ouverture du tableau de bord (§ 6.3). */
    @keyframes trace { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
  `],
})
export class CarteIndicateur {
  readonly libelle = input.required<string>();
  readonly valeur = input.required<string>();
  readonly evolution = input<number | null>(null);
  readonly unite = input(' %');
  readonly hausseBonne = input(true);
  readonly note = input('');
  readonly serie = input<number[]>([]);
  readonly ton = input<Ton | ''>('');

  protected readonly sens = computed(() => {
    const e = this.evolution();
    if (e === null || e === 0) return 'stable';
    return e > 0 === this.hausseBonne() ? 'bien' : 'mal';
  });

  protected readonly texteEvolution = computed(() => {
    const e = Math.abs(this.evolution() ?? 0);
    return new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(e) + this.unite();
  });

  private readonly points = computed(() => {
    const s = this.serie();
    if (s.length < 2) return [];
    const min = Math.min(...s), max = Math.max(...s);
    const etendue = max - min || 1;
    return s.map((v, i) => [(i / (s.length - 1)) * 100, 28 - ((v - min) / etendue) * 24] as const);
  });

  protected readonly trace = computed(() => {
    const p = this.points();
    return p.length ? p.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ') : '';
  });

  protected readonly aire = computed(() => (this.trace() ? `${this.trace()} L100 32 L0 32 Z` : ''));
}
