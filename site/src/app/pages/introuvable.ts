import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MOUVEMENT } from '../noyau/mouvement';

/** Page introuvable : on propose les chemins les plus utiles. */
@Component({
  selector: 'site-introuvable',
  imports: [RouterLink, ...MOUVEMENT],
  template: `
    <section class="bandeau plein">
      <div class="conteneur" siteRevele>
        <p class="code">404</p>
        <h1>Cette page n’existe pas ou plus</h1>
        <p>Le bien a peut-être été loué, ou l’adresse a changé.</p>
        <div class="actions">
          <a class="btn accent grand" routerLink="/">Retour à l’accueil</a>
          <a class="btn grand clair" routerLink="/louer">Voir les biens à louer</a>
        </div>
      </div>
    </section>`,
  styles: [`
    .plein { min-height: 70vh; display: flex; align-items: center; }
    .code { margin: 0 0 6px !important; font: 700 clamp(64px, 14vw, 120px) / 1 var(--titre); color: var(--ocre) !important; }
    .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 24px; }
    .clair { background: transparent; color: #fff; border-color: rgba(255,255,255,.4); }
  `],
})
export class Introuvable {}
