import { ChangeDetectionStrategy, Component, ElementRef, computed, input, signal, viewChild } from '@angular/core';
import { gsap } from 'gsap';
import { Photo, photo } from '../noyau/api';
import { COURBE, mouvementReduit } from '../noyau/mouvement';
import { Icone } from './icone';

/**
 * Galerie d'un bien (§ 3.3) : grande photo et vignettes ; ouverture en plein écran par
 * agrandissement ; glissement entre photos au clic, au clavier et par balayage sur téléphone.
 */
@Component({
  selector: 'site-galerie',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'clavier($event)' },
  template: `
    <div class="scene">
      <button type="button" class="principale" (click)="ouvrir()" aria-label="Voir les photos en plein écran">
        <img #image [src]="adresse(courante())" [alt]="legende()" [style.view-transition-name]="'photo-' + idBien()" decoding="async">
        <span class="agrandir"><fact-icone nom="calques" [taille]="16" /> {{ index() + 1 }} / {{ photos().length }}</span>
      </button>
      @if (photos().length > 1) {
        <button type="button" class="fleche gauche" (click)="aller(-1)" aria-label="Photo précédente"><fact-icone nom="gauche" /></button>
        <button type="button" class="fleche droite" (click)="aller(1)" aria-label="Photo suivante"><fact-icone nom="droite" /></button>
      }
    </div>
    @if (photos().length > 1) {
      <div class="miniatures" role="tablist" aria-label="Photos">
        @for (p of photos(); track p.id; let i = $index) {
          <button type="button" role="tab" [attr.aria-selected]="i === index()" [class.active]="i === index()" (click)="choisir(i)" [attr.aria-label]="'Photo ' + (i + 1)">
            <img [src]="adresse(p)" alt="" loading="lazy">
          </button>
        }
      </div>
    }

    @if (plein()) {
      <div class="plein" #plein role="dialog" aria-modal="true" aria-label="Photos en plein écran"
           (pointerdown)="debut($event)" (pointerup)="fin($event)">
        <img #grande [src]="adresse(courante())" [alt]="legende()" draggable="false">
        <button type="button" class="fermer" (click)="fermer()" aria-label="Fermer"><fact-icone nom="fermer" [taille]="26" /></button>
        @if (photos().length > 1) {
          <button type="button" class="fleche gauche" (click)="aller(-1)" aria-label="Photo précédente"><fact-icone nom="gauche" [taille]="26" /></button>
          <button type="button" class="fleche droite" (click)="aller(1)" aria-label="Photo suivante"><fact-icone nom="droite" [taille]="26" /></button>
        }
        <p class="compteur">{{ index() + 1 }} / {{ photos().length }}@if (courante().legende) { · {{ courante().legende }} }</p>
      </div>
    }`,
  styles: [`
    :host { display: block; }
    .scene { position: relative; border-radius: 22px; overflow: hidden; background: var(--surface-3); }
    .principale { display: block; width: 100%; padding: 0; border: 0; background: none; cursor: zoom-in; }
    .principale img { display: block; width: 100%; aspect-ratio: 16 / 10; object-fit: cover; }
    .agrandir { position: absolute; right: 12px; bottom: 12px; display: inline-flex; align-items: center; gap: 6px; padding: 6px 11px;
                border-radius: 999px; background: rgba(11, 18, 34, .72); color: #fff; font-size: 13px; }
    .fleche { position: absolute; top: 50%; width: 44px; height: 44px; margin-top: -22px; display: grid; place-items: center; border: 0;
              border-radius: 50%; background: rgba(255,255,255,.9); color: var(--nuit); box-shadow: 0 6px 16px -8px rgba(0,0,0,.5); }
    .fleche.gauche { left: 12px; } .fleche.droite { right: 12px; }
    .miniatures { display: flex; gap: 8px; margin-top: 10px; overflow-x: auto; padding-bottom: 4px; }
    .miniatures button { flex: none; width: 88px; height: 62px; padding: 0; border: 2px solid transparent; border-radius: 10px; overflow: hidden;
                         background: none; opacity: .7; transition: opacity 150ms, border-color 150ms; }
    .miniatures button.active { border-color: var(--ocre); opacity: 1; }
    .miniatures img { width: 100%; height: 100%; object-fit: cover; }
    .plein { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; background: rgba(8,14,24,.96); touch-action: pan-y; }
    .plein img { max-width: 100vw; max-height: 86vh; object-fit: contain; user-select: none; }
    .plein .fleche { background: rgba(255,255,255,.14); color: #fff; }
    .fermer { position: absolute; right: 14px; top: 14px; width: 48px; height: 48px; display: grid; place-items: center; border: 0; border-radius: 50%;
              background: rgba(255,255,255,.14); color: #fff; }
    .compteur { position: absolute; bottom: 18px; left: 0; right: 0; margin: 0; text-align: center; color: #dce4e4; font-size: 14px; }
  `],
})
export class Galerie {
  readonly photos = input.required<Photo[]>();
  readonly titre = input('');
  readonly idBien = input(0);
  protected readonly index = signal(0);
  protected readonly plein = signal(false);
  private readonly image = viewChild<ElementRef<HTMLImageElement>>('image');
  private readonly grande = viewChild<ElementRef<HTMLImageElement>>('grande');
  private departX: number | null = null;

  protected readonly courante = computed(() => this.photos()[Math.min(this.index(), this.photos().length - 1)]);
  protected readonly legende = computed(() => this.courante().legende ?? `${this.titre()}, photo ${this.index() + 1}`);
  protected adresse(p: Photo): string {
    return photo(p.id);
  }

  protected choisir(i: number): void {
    if (i === this.index()) return;
    this.glisser(i > this.index() ? 1 : -1, i);
  }

  protected aller(sens: 1 | -1): void {
    const n = this.photos().length;
    this.glisser(sens, (this.index() + sens + n) % n);
  }

  protected ouvrir(): void {
    this.plein.set(true);
    document.body.style.overflow = 'hidden';
    if (mouvementReduit()) return;
    // Ouverture par agrandissement depuis la photo de la page.
    setTimeout(() => {
      const g = this.grande()?.nativeElement;
      const r = this.image()?.nativeElement.getBoundingClientRect();
      if (!g || !r) return;
      const cible = g.getBoundingClientRect();
      gsap.from(g, { x: r.left + r.width / 2 - (cible.left + cible.width / 2), y: r.top + r.height / 2 - (cible.top + cible.height / 2),
        scale: r.width / Math.max(cible.width, 1), duration: 0.45, ease: COURBE });
    });
  }

  protected fermer(): void {
    this.plein.set(false);
    document.body.style.overflow = '';
  }

  protected clavier(e: KeyboardEvent): void {
    if (!this.plein()) return;
    if (e.key === 'Escape') this.fermer();
    else if (e.key === 'ArrowRight') this.aller(1);
    else if (e.key === 'ArrowLeft') this.aller(-1);
  }

  protected debut(e: PointerEvent): void {
    this.departX = e.clientX;
  }

  protected fin(e: PointerEvent): void {
    if (this.departX === null) return;
    const dx = e.clientX - this.departX;
    this.departX = null;
    if (Math.abs(dx) > 50) this.aller(dx < 0 ? 1 : -1);
  }

  private glisser(sens: 1 | -1, i: number): void {
    const el = (this.plein() ? this.grande() : this.image())?.nativeElement;
    if (!el || mouvementReduit()) {
      this.index.set(i);
      return;
    }
    gsap.to(el, { x: -40 * sens, opacity: 0, duration: 0.18, ease: 'power2.in', onComplete: () => {
      this.index.set(i);
      gsap.fromTo(el, { x: 40 * sens, opacity: 0 }, { x: 0, opacity: 1, duration: 0.3, ease: COURBE });
    } });
  }
}
