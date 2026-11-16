import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { TravauxService } from '../noyau/travaux.service';

/**
 * Une photo d'intervention. Elle ne se lit pas par une simple adresse : elle passe par l'API,
 * qui vérifie que le compte a le droit de la voir. Une silhouette grise l'attend.
 */
@Component({
  selector: 'fact-photo-securisee',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (src(); as s) {
      <img [src]="s" [alt]="alt()" loading="lazy" (load)="chargee.set(true)" [class.chargee]="chargee()">
    } @else if (echec()) {
      <span class="echec">Photo indisponible</span>
    } @else {
      <span class="attente" aria-hidden="true"></span>
    }`,
  styles: [`
    :host { display: block; position: relative; overflow: hidden; background: var(--surface-3); }
    img { display: block; width: 100%; height: 100%; object-fit: cover; opacity: 0; transition: opacity var(--standard) var(--courbe); }
    img.chargee { opacity: 1; }
    .attente { position: absolute; inset: 0; animation: pulse 1.2s ease-in-out infinite; background: var(--surface-3); }
    .echec { position: absolute; inset: 0; display: grid; place-items: center; font-size: 12px; color: var(--discret); }
  `],
})
export class PhotoSecurisee {
  private readonly travaux = inject(TravauxService);
  readonly idPhoto = input.required<number>();
  readonly alt = input('Photo de l\'intervention');
  readonly source = input<'interventions' | 'annonces' | 'batiments'>('interventions');

  protected readonly src = signal<string | null>(null);
  protected readonly echec = signal(false);
  protected readonly chargee = signal(false);

  constructor() {
    effect(() => {
      const id = this.idPhoto();
      const source = this.source();
      untracked(() => {
        this.src.set(null);
        this.chargee.set(false);
        this.travaux.image(id, source).then((s) => this.src.set(s)).catch(() => this.echec.set(true));
      });
    });
  }
}
