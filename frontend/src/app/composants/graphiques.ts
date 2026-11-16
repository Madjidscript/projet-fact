import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Ton, couleurTon } from '../noyau/statuts';

/** Une part d'un graphique : son libellé, sa valeur et sa couleur d'état. */
export interface Part {
  libelle: string;
  valeur: number;
  ton?: Ton;
  /** Le texte affiché à la place de la valeur brute (« 1 250 000 FCFA »). */
  texte?: string;
}

const entier = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });

/**
 * Anneau d'occupation (§ 5.4, « Graphiques ») : les parts d'un tout, la principale au centre.
 * L'anneau se remplit à l'ouverture (§ 6.3). Une légende écrite double toujours la couleur.
 */
@Component({
  selector: 'fact-anneau',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure>
      <div class="rond">
        <svg viewBox="0 0 120 120" role="img" [attr.aria-label]="resume()">
          <circle cx="60" cy="60" r="48" class="piste" />
          @for (a of arcs(); track a.libelle) {
            <circle cx="60" cy="60" r="48" class="arc" pathLength="100" [style.stroke]="a.couleur"
                    [attr.stroke-dasharray]="a.longueur + ' ' + (100 - a.longueur)" [attr.stroke-dashoffset]="-a.depart" />
          }
        </svg>
        <div class="centre">
          <strong>{{ centre() }}</strong>
          <span>{{ sousCentre() }}</span>
        </div>
      </div>
      <figcaption>
        @for (a of arcs(); track a.libelle) {
          <span class="cle"><i [style.background]="a.couleur"></i>{{ a.libelle }} <b class="donnee">{{ a.texte }}</b></span>
        }
      </figcaption>
    </figure>`,
  styles: [`
    :host { display: block; }
    figure { margin: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 18px; }
    .rond { position: relative; width: 132px; height: 132px; flex: none; }
    svg { width: 100%; height: 100%; transform: rotate(-90deg); }
    circle { fill: none; stroke-width: 13; }
    .piste { stroke: var(--surface-3); }
    .arc { animation: remplit 800ms var(--courbe) both; }
    @keyframes remplit { from { stroke-dasharray: 0 100; } }
    .centre { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; }
    .centre strong { font: 700 24px/1 var(--titre); color: var(--marque); }
    .centre span { font-size: 12px; color: var(--discret); margin-top: 3px; }
    figcaption { display: grid; gap: 7px; font-size: 14px; }
    .cle { display: flex; align-items: center; gap: 8px; color: var(--encre-2); }
    .cle i { width: 11px; height: 11px; border-radius: 3px; flex: none; }
    .cle b { margin-left: auto; padding-left: 14px; color: var(--encre); font-weight: 500; }
  `],
})
export class Anneau {
  readonly parts = input.required<Part[]>();
  /** Le texte du centre ; par défaut, le pourcentage de la première part. */
  readonly titre = input('');
  readonly sousTitre = input('');

  private readonly total = computed(() => this.parts().reduce((t, p) => t + Math.max(0, p.valeur), 0));

  protected readonly arcs = computed(() => {
    const total = this.total() || 1;
    let depart = 0;
    return this.parts().map((p) => {
      const longueur = (Math.max(0, p.valeur) / total) * 100;
      const arc = { libelle: p.libelle, longueur, depart, couleur: couleurTon(p.ton ?? 'neutre'), texte: p.texte ?? entier.format(p.valeur) };
      depart += longueur;
      return arc;
    });
  });

  protected readonly centre = computed(() => {
    if (this.titre()) return this.titre();
    const premiere = this.parts()[0];
    return premiere && this.total() ? Math.round((premiere.valeur / this.total()) * 100) + ' %' : '-';
  });

  protected readonly sousCentre = computed(() => this.sousTitre() || this.parts()[0]?.libelle || '');

  protected readonly resume = computed(() => this.arcs().map((a) => `${a.libelle} : ${a.texte}`).join(', '));
}

/**
 * Barres horizontales (§ 5.4, « Graphiques ») : un classement — vacance par commune,
 * impayés par gestionnaire. Les barres se remplissent à l'ouverture (§ 6.3).
 */
@Component({
  selector: 'fact-barres',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul [attr.aria-label]="titre()">
      @for (b of barres(); track b.libelle; let i = $index) {
        <li>
          <span class="nom">{{ b.libelle }}</span>
          <span class="piste"><span class="barre" [style.width.%]="b.part" [style.background]="b.couleur" [style.animation-delay.ms]="i * 60"></span></span>
          <span class="chiffre donnee">{{ b.texte }}</span>
        </li>
      }
    </ul>`,
  styles: [`
    :host { display: block; }
    ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    li { display: grid; grid-template-columns: minmax(90px, 30%) 1fr auto; align-items: center; gap: 12px; font-size: 14px; }
    .nom { color: var(--encre-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .piste { height: 10px; border-radius: 5px; background: var(--surface-3); overflow: hidden; }
    .barre { display: block; height: 100%; border-radius: 5px; transform-origin: left; animation: pousse 700ms var(--courbe) both; }
    @keyframes pousse { from { transform: scaleX(0); } }
    .chiffre { font-size: 13px; min-width: 3ch; text-align: right; }
  `],
})
export class Barres {
  readonly parts = input.required<Part[]>();
  readonly titre = input('Classement');

  protected readonly barres = computed(() => {
    const max = Math.max(1, ...this.parts().map((p) => p.valeur));
    return this.parts().map((p) => ({
      libelle: p.libelle,
      part: (Math.max(0, p.valeur) / max) * 100,
      couleur: couleurTon(p.ton ?? 'info'),
      texte: p.texte ?? entier.format(p.valeur),
    }));
  });
}
