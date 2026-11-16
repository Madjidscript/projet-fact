import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input } from '@angular/core';
import { gsap } from 'gsap';
import { Vignette } from '../noyau/api';
import { FavorisService } from '../noyau/favoris.service';
import { mouvementReduit } from '../noyau/mouvement';

/** Le bouton favori : le cœur se remplit avec un petit rebond (§ 6.2). */
@Component({
  selector: 'site-coeur',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button type="button" class="coeur" [class.actif]="actif()" (click)="basculer($event)"
            [attr.aria-pressed]="actif()" [attr.aria-label]="actif() ? 'Retirer des favoris' : 'Ajouter aux favoris'"
            [attr.title]="actif() ? 'Retirer des favoris' : 'Ajouter aux favoris'">
      <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
        <path d="M12 20s-7.500-4.400-7.500-10A4.300 4.300 0 0 1 12 7.300 4.300 4.300 0 0 1 19.500 10c0 5.600-7.500 10-7.500 10Z"
              stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
      </svg>
      @if (libelle()) { <span>{{ actif() ? 'Dans vos favoris' : 'Favori' }}</span> }
    </button>`,
  styles: [`
    .coeur { display: inline-flex; align-items: center; gap: 8px; min-width: 42px; height: 42px; padding: 0 10px; justify-content: center;
             border: 0; border-radius: var(--rayon-pastille); background: rgba(255,255,255,.94); color: var(--nuit); cursor: pointer;
             box-shadow: 0 6px 16px -8px rgba(11, 18, 34, .5); font-weight: 600; }
    path { fill: transparent; transition: fill 200ms var(--courbe); }
    .actif { color: #d64545; }
    .actif path { fill: #d64545; }
  `],
})
export class Coeur {
  private readonly favoris = inject(FavorisService);
  private readonly el = inject(ElementRef<HTMLElement>);
  readonly bien = input.required<Vignette>();
  /** Vrai : le libellé s'affiche à côté du cœur (fiche du bien). */
  readonly libelle = input(false);
  protected readonly actif = computed(() => this.favoris.liste().some((v) => v.slug === this.bien().slug));

  protected basculer(e: Event): void {
    e.preventDefault();
    e.stopPropagation();
    const ajoute = this.favoris.basculer(this.bien());
    if (ajoute && !mouvementReduit()) {
      gsap.fromTo(this.el.nativeElement.querySelector('svg'), { scale: 0.6 }, { scale: 1, duration: 0.6, ease: 'elastic.out(1.2, 0.45)' });
    }
  }
}
