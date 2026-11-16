import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icone } from './icone';

/**
 * Progression d'un formulaire par étapes (§ 5.4) : recensement d'un bâtiment, création d'un
 * bail, demande de devis. L'étape courante est annoncée aux lecteurs d'écran.
 *   <fact-etapes [etapes]="['Position', 'Identité', 'Lots', 'Photos']" [courante]="1" />
 */
@Component({
  selector: 'fact-etapes',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol [attr.aria-label]="'Étape ' + (courante() + 1) + ' sur ' + etapes().length">
      @for (e of etapes(); track e; let i = $index) {
        <li [class.faite]="i < courante()" [class.courante]="i === courante()" [attr.aria-current]="i === courante() ? 'step' : null">
          <span class="rond">
            @if (i < courante()) { <fact-icone nom="coche" [taille]="15" /> } @else { {{ i + 1 }} }
          </span>
          <span class="nom">{{ e }}</span>
        </li>
      }
    </ol>`,
  styles: [`
    :host { display: block; }
    ol { list-style: none; margin: 0; padding: 0; display: flex; }
    li { flex: 1; position: relative; display: grid; justify-items: center; gap: 6px; font-size: 13px; color: var(--discret); text-align: center; }
    li:not(:first-child)::before { content: ''; position: absolute; top: 15px; right: 50%; width: 100%; height: 2px; background: var(--filet-fort);
                                   transition: background var(--standard) var(--courbe); }
    li.faite::before, li.courante::before { background: var(--bon); }
    .rond { position: relative; z-index: 1; width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center;
            font: 600 13px var(--titre); background: var(--surface); border: 2px solid var(--filet-fort);
            transition: background var(--standard) var(--courbe), border-color var(--standard) var(--courbe); }
    li.faite .rond { background: var(--bon); border-color: var(--bon); color: #fff; }
    li.courante .rond { border-color: var(--marque); color: var(--marque); }
    li.courante { color: var(--encre); font-weight: 600; }
    li.faite { color: var(--encre-2); }
    @media (max-width: 767px) { .nom { display: none; } li.courante .nom { display: block; } }
  `],
})
export class Etapes {
  readonly etapes = input.required<string[]>();
  readonly courante = input(0);
}

/**
 * Indicateur de synchronisation (§ 5.4, terrain) : en ligne, hors ligne, éléments en attente
 * d'envoi. L'icône tourne pendant l'envoi (§ 6.3). L'état réel du réseau et la file d'attente
 * viendront de la PWA à l'étape 8 ; le composant ne fait qu'afficher ce qu'on lui donne.
 */
@Component({
  selector: 'fact-synchro',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="synchro" [class]="etat()" role="status">
      <fact-icone [nom]="etat() === 'hors-ligne' ? 'hors_ligne' : etat() === 'a-jour' ? 'coche' : 'synchro'" [taille]="16" />
      @switch (etat()) {
        @case ('hors-ligne') { Hors ligne@if (enAttente()) { · {{ enAttente() }} en attente } }
        @case ('envoi') { Envoi de {{ enAttente() }} élément{{ enAttente() > 1 ? 's' : '' }}… }
        @case ('en-attente') { {{ enAttente() }} en attente d'envoi }
        @default { À jour }
      }
    </span>`,
  styles: [`
    .synchro { display: inline-flex; align-items: center; gap: 7px; padding: 5px 12px; border-radius: var(--rayon-pastille); font-size: 13px; font-weight: 500; }
    .a-jour { background: var(--bon-clair); color: var(--bon-texte); }
    .envoi { background: var(--info-clair); color: var(--info-texte); }
    .envoi fact-icone { animation: tourne 1.1s linear infinite; }
    .en-attente { background: var(--attention-clair); color: var(--attention-texte); }
    .hors-ligne { background: var(--surface-3); color: var(--encre-2); }
  `],
})
export class Synchro {
  readonly etat = input<'a-jour' | 'envoi' | 'en-attente' | 'hors-ligne'>('a-jour');
  readonly enAttente = input(0);
}
