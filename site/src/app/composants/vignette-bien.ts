import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Vignette, photo } from '../noyau/api';
import { prix } from '../noyau/donnees';
import { Nombre } from '../noyau/mouvement';
import { Coeur } from './coeur';
import { Icone } from './icone';

const TEINTES: [string, string][] = [['#1a2f55', '#1a63d8'], ['#2f5585', '#2e9e6b'], ['#0f1b30', '#3a78c2'], ['#2f5585', '#1a63d8']];

/**
 * La vignette d'un bien (§ 3.2) : photo, prix au format unique, type, quartier, chambres,
 * salles d'eau, surface. Au survol, la carte se soulève et la photo zoome (§ 6.2) ; elle se
 * met en évidence quand son marqueur est survolé sur la carte. Sans photo, une illustration.
 */
@Component({
  selector: 'site-vignette-bien',
  imports: [RouterLink, Icone, Coeur, Nombre],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(mouseenter)': 'survol.emit(bien().id)', '(mouseleave)': 'survol.emit(null)' },
  template: `
    <a class="vignette" [class.en-avant]="enAvant()" [routerLink]="['/biens', bien().slug]" [attr.aria-label]="bien().titre + ', ' + texte()">
      <div class="visuel">
        @if (bien().idPhoto !== null && !photoKo()) {
          <img class="fond" [src]="adresse()" [alt]="''" loading="lazy" decoding="async" (error)="photoKo.set(true)"
               [style.view-transition-name]="transition() ? 'photo-' + bien().id : null">
        } @else {
          <div class="fond" [style.background]="teinte()">
            <svg viewBox="0 0 200 150" width="100%" height="100%" preserveAspectRatio="xMidYMax slice" aria-hidden="true">
              <circle cx="150" cy="44" r="16" fill="rgba(255,255,255,.22)" />
              <path d="M0 150V96h22V70h26v26h14V52h34v44h12V80h24v16h16V62h28v34h24v54Z" fill="rgba(255,255,255,.14)" />
              <path d="M0 150v-30h40v-14h30v14h26v-24h36v24h20v-12h48v42Z" fill="rgba(255,255,255,.22)" />
            </svg>
            <fact-icone class="pictogramme" [nom]="bien().categorie === 'Villa' || bien().categorie === 'Duplex' ? 'maison' : 'batiment'" [taille]="44" />
          </div>
        }
        <span class="etiquette">{{ bien().operation === 'LOCATION' ? 'À louer' : 'À vendre' }} · {{ bien().categorie }}</span>
        @if (bien().photos > 1) { <span class="nb-photos"><fact-icone nom="photo" [taille]="14" /> {{ bien().photos }}</span> }
        <site-coeur class="favori" [bien]="bien()" />
      </div>
      <div class="corps">
        <p class="prix" [attr.aria-label]="texte()"><span [siteNombre]="bien().prix" [suffixe]="bien().operation === 'LOCATION' ? ' FCFA / mois' : ' FCFA'"></span></p>
        <h3>{{ bien().titre }}</h3>
        <p class="lieu">{{ bien().quartier ? bien().quartier + ', ' : '' }}{{ bien().commune }}</p>
        @if (bien().chambres || bien().sallesEau || bien().surface || bien().pieces) {
          <p class="caracteristiques">
            @if (bien().chambres) { <span><fact-icone nom="chambre" [taille]="16" /> {{ bien().chambres }} chambre{{ bien().chambres! > 1 ? 's' : '' }}</span> }
            @else if (bien().pieces) { <span><fact-icone nom="pieces" [taille]="16" /> {{ bien().pieces }} pièce{{ bien().pieces! > 1 ? 's' : '' }}</span> }
            @if (bien().sallesEau) { <span><fact-icone nom="salle-eau" [taille]="16" /> {{ bien().sallesEau }} salle{{ bien().sallesEau! > 1 ? 's' : '' }} d’eau</span> }
            @if (bien().surface) { <span><fact-icone nom="surface" [taille]="16" /> {{ bien().surface }} m²</span> }
          </p>
        }
      </div>
    </a>`,
  styles: [`
    :host { display: block; height: 100%; }
    .pictogramme { position: absolute; }
    img.fond { width: 100%; height: 100%; object-fit: cover; }
    .vignette.en-avant { transform: translateY(-6px); border-color: var(--ocre); box-shadow: 0 0 0 2px var(--ocre), 0 24px 40px -24px rgba(11, 18, 34, .45); }
    .nb-photos { position: absolute; left: 12px; bottom: 12px; display: inline-flex; align-items: center; gap: 4px; padding: 4px 9px;
                 border-radius: var(--rayon-pastille); background: rgba(11, 18, 34, .7); color: #fff; font-size: 12.5px; }
    .favori { position: absolute; right: 10px; top: 10px; }
  `],
})
export class VignetteBien {
  readonly bien = input.required<Vignette>();
  /** Le marqueur de ce bien est survolé sur la carte. */
  readonly enAvant = input(false);
  /** Nom de transition de vue : la photo grandit jusqu'à la fiche (§ 6.2). */
  readonly transition = input(true);
  readonly survol = output<number | null>();
  protected readonly photoKo = signal(false);
  protected readonly texte = computed(() => prix(this.bien()));
  protected readonly adresse = computed(() => photo(this.bien().idPhoto!));
  protected readonly teinte = computed(() => {
    const [a, b] = TEINTES[this.bien().id % TEINTES.length];
    return `linear-gradient(140deg, ${a}, ${b})`;
  });
}
