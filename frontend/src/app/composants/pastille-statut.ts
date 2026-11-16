import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Ton, tonStatut } from '../noyau/statuts';

/**
 * Pastille de statut (§ 5.4) : un libellé coloré — Occupé, Vacant, En retard…
 * La couleur vient du code du statut, sauf si un ton est imposé.
 *   <fact-pastille-statut code="OCCUPE" libelle="Occupé" />
 */
@Component({
  selector: 'fact-pastille-statut',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="pastille" [class]="couleur()"><span class="point" aria-hidden="true"></span>{{ libelle() || code() }}</span>`,
  styles: [`
    .point { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
  `],
})
export class PastilleStatut {
  readonly code = input<string | null>(null);
  readonly libelle = input<string>('');
  readonly ton = input<Ton | null>(null);

  protected readonly couleur = computed(() => this.ton() ?? tonStatut(this.code()));
}
