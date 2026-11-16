import { ChangeDetectionStrategy, Component, Directive, ElementRef, OnDestroy, afterNextRender, effect, inject, input, signal, untracked } from '@angular/core';

/** Vrai si la personne a demandé la réduction des animations sur son appareil (§ 6.1). */
export function animationsReduites(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Apparition à l'entrée dans l'écran (§ 6.2, « Sections ») : montée de 24 px avec fondu,
 * jouée une seule fois par visite de page (§ 6.1). {@code factApparition} reçoit le rang de
 * l'élément dans sa liste, pour la cascade de 60 ms, 8 éléments au plus.
 *   <article factApparition>…</article>      <li [factApparition]="i">…</li>
 */
@Directive({
  selector: '[factApparition]',
  host: { '[class.a-venir]': '!vu()', '[class.venu]': 'vu()', '[style.--rang]': 'rang() || 0' },
})
export class Apparition implements OnDestroy {
  readonly rang = input<number | ''>('', { alias: 'factApparition' });
  protected readonly vu = signal(false);
  private observateur?: IntersectionObserver;

  constructor() {
    const hote = inject(ElementRef<HTMLElement>);
    afterNextRender(() => {
      if (typeof IntersectionObserver !== 'function') {
        this.vu.set(true);
        return;
      }
      this.observateur = new IntersectionObserver((entrees) => {
        if (entrees.some((e) => e.isIntersecting)) {
          this.vu.set(true);
          this.observateur?.disconnect();
        }
      }, { rootMargin: '0px 0px -8% 0px' });
      this.observateur.observe(hote.nativeElement);
    });
  }

  ngOnDestroy(): void {
    this.observateur?.disconnect();
  }
}

/**
 * Compteur qui défile de zéro à la valeur réelle (§ 6.2 « Chiffres clés », § 6.3 « Indicateurs »),
 * à l'entrée dans l'écran. Avec la réduction des animations, la valeur s'affiche d'emblée.
 * Les lecteurs d'écran lisent la valeur finale, pas le défilement.
 *   <fact-compteur [valeur]="375000" suffixe=" FCFA" />
 */
@Component({
  selector: 'fact-compteur',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span aria-hidden="true">{{ texte() }}</span><span class="sr">{{ f(valeur()) }}{{ suffixe() }}</span>`,
  styles: [`:host { font-variant-numeric: tabular-nums; }`],
})
export class Compteur implements OnDestroy {
  readonly valeur = input.required<number>();
  readonly suffixe = input('');
  readonly decimales = input(0);
  readonly duree = input(900);

  private readonly hote = inject(ElementRef<HTMLElement>);
  private readonly affiche = signal(0);
  private readonly visible = signal(false);
  private observateur?: IntersectionObserver;
  private image = 0;

  protected texte(): string {
    return this.f(this.affiche()) + this.suffixe();
  }

  protected f(n: number): string {
    return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: this.decimales(), maximumFractionDigits: this.decimales() }).format(n);
  }

  constructor() {
    afterNextRender(() => {
      if (typeof IntersectionObserver !== 'function') {
        this.visible.set(true);
        return;
      }
      this.observateur = new IntersectionObserver((entrees) => {
        if (entrees.some((e) => e.isIntersecting)) {
          this.visible.set(true);
          this.observateur?.disconnect();
        }
      });
      this.observateur.observe(this.hote.nativeElement);
    });

    // Rejoue le défilement quand la valeur change (filtre, nouvelle période).
    effect(() => {
      const cible = this.valeur();
      if (!this.visible()) return;
      cancelAnimationFrame(this.image);
      if (animationsReduites()) {
        this.affiche.set(cible);
        return;
      }
      // La valeur affichée est lue sans être suivie : sinon chaque image relancerait l'animation,
      // qui n'atteindrait jamais la valeur réelle.
      const depart = untracked(() => this.affiche()), debut = performance.now(), duree = untracked(() => this.duree());
      const pas = (t: number) => {
        // L'horodatage de l'image peut précéder légèrement le départ : on borne entre 0 et 1.
        const p = Math.min(1, Math.max(0, (t - debut) / duree));
        const doux = 1 - Math.pow(1 - p, 3);   // départ franc, arrivée douce
        this.affiche.set(depart + (cible - depart) * doux);
        if (p < 1) this.image = requestAnimationFrame(pas);
      };
      this.image = requestAnimationFrame(pas);
    });
  }

  ngOnDestroy(): void {
    this.observateur?.disconnect();
    cancelAnimationFrame(this.image);
  }
}
