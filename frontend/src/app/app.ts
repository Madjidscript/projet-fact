import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AvisService } from './noyau/avis.service';

@Component({
  selector: 'fact-root',
  imports: [RouterOutlet],
  template: `
    <router-outlet />
    <div class="avis-pile" aria-live="polite">
      @for (a of avis.liste(); track a.id) {
        <div class="avis" [class]="a.genre" role="status">
          <span class="pastille-avis" aria-hidden="true">{{
            a.genre === 'succes' ? '✓' : a.genre === 'erreur' ? '!' : 'i'
          }}</span>
          <span class="texte">{{ a.texte }}</span>
          <button type="button" (click)="avis.retirer(a.id)" aria-label="Fermer">×</button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      /* Notifications : glissement depuis le bas, disparition après 4 s.
         Elles se posent à gauche, hors de la zone des panneaux (à droite) et de leurs boutons ;
         les clics les traversent, sauf sur leur bouton de fermeture. */
      .avis-pile {
        position: fixed;
        left: 16px;
        right: 16px;
        bottom: calc(var(--barre-basse) + env(safe-area-inset-bottom) + 12px);
        pointer-events: none;
        z-index: 2000;
        display: grid;
        gap: 10px;
      }
      .avis {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 12px 12px 12px 14px;
        border-radius: var(--rayon);
        background: var(--nuit);
        color: #fff;
        font-size: 14px;
        border: 1px solid rgba(255, 255, 255, 0.08);
        animation: entree var(--standard) var(--courbe);
        pointer-events: none;
      }
      .pastille-avis {
        flex: none;
        width: 26px;
        height: 26px;
        border-radius: 50%;
        display: grid;
        place-items: center;
        font: 700 13px var(--titre);
        background: var(--ocre);
        color: #fff;
      }
      .avis.succes .pastille-avis {
        background: var(--bon);
      }
      .avis.erreur {
        background: #7a1f1f;
      }
      .avis.erreur .pastille-avis {
        background: #ff9b9b;
        color: #7a1f1f;
      }
      .texte {
        flex: 1;
        line-height: 1.4;
      }
      .avis button {
        pointer-events: auto;
        min-height: auto;
        background: none;
        border: 0;
        color: inherit;
        font-size: 20px;
        line-height: 1;
        opacity: 0.6;
        padding: 2px 6px;
        border-radius: 6px;
      }
      .avis button:hover {
        opacity: 1;
        background: rgba(255, 255, 255, 0.1);
      }
      @keyframes entree {
        from {
          transform: translateY(14px) scale(0.98);
          opacity: 0;
        }
      }
      @media (min-width: 768px) {
        .avis-pile {
          left: calc(72px + 20px);
          right: auto;
          bottom: 20px;
          width: min(420px, calc(100vw - 140px));
        }
      }
      @media (min-width: 1200px) {
        .avis-pile {
          left: calc(var(--menu-largeur) + 20px);
        }
      }
    `,
  ],
})
export class App {
  protected readonly avis = inject(AvisService);
}
