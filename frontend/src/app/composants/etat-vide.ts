import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Icone } from './icone';

/**
 * État vide (§ 5.4) : une illustration, une phrase qui dit pourquoi c'est vide, et l'action
 * proposée — projetée dans le composant :
 *   <fact-etat-vide icone="batiment" titre="Aucun bâtiment" message="…"><button class="btn principal">Ajouter</button></fact-etat-vide>
 */
@Component({
  selector: 'fact-etat-vide',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="vide">
      <span class="rond"><fact-icone [nom]="icone()" [taille]="30" /></span>
      <h3>{{ titre() }}</h3>
      @if (message()) { <p>{{ message() }}</p> }
      <div class="actions"><ng-content /></div>
    </div>`,
  styles: [`
    :host { display: block; }
    .vide { display: grid; justify-items: center; gap: 8px; padding: 36px 20px; text-align: center;
            border: 1px dashed var(--filet-fort); border-radius: var(--rayon); background: var(--surface-2); }
    .rond { width: 64px; height: 64px; border-radius: 50%; display: grid; place-items: center;
            background: var(--nuit-clair); color: var(--marque); margin-bottom: 4px; }
    h3 { font-size: 16px; }
    p { margin: 0; max-width: 44ch; color: var(--encre-2); font-size: 14px; }
    .actions { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
    .actions:empty { display: none; }
  `],
})
export class EtatVide {
  readonly icone = input('info');
  readonly titre = input.required<string>();
  readonly message = input('');
}

/**
 * Squelette (§ 5.4) : des silhouettes grises pendant le chargement, à la forme de ce qui
 * va venir — lignes de texte, carte ou vignette.
 */
@Component({
  selector: 'fact-squelette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="squelette" [class]="forme()" aria-hidden="true">
      @if (forme() === 'vignette') { <span class="image"></span> }
      @for (l of rangs(); track l) { <span class="ligne" [style.width.%]="l"></span> }
    </div>
    <span class="sr" role="status">Chargement…</span>`,
  styles: [`
    :host { display: block; }
    .squelette { display: grid; gap: 10px; }
    .squelette.carte, .squelette.vignette { padding: 18px; border: 1px solid var(--filet); border-radius: var(--rayon); background: var(--surface); }
    .ligne, .image { display: block; border-radius: 7px; background: var(--surface-3); animation: pulse 1.2s ease-in-out infinite; }
    .ligne { height: 13px; }
    .image { height: 130px; border-radius: var(--rayon-2); margin-bottom: 4px; }
  `],
})
export class Squelette {
  readonly forme = input<'lignes' | 'carte' | 'vignette'>('lignes');
  readonly lignes = input(3);

  protected rangs(): number[] {
    const largeurs = [92, 78, 55, 84, 66];
    return Array.from({ length: this.lignes() }, (_, i) => largeurs[i % largeurs.length]);
  }
}
