import { Component } from '@angular/core';
import { Icone } from '../../composants/icone';

/**
 * Le guide d'utilisation illustré, dans l'application, le temps des essais et de la formation.
 * À RETIRER À LA LIVRAISON : ce fichier, la route « guide », l'entrée « Guide » du menu et le
 * dossier public/guide (il contient les comptes d'essai).
 */
@Component({
  selector: 'fact-guide',
  imports: [Icone],
  template: `
    <div class="gd">
      <header>
        <h1>Guide d'utilisation</h1>
        <a class="btn petit" href="guide-essais/index.html" target="_blank" rel="noopener"><fact-icone nom="droite" [taille]="16" /> Ouvrir dans un onglet</a>
      </header>
      <iframe src="guide-essais/index.html" title="Guide d'utilisation de FACT Immobilier"></iframe>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .gd { display: grid; grid-template-rows: auto 1fr; gap: 10px; height: calc(100dvh - 24px); padding: 12px 16px 16px; }
    header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
    h1 { font-size: clamp(20px, 4vw, 26px); color: var(--marque); }
    iframe { width: 100%; height: 100%; border: 1px solid var(--filet); border-radius: var(--rayon); background: #fff; }
    @media (max-width: 767px) { .gd { height: calc(100dvh - var(--barre-basse, 64px) - 12px); } }
  `],
})
export class Guide {}
