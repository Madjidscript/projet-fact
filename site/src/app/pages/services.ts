import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../composants/icone';
import { METIERS } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

/** « Nos services » : les quatre métiers de l'agence, chacun menant à sa page (§ 3.6). */
@Component({
  selector: 'site-services',
  imports: [RouterLink, Icone, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>Nos services</span></nav>
        <h1>Nos services</h1>
        <p>Quatre métiers complémentaires pour louer, vendre, construire ou entretenir votre bien, avec une seule équipe.</p>
      </div>
    </section>
    <section class="section conteneur">
      <ul class="services">
        @for (m of metiers; track m.slug; let i = $index) {
          <li class="service" [class.inverse]="i % 2 === 1" siteRevele>
            <div class="illustration" [style.--teinte]="teintes[i]">
              <span><fact-icone [nom]="m.icone" [taille]="88" [dessin]="true" /></span>
              <span class="numero">0{{ i + 1 }}</span>
            </div>
            <div class="texte">
              <p class="surtitre">{{ m.court }}</p>
              <h2 class="titre-section" siteTitre>{{ m.titre }}</h2>
              <p class="chapeau">{{ m.accroche }}</p>
              <p class="detail">{{ m.texte[0] }}</p>
              <a class="btn principal grand" [routerLink]="['/services', m.slug]">En savoir plus <fact-icone nom="droite" [taille]="18" /></a>
            </div>
          </li>
        }
      </ul>
    </section>`,
  styles: [`
    :host { display: block; }
    .services { display: grid; gap: 56px; margin: 0; padding: 0; list-style: none; }
    .service { display: grid; gap: 24px; align-items: center; }
    .illustration { position: relative; aspect-ratio: 4 / 3; display: grid; place-items: center; border-radius: 26px; overflow: hidden; color: #fff;
                    background: radial-gradient(120% 120% at 20% 10%, var(--teinte), #0f1b30); }
    .illustration::after { content: ''; position: absolute; width: 60%; aspect-ratio: 1; right: -15%; bottom: -25%; border-radius: 50%;
                           background: radial-gradient(circle, rgba(26, 99, 216, .45), transparent 70%); }
    .numero { position: absolute; left: 22px; top: 16px; font: 700 42px var(--titre); color: rgba(255,255,255,.25); }
    .detail { margin: 14px 0 22px; color: var(--encre-2); line-height: 1.65; }
    @media (min-width: 900px) {
      .service { grid-template-columns: 1fr 1fr; gap: 56px; }
      .service.inverse .illustration { order: 2; }
    }
  `],
})
export class Services {
  protected readonly metiers = METIERS;
  protected readonly teintes = ['#2f5585', '#2f5585', '#1a2f55', '#2f5585'];
}
