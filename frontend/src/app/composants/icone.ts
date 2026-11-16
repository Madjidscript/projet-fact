import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

/** Pictogrammes au trait, dessinés pour l'application (24 × 24, trait 1,8). */
const TRACES: Record<string, string> = {
  accueil: '<path d="M3 10.5 12 3l9 7.5V21H3z"/><path d="M9 21v-7h6v7"/>',
  carte: '<path d="M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2Z"/><path d="M9 4v14M15 6v14"/>',
  batiment: '<path d="M5 21V4h10v17M15 9h4v12M3 21h18"/><path d="M8 8h1M11 8h1M8 12h1M11 12h1M8 16h1M11 16h1"/>',
  portefeuille: '<path d="M3 7h18v13H3z"/><path d="M8 7V4h8v3M3 12h18"/>',
  cle: '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M17 6l3 3M14 9l2 2"/>',
  monnaie: '<circle cx="12" cy="12" r="9"/><path d="M15 9.5c-.6-1-1.7-1.5-3-1.5-1.7 0-3 .9-3 2.2 0 3 6 1.300 6 4.100 0 1.300-1.300 2.200-3 2.200-1.400 0-2.600-.6-3.100-1.600M12 6v2M12 16v2"/>',
  outils: '<path d="M14.500 6.500a4 4 0 0 0-5.300 5.300L3 18l3 3 6.200-6.200a4 4 0 0 0 5.300-5.300L15 12l-3-3z"/>',
  rapport: '<path d="M6 3h8.500L19 7.500V21H6z"/><path d="M14 3v5h5M9.500 17.500v-3M12.500 17.500v-6M15.500 17.500v-4"/>',
  tableau: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  composants: '<rect x="3" y="3" width="8" height="8" rx="2"/><rect x="13" y="3" width="8" height="8" rx="2"/><rect x="3" y="13" width="8" height="8" rx="2"/><circle cx="17" cy="17" r="4"/>',
  comptes: '<circle cx="9" cy="8" r="3.500"/><path d="M2.500 20c.8-3.600 3.300-5.500 6.500-5.500s5.700 1.900 6.500 5.500"/><path d="M16 4.500a3.500 3.500 0 0 1 0 7M18.500 14.800c1.600.9 2.600 2.600 3 5.200"/>',
  reglages: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
  recherche: '<circle cx="11" cy="11" r="6.500"/><path d="m16 16 4.500 4.500"/>',
  fermer: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  crayon: '<path d="M4 20h4L19 9l-4-4L4 16v4Z"/><path d="m13.500 6.500 4 4"/>',
  archive: '<path d="M3 5h18v4H3zM5 9v11h14V9M10 13h4"/>',
  telecharger: '<path d="M12 4v11M7 10l5 5 5-5M5 20h14"/>',
  alerte: '<path d="M12 3 2 20h20L12 3Z"/><path d="M12 10v4M12 17.200v.3"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.500v.3"/>',
  coche: '<path d="m5 12.500 4.500 4.500L19 7.500"/>',
  gauche: '<path d="m15 5-7 7 7 7"/>',
  droite: '<path d="m9 5 7 7-7 7"/>',
  haut: '<path d="m5 15 7-7 7 7"/>',
  bas: '<path d="m5 9 7 7 7-7"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  monde: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.500 2.700 3.700 5.700 3.700 9s-1.200 6.300-3.700 9c-2.500-2.700-3.700-5.700-3.700-9S9.500 5.700 12 3Z"/>',
  etoile: '<path d="m12 3.500 2.600 5.300 5.900.9-4.250 4.150 1 5.850L12 16.950 6.750 19.700l1-5.850L3.500 9.700l5.900-.9Z"/>',
  telephone: '<path d="M5 3h4l2 5-2.500 1.500a11 11 0 0 0 6 6L16 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2Z"/>',
  courrier: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
  whatsapp: '<path d="M4 20l1.300-4.200A8 8 0 1 1 8.400 18.800Z"/><path d="M9 9.500c0 3 2.500 5.500 5.500 5.500l1-1.500-2-1-1 1c-1-.5-1.500-1-2-2l1-1-1-2Z"/>',
  boite: '<path d="M3 13h5l1.500 3h5L16 13h5"/><path d="M5 5h14l2 8v6H3v-6Z"/>',
  annonce: '<path d="M4 10v4h3l6 4V6L7 10Z"/><path d="M16.500 9a4 4 0 0 1 0 6M19 6.500a8 8 0 0 1 0 11"/>',
  cloche: '<path d="M6 17V11a6 6 0 0 1 12 0v6l2 2H4l2-2ZM10 21h4"/>',
  soleil: '<circle cx="12" cy="12" r="4"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.900 4.900 7 7M17 17l2.100 2.100M4.900 19.100 7 17M17 7l2.100-2.100"/>',
  lune: '<path d="M20 14.500A8 8 0 0 1 9.500 4a8 8 0 1 0 10.500 10.500Z"/>',
  utilisateur: '<circle cx="12" cy="8" r="4"/><path d="M4 21c.9-4 4-6.500 8-6.500s7.100 2.500 8 6.500"/>',
  synchro: '<path d="M20 11a8 8 0 0 0-14.500-4.500L4 8M4 4v4h4M4 13a8 8 0 0 0 14.500 4.500L20 16M20 20v-4h-4"/>',
  hors_ligne: '<path d="M3 3l18 18M8.500 16.500a5 5 0 0 1 7 0M5 13a10 10 0 0 1 4-2.600M19 13a10 10 0 0 0-5-3M2 9.500a15 15 0 0 1 4.500-3M22 9.500A15 15 0 0 0 10 5.200"/><circle cx="12" cy="20" r=".8"/>',
  photo: '<path d="M3 7h4l2-3h6l2 3h4v13H3z"/><circle cx="12" cy="13" r="4"/>',
  calques: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 13 9 5 9-5"/>',
  filtre: '<path d="M3 5h18l-7 8v6l-4 2v-8z"/>',
  horloge: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.500 2"/>',
  document: '<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
  cible: '<circle cx="12" cy="12" r="7"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/>',
  fleche: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  oeil: '<path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  sortir: '<path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10"/>',
  journal: '<path d="M6 3h11l3 3v15H6z"/><path d="M10 9h6M10 13h6M10 17h4"/>',
  copier: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
};

/** Un pictogramme par son nom : <fact-icone nom="carte" />. Il prend la couleur du texte. */
@Component({
  selector: 'fact-icone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg viewBox="0 0 24 24" [attr.width]="taille()" [attr.height]="taille()" fill="none"
                  stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
                  aria-hidden="true" [innerHTML]="trace()"></svg>`,
  styles: [`:host { display: inline-flex; flex: none; vertical-align: middle; }`],
})
export class Icone {
  private readonly assainisseur = inject(DomSanitizer);

  readonly nom = input.required<string>();
  readonly taille = input(20);

  // Les tracés sont des constantes de ce fichier : rien qui vienne de l'utilisateur.
  protected readonly trace = computed(() =>
    this.assainisseur.bypassSecurityTrustHtml(TRACES[this.nom()] ?? TRACES['info']),
  );
}

/** Les noms de pictogrammes disponibles, pour le catalogue. */
export const NOMS_ICONES = Object.keys(TRACES);
