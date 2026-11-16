import { ChangeDetectionStrategy, Component, input } from '@angular/core';

let suivant = 0;

/**
 * Le logo de FACT Immobilier, reconstitué en vecteurs d'après le fichier reçu de l'agence
 * (public/marque/logo-fact-original.png, 154 × 71 px) : « FACT » en lettres argent chromé
 * biseautées, l'immeuble bleu à fenêtres en grille, « IMMOBILIER » en bleu sur un bandeau argent.
 * C'est une reconstitution à faire valider : quand l'agence fournira son fichier original en
 * haute définition, seuls les tracés de ce composant seront à remplacer.
 *
 * Les éléments portent des classes (immeuble, fenetre, lettres, reflet, bandeau) : le
 * préchargeur les anime un par un.
 */
@Component({
  selector: 'site-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 300 140" [attr.width]="largeur()" role="img" aria-label="FACT Immobilier" class="logo" [class.sombre]="variante() === 'sombre'">
      <defs>
        <linearGradient [attr.id]="id('argent')" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#f6f9fa" />
          <stop offset=".38" stop-color="#dce4e4" />
          <stop offset=".52" stop-color="#97a5b4" />
          <stop offset=".7" stop-color="#e8eef0" />
          <stop offset="1" stop-color="#b4c2d0" />
        </linearGradient>
        <linearGradient [attr.id]="id('bandeau')" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#eef3f4" />
          <stop offset=".5" stop-color="#cfd9e0" />
          <stop offset="1" stop-color="#a9b8c6" />
        </linearGradient>
        <linearGradient [attr.id]="id('facade')" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#2f6bd0" />
          <stop offset=".55" stop-color="#2353a6" />
          <stop offset="1" stop-color="#1b4389" />
        </linearGradient>
        <linearGradient [attr.id]="id('reflet')" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stop-color="#fff" stop-opacity="0" />
          <stop offset=".5" stop-color="#fff" stop-opacity=".95" />
          <stop offset="1" stop-color="#fff" stop-opacity="0" />
        </linearGradient>
        <clipPath [attr.id]="id('lettres')">
          <text x="8" y="94" class="texte-fact">FACT</text>
        </clipPath>
      </defs>

      <!-- L'immeuble : façade bleu roi, flanc plus sombre, fenêtres en grille -->
      <g class="immeuble">
        <path d="M262 10 284 19v95h-22z" fill="#2f5585" />
        <rect x="212" y="6" width="50" height="108" rx="1.5" [attr.fill]="url('facade')" />
        <g class="fenetres">
          @for (f of fenetres; track $index) {
            <rect class="fenetre" [attr.x]="f.x" [attr.y]="f.y" width="7.5" height="8.5" rx="1" fill="#e9f0f7" />
          }
          @for (f of flanc; track $index) {
            <path class="fenetre" [attr.d]="f" fill="#b9c8da" opacity=".85" />
          }
        </g>
      </g>

      <!-- « FACT » : ombre anthracite, lettres argent biseautées, reflet chromé -->
      <g class="lettres">
        <text x="11" y="97" class="texte-fact ombre">FACT</text>
        <text x="8" y="94" class="texte-fact metal" [attr.fill]="url('argent')">FACT</text>
        <g [attr.clip-path]="url('lettres')"><rect class="reflet" x="-90" y="20" width="60" height="90" [attr.fill]="url('reflet')" transform="skewX(-20)" /></g>
      </g>

      <!-- Le bandeau argent et « IMMOBILIER » -->
      <g class="bandeau">
        <rect x="5" y="104" width="282" height="31" rx="3.5" [attr.fill]="url('bandeau')" stroke="#8796a8" stroke-width="1" />
        <text x="146" y="127.5" text-anchor="middle" class="texte-immo">IMMOBILIER</text>
      </g>
    </svg>`,
  styles: [`
    :host { display: inline-flex; }
    .logo { display: block; height: auto; overflow: hidden; }
    .texte-fact { font: 800 84px 'Sora', 'Arial Black', sans-serif; letter-spacing: -3px; }
    .metal { stroke: #2e2e2e; stroke-width: 2.4px; paint-order: stroke; }
    .ombre { fill: #2e2e2e; }
    .sombre .metal { stroke: #0b1222; }
    .sombre .ombre { fill: #05090f; }
    .texte-immo { font: 800 21px 'Sora', 'Arial Black', sans-serif; letter-spacing: 4.6px; fill: #2353a6; }
  `],
})
export class Logo {
  readonly largeur = input<number | string>(150);
  readonly variante = input<'clair' | 'sombre'>('clair');
  private readonly n = ++suivant;

  /** La grille des fenêtres de la façade : 4 colonnes, 8 étages. */
  protected readonly fenetres = Array.from({ length: 32 }, (_, i) => ({ x: 216.5 + (i % 4) * 11.2, y: 12 + Math.floor(i / 4) * 12.4 }));
  /** Les fenêtres du flanc, en perspective. */
  protected readonly flanc = Array.from({ length: 8 }, (_, i) => {
    const y = 18 + i * 11.6;
    return `M266 ${y}l14 4.4v7l-14-3.6z`;
  });

  protected id(nom: string): string {
    return `logo-${this.n}-${nom}`;
  }

  protected url(nom: string): string {
    return `url(#${this.id(nom)})`;
  }
}
