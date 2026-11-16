import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { AGENCE } from '../noyau/donnees';
import { Icone } from './icone';

/**
 * L'accusé de réception d'une demande réellement enregistrée : sa référence et ce qui va
 * se passer. Le bouton WhatsApp reste proposé pour qui préfère écrire tout de suite.
 */
@Component({
  selector: 'site-accuse',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="envoye" role="status" aria-live="polite">
      <svg class="coche-dessinee" viewBox="0 0 52 52" fill="none" stroke-width="3" stroke-linecap="round" aria-hidden="true">
        <circle cx="26" cy="26" r="23" opacity=".25" />
        <path d="m15 27 7.500 7.500L37 19" />
      </svg>
      <h2>Demande {{ reference() }} enregistrée</h2>
      <p>{{ message() || 'Un conseiller vous rappelle rapidement.' }}</p>
      <p class="petit">Gardez cette référence : elle permet à l’agence de retrouver votre demande.</p>
      <div class="actions">
        <a class="btn grand whatsapp-btn" [href]="lien()" target="_blank" rel="noopener"><fact-icone nom="whatsapp" [taille]="20" /> Écrire sur WhatsApp</a>
        <button type="button" class="btn grand" (click)="nouvelle.emit()">Nouvelle demande</button>
      </div>
    </div>`,
  styles: [`
    h2 { margin: 0; font: 700 24px var(--titre); color: var(--nuit); }
    p { max-width: 46ch; margin: 0; color: var(--encre-2); line-height: 1.6; }
    .petit { font-size: 13.5px; color: var(--discret); }
    .actions { display: flex; flex-wrap: wrap; justify-content: center; gap: 10px; margin-top: 6px; }
    .whatsapp-btn { background: #25d366; border-color: #25d366; color: #fff; }
  `],
})
export class Accuse {
  readonly reference = input.required<string>();
  readonly message = input<string | null>(null);
  readonly nouvelle = output<void>();
  protected readonly lien = computed(() =>
    `${AGENCE.whatsapp}?text=${encodeURIComponent(`Bonjour FACT Immobilier, je reviens vers vous au sujet de ma demande ${this.reference()}.`)}`);
}

/** Téléphone ivoirien à 10 chiffres ou international (§ 3.3). */
export const TELEPHONE = /^(\+?\d{1,3}[ .]?)?(\d{2}[ .]?){4}\d{2}$/;
export const EMAIL = /^[^@\s]+@[^@\s]+\.[a-zA-Z]{2,}$/;

/** Le champ piège : invisible pour les visiteurs, rempli seulement par les robots. */
export const STYLE_PIEGE = 'position:absolute;width:1px;height:1px;margin:-1px;padding:0;border:0;overflow:hidden;clip:rect(0 0 0 0);clip-path:inset(50%);white-space:nowrap';
