import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, NgZone, OnDestroy, inject, signal, viewChild } from '@angular/core';
import { gsap } from 'gsap';
import { mouvementReduit } from '../noyau/mouvement';
import { Logo } from './logo';

const CLE = 'fact-site.intro-vue';

/** Vrai si le préchargeur doit jouer : première page de la session, animations permises. */
function doitJouer(): boolean {
  if (mouvementReduit()) return false;
  try {
    if (sessionStorage.getItem(CLE)) return false;
    sessionStorage.setItem(CLE, '1');
    return true;
  } catch {
    // Stockage refusé : on ne rejoue pas l'intro à chaque page faute de mémoire.
    return false;
  }
}

/**
 * Le préchargeur cinématique, à la première visite de la session (2,2 s environ) : l'immeuble
 * se dresse et ses fenêtres s'allument en cascade, un reflet chromé balaie « FACT »,
 * « IMMOBILIER » glisse dans son bandeau, puis le logo se loge dans l'en-tête et le rideau
 * s'ouvre. La page se construit dessous pendant ce temps : rien n'est retardé. Un clic ou une
 * touche l'écourte ; il n'a aucun élément focalisable et la page reste annoncée « occupée »
 * (aria-busy) le temps de l'animation seulement.
 */
@Component({
  selector: 'site-prechargeur',
  imports: [Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'passer()' },
  template: `
    @if (actif()) {
      <div #rideau class="rideau" aria-hidden="true" (click)="passer()">
        <div class="fond"></div>
        <div #scene class="scene"><site-logo variante="sombre" largeur="100%" /></div>
      </div>
    }`,
  styles: [`
    .rideau { position: fixed; inset: 0; z-index: 5000; display: grid; place-items: center; cursor: pointer; }
    .fond { position: absolute; inset: 0; background: radial-gradient(120% 90% at 50% 40%, #1a2f55 0%, #0f1b30 55%, #0b1222 100%); }
    .scene { position: relative; width: min(72vw, 380px); transform-origin: 50% 50%; }
  `],
})
export class Prechargeur implements AfterViewInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly rideau = viewChild<ElementRef<HTMLElement>>('rideau');
  private readonly scene = viewChild<ElementRef<HTMLElement>>('scene');
  protected readonly actif = signal(doitJouer());
  private tl: gsap.core.Timeline | null = null;

  ngAfterViewInit(): void {
    const rideau = this.rideau()?.nativeElement;
    const scene = this.scene()?.nativeElement;
    if (!rideau || !scene) return;
    const main = document.getElementById('contenu');
    main?.setAttribute('aria-busy', 'true');
    this.zone.runOutsideAngular(() => {
      const q = gsap.utils.selector(scene);
      gsap.set(q('.immeuble'), { scaleY: 0, transformOrigin: '50% 100%', transformBox: 'fill-box' });
      gsap.set(q('.fenetre'), { opacity: 0 });
      gsap.set(q('.lettres'), { opacity: 0, y: 12 });
      gsap.set(q('.bandeau'), { opacity: 0, x: -40 });
      this.tl = gsap.timeline({ onComplete: () => this.zone.run(() => this.terminer()) })
        .to(q('.immeuble'), { scaleY: 1, duration: 0.55, ease: 'back.out(1.4)' })
        .to(q('.fenetre'), { opacity: 1, duration: 0.12, stagger: { each: 0.012, from: 'end' } }, '-=0.15')
        .to(q('.lettres'), { opacity: 1, y: 0, duration: 0.4, ease: 'power3.out' }, '-=0.35')
        .fromTo(q('.reflet'), { x: 0 }, { x: 330, duration: 0.7, ease: 'power2.inOut' }, '-=0.1')
        .to(q('.bandeau'), { opacity: 1, x: 0, duration: 0.45, ease: 'power3.out' }, '-=0.5')
        // Le logo glisse et rétrécit jusqu'à celui de l'en-tête, pendant que le rideau s'ouvre.
        .to(scene, { x: () => this.ecart(scene).x, y: () => this.ecart(scene).y, scale: () => this.ecart(scene).s,
          duration: 0.6, ease: 'power3.inOut' }, '+=0.15')
        .to(rideau.querySelector('.fond'), { yPercent: -100, duration: 0.6, ease: 'power3.inOut' }, '<0.1')
        .to(scene, { opacity: 0, duration: 0.2 }, '-=0.15');
    });
  }

  ngOnDestroy(): void {
    this.tl?.kill();
    document.getElementById('contenu')?.removeAttribute('aria-busy');
  }

  /** Clic ou touche : on saute à la fin, sans attendre. */
  protected passer(): void {
    if (!this.actif() || !this.tl) return;
    this.tl.timeScale(4);
  }

  /** L'écart entre le logo du préchargeur et celui de l'en-tête (calculé au dernier moment). */
  private ecart(scene: HTMLElement): { x: number; y: number; s: number } {
    const cible = document.querySelector('.barre .marque site-logo') as HTMLElement | null;
    if (!cible) return { x: 0, y: -40, s: 0.8 };
    const a = scene.getBoundingClientRect(), b = cible.getBoundingClientRect();
    return { x: b.left + b.width / 2 - (a.left + a.width / 2), y: b.top + b.height / 2 - (a.top + a.height / 2), s: b.width / a.width };
  }

  private terminer(): void {
    this.actif.set(false);
    document.getElementById('contenu')?.removeAttribute('aria-busy');
  }
}
