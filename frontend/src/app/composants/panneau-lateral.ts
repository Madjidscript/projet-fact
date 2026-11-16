import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Icone } from './icone';

/**
 * Panneau latéral : filtres, liste, fiche ou formulaire.
 *  - posé sur une carte (par défaut) : il se place dans le cadre de la carte sans la masquer ;
 *  - sur une page ([page]="true") : il se fixe à l'écran, avec un voile sombre derrière lui ;
 *    un clic sur le voile le referme.
 * À droite sur ordinateur et tablette, glissant depuis le bas sur téléphone. Échap le referme.
 */
@Component({
  selector: 'fact-panneau-lateral',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'ouvert() && fermer.emit()' },
  template: `
    @if (ouvert()) {
      @if (page()) { <div class="voile" (click)="fermer.emit()" aria-hidden="true"></div> }
      <aside class="panneau" [class.page]="page()" role="complementary" [attr.aria-label]="titre()">
        <header>
          <span class="poignee" aria-hidden="true"></span>
          <h2>{{ titre() }}</h2>
          <button type="button" class="btn discret icone-seule" (click)="fermer.emit()" aria-label="Fermer le panneau">
            <fact-icone nom="fermer" />
          </button>
        </header>
        <div class="contenu"><ng-content /></div>
      </aside>
    }`,
  styles: [`
    :host { display: contents; }
    .voile { position: fixed; inset: 0; z-index: 1400; background: var(--voile); animation: fondu var(--standard) var(--courbe); }
    .panneau { position: absolute; z-index: 500; display: flex; flex-direction: column; background: var(--surface);
               border: 1px solid var(--filet); overflow: hidden;
               /* Téléphone : panneau glissant depuis le bas */
               left: 0; right: 0; bottom: 0; max-height: 70%; border-radius: 20px 20px 0 0;
               animation: depuis-bas var(--standard) var(--courbe); }
    .panneau.page { position: fixed; z-index: 1500; max-height: 88dvh; }
    header { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 8px; padding: 6px 8px 6px 18px;
             border-bottom: 1px solid var(--filet); position: relative; flex: none; }
    .poignee { position: absolute; top: 6px; left: 50%; width: 40px; height: 4px; margin-left: -20px; border-radius: 2px; background: var(--filet-fort); }
    h2 { font-size: 16px; padding-top: 6px; }
    .contenu { padding: 16px 18px; overflow-y: auto; flex: 1; }
    @keyframes depuis-bas { from { transform: translateY(100%); } }
    @keyframes depuis-droite { from { transform: translateX(100%); } }
    @media (min-width: 768px) {
      .panneau { left: auto; top: 12px; right: 12px; bottom: 12px; width: min(380px, 46%); max-height: none;
                 border-radius: var(--rayon); animation-name: depuis-droite; }
      .panneau.page { width: min(520px, calc(100vw - 96px)); max-height: none; }
      .poignee { display: none; }
      h2 { padding-top: 0; }
    }
  `],
})
export class PanneauLateral {
  readonly ouvert = input(true);
  readonly titre = input.required<string>();
  /** Vrai : le panneau se fixe à l'écran, au-dessus de la page, avec son voile. */
  readonly page = input(false);
  readonly fermer = output<void>();
}
