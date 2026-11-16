import { Component, ElementRef, NgZone, OnDestroy, computed, effect, inject, input, viewChild } from '@angular/core';
import { RouterLink, TitleStrategy } from '@angular/router';
import { gsap } from 'gsap';
import { Icone } from '../composants/icone';
import { METIERS } from '../noyau/donnees';
import { MOUVEMENT, mouvementReduit } from '../noyau/mouvement';
import { Referencement } from '../noyau/referencement';

/**
 * La page d'un métier (§ 3.6) : son texte propre, les étapes de la prestation (une ligne qui
 * se remplit au défilement relie les étapes) et un appel à l'action.
 */
@Component({
  selector: 'site-metier',
  imports: [RouterLink, Icone, ...MOUVEMENT],
  template: `
    @if (metier(); as m) {
      <section class="bandeau">
        <div class="conteneur entete" siteRevele>
          <div>
            <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <a routerLink="/services">Nos services</a> <span>/</span> <span>{{ m.titre }}</span></nav>
            <h1>{{ m.titre }}</h1>
            <p>{{ m.accroche }}</p>
          </div>
          <span class="grand-picto"><fact-icone [nom]="m.icone" [taille]="96" [dessin]="true" /></span>
        </div>
      </section>

      <section class="section conteneur deux">
        <div siteRevele>
          <p class="surtitre">Ce que nous faisons pour vous</p>
          @for (t of m.texte; track $index) { <p class="paragraphe">{{ t }}</p> }
        </div>
        <div siteRevele="0.1">
          <p class="surtitre">Comment ça se passe</p>
          <div class="etapes" #etapes>
            <span class="rail" aria-hidden="true"><span class="remplissage"></span></span>
            <ol>
            @for (e of m.etapes; track e.titre; let i = $index) {
              <li><span class="pastille-num">{{ i + 1 }}</span><div><h3>{{ e.titre }}</h3><p>{{ e.texte }}</p></div></li>
            }
            </ol>
          </div>
        </div>
      </section>

      <section class="conteneur">
        <div class="appel" siteRevele>
          <div>
            <h2>Parlons de votre projet</h2>
            <p>Un conseiller vous rappelle pour comprendre votre besoin et vous proposer la démarche la plus simple.</p>
          </div>
          <a class="btn grand" [routerLink]="m.appel.lien" [queryParams]="m.appel.lien === '/devis' ? { metier: m.code } : {}" siteAimant>{{ m.appel.libelle }} <fact-icone nom="droite" [taille]="18" /></a>
        </div>
      </section>

      <section class="section conteneur autres">
        <h2 class="titre-section" siteTitre>Nos autres métiers</h2>
        <ul siteCascade>
          @for (a of autres(); track a.slug) {
            <li><a [routerLink]="['/services', a.slug]"><fact-icone [nom]="a.icone" [taille]="26" /><span><strong>{{ a.titre }}</strong><small>{{ a.accroche }}</small></span></a></li>
          }
        </ul>
      </section>
    } @else {
      <section class="bandeau"><div class="conteneur"><h1>Service introuvable</h1><p><a class="btn accent" routerLink="/services">Voir nos services</a></p></div></section>
    }`,
  styles: [`
    :host { display: block; }
    .entete { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
    .grand-picto { display: none; color: var(--ocre); }
    .deux { display: grid; grid-template-columns: minmax(0, 1fr); gap: 40px; }
    .paragraphe { margin: 0 0 16px; color: var(--encre-2); font-size: 17px; line-height: 1.7; }
    .etapes { position: relative; margin-top: 6px; }
    .etapes ol { display: grid; gap: 22px; margin: 0; padding: 0; list-style: none; }
    .etapes li { position: relative; display: flex; gap: 16px; }
    .rail { position: absolute; left: 19px; top: 20px; bottom: 20px; width: 2px; background: var(--filet); }
    .remplissage { position: absolute; inset: 0; background: var(--ocre); transform-origin: top; }
    .pastille-num { position: relative; z-index: 1; flex: none; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 50%;
                    background: var(--nuit); color: #fff; font: 700 15px var(--titre); box-shadow: 0 0 0 5px var(--fond); }
    .etapes h3 { margin: 8px 0 4px; font-size: 17px; color: var(--nuit); }
    .etapes p { margin: 0; color: var(--encre-2); line-height: 1.55; }
    .autres ul { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; margin: 0; padding: 0; list-style: none; }
    .autres a { display: flex; gap: 14px; align-items: flex-start; height: 100%; padding: 18px; border: 1px solid var(--filet); border-radius: 18px;
                background: var(--surface); color: var(--nuit); text-decoration: none; transition: transform 300ms var(--courbe), box-shadow 300ms var(--courbe); }
    .autres a:hover { transform: translateY(-4px); box-shadow: 0 20px 36px -28px rgba(11, 18, 34, .5); }
    .autres span { display: grid; gap: 4px; }
    .autres small { color: var(--encre-2); font-size: 14px; }
    @media (min-width: 900px) {
      .grand-picto { display: block; }
      .deux { grid-template-columns: 1.1fr 1fr; gap: 72px; }
      .autres ul { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    }
  `],
})
export class MetierPage implements OnDestroy {
  private readonly referencement = inject(TitleStrategy) as Referencement;
  private readonly zone = inject(NgZone);
  private readonly etapes = viewChild<ElementRef<HTMLElement>>('etapes');
  readonly slug = input.required<string>();
  protected readonly metier = computed(() => METIERS.find((m) => m.slug === this.slug()) ?? null);
  protected readonly autres = computed(() => METIERS.filter((m) => m.slug !== this.slug()));
  private contexte: gsap.Context | null = null;

  constructor() {
    effect(() => {
      const m = this.metier();
      if (m) this.referencement.poser(`${m.titre} à Abidjan — FACT Immobilier`, `${m.accroche} ${m.texte[0]}`);
    });
    // Le rail des étapes se remplit au fil du défilement.
    effect(() => {
      const ol = this.etapes()?.nativeElement;
      this.contexte?.revert();
      if (!ol || mouvementReduit()) return;
      this.zone.runOutsideAngular(() => {
        this.contexte = gsap.context(() => {
          gsap.fromTo('.remplissage', { scaleY: 0 }, { scaleY: 1, ease: 'none', scrollTrigger: { trigger: ol, start: 'top 75%', end: 'bottom 60%', scrub: true } });
          gsap.from('li', { x: -18, opacity: 0, duration: 0.5, stagger: 0.12, ease: 'power3.out', scrollTrigger: { trigger: ol, start: 'top 85%', once: true } });
        }, ol);
      });
    });
  }

  ngOnDestroy(): void {
    this.contexte?.revert();
  }
}
