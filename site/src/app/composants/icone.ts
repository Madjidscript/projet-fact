import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, NgZone, OnDestroy, ViewEncapsulation, computed, inject, input } from '@angular/core';
import { gsap } from 'gsap';
import { mouvementReduit } from '../noyau/mouvement';
import { DomSanitizer } from '@angular/platform-browser';

/** Pictogrammes au trait, dessinés pour l'application (24 × 24, trait 1,8). */
const TRACES: Record<string, string> = {
  'gestion': '<path d="M3.5 11 12 4l8.5 7"/><path d="M5.5 9.500V20h6.500"/><circle class="ac" cx="16.500" cy="15" r="2.700"/><path class="ac" d="m18.400 16.900 2.600 2.600m-1.300-1.300-1.100 1.100"/>',
  'transaction': '<path d="m2 11.500 4-4 4 2 3-2 4 1 5 3-3 3"/><path d="M2 11.500 6 15.500"/><path class="ac" d="m8 14 3 3a1.500 1.500 0 0 0 2-2m-2-1 3 3a1.500 1.500 0 0 0 2-2l-3-3"/>',
  'construction': '<path d="M6 21V4h2.500v17M4 21h7"/><path d="M8.500 5H20l-3 3M8.500 5l4 3"/><path class="ac" d="M17 8v3.500"/><rect class="ac" x="15.300" y="11.500" width="3.400" height="3.400" rx=".8"/>',
  'travaux': '<path d="M14.500 6.500a4 4 0 0 0-5.300 5.300L3 18l3 3 6.200-6.200a4 4 0 0 0 5.300-5.300L15 12l-3-3z"/><path class="ac" d="m16 17 3 3m-1.500-4.500 3 3"/>',
  'chambre': '<path d="M3 18.500v-9M21 18.500V13a3 3 0 0 0-3-3h-7v5.500"/><path d="M3 15.500h18"/><circle class="ac" cx="7" cy="12" r="1.800"/>',
  'salle-eau': '<path d="M4 20V8a4 4 0 0 1 8 0"/><path class="ac" d="M9 11h6M10 14v.300M12.500 14v.300M15 14v.300M11 17v.300M14 17v.300"/>',
  'surface': '<rect x="4" y="4" width="16" height="16" rx="2.500"/><path class="ac" d="M4 9h5V4M15 20v-5h5"/>',
  'pieces': '<path d="M4 20V5.500A1.500 1.500 0 0 1 5.500 4h13A1.500 1.500 0 0 1 20 5.500V20M3 20h18"/><path class="ac" d="M12 4v7h8M12 15v5"/>',
  'parking': '<rect x="4" y="4" width="16" height="16" rx="3.500"/><path class="ac" d="M10 17V7h3a3 3 0 0 1 0 6h-3"/>',
  'gardien': '<path d="M12 3 4.500 6v5.500c0 4.400 3.200 7.800 7.500 9.500 4.300-1.700 7.500-5.100 7.500-9.500V6z"/><path class="ac" d="m8.700 12 2.300 2.300 4.300-4.300"/>',
  'groupe': '<rect x="3" y="8" width="18" height="11" rx="2.500"/><path d="M7 8V5.500h10V8"/><path class="ac" d="m12.800 10.500-2.600 3.800h3.300l-2.600 3.700"/>',
  'clim': '<rect x="3" y="5" width="18" height="7.500" rx="2.500"/><path d="M6.500 9.500h11"/><path class="ac" d="M8 15.500v3M12 15.500v5M16 15.500v3"/>',
  'piscine': '<path d="M7.500 16V5.500a2 2 0 0 1 4 0M15.500 16V5.500a2 2 0 0 1 4 0M7.500 9.500h8M7.500 13h8"/><path class="ac" d="M2.500 19.500c2 1.300 4 1.300 6 0s4-1.300 6 0 4 1.300 6 0"/>',
  'ascenseur': '<rect x="5" y="3" width="14" height="18" rx="2.500"/><path d="M12 3v18"/><path class="ac" d="m7.500 10.500 1.500-2 1.500 2M13.500 13.500l1.500 2 1.500-2"/>',
  'eau-chaude': '<path d="M12 3.500S6 10 6 14a6 6 0 0 0 12 0c0-4-6-10.500-6-10.500Z"/><path class="ac" d="M9.700 14.500a2.300 2.300 0 0 0 2.300 2.300"/>',
  'balcon': '<path d="M3 20h18M4.500 12.500h15M5.500 12.500V20M9.500 12.500V20M14.500 12.500V20M18.500 12.500V20"/><path class="ac" d="M7.500 12.500V5h9v7.500"/>',
  'cour': '<path d="M4 21h16M12 21v-6"/><path class="ac" d="M12 15a4.800 4.800 0 0 0 4.300-7A4.300 4.300 0 0 0 12 3.300 4.300 4.300 0 0 0 7.700 8 4.800 4.800 0 0 0 12 15Z"/>',
  'meuble': '<path d="M5.500 11V8.500a3 3 0 0 1 3-3h7a3 3 0 0 1 3 3V11"/><path d="M3 12.500a2 2 0 0 1 4 0V14h10v-1.500a2 2 0 0 1 4 0v4.500H3z"/><path class="ac" d="M6 17v2M18 17v2"/>',
  'etage': '<path d="M4 20h4v-4h4v-4h4V8h4"/><path class="ac" d="M15 4h5v5"/>',
  'appel': '<path d="M5 4h4l2 5-2.500 1.500a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z"/><path class="ac" d="M15 3.500a5.500 5.500 0 0 1 5.500 5.500M15 7a2 2 0 0 1 2 2"/>',
  'courriel': '<rect x="3" y="5" width="18" height="14" rx="2.500"/><path class="ac" d="m3.500 6.500 8.500 6.500 8.500-6.500"/>',
  'adresse': '<path d="M12 21s-7-6.200-7-11.500a7 7 0 0 1 14 0C19 14.800 12 21 12 21Z"/><circle class="ac" cx="12" cy="9.500" r="2.500"/>',
  'visite': '<path d="M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12Z"/><circle class="ac" cx="12" cy="12" r="3"/>',
  'contrat': '<path d="M6 3h8.500L19 7.500V21H6z"/><path d="M14 3v5h5"/><path class="ac" d="M9 15.500c1.300-1.600 2.300-1.600 3 0s1.700 1.600 3 0M9 18.500h6"/>',
  telephone: '<path d="M5 4h4l2 5-2.500 1.500a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z"/>',
  whatsapp: '<path d="M3.500 20.500 5 16a8.500 8.500 0 1 1 3 3l-4.500 1.500Z"/><path d="M9 9.500c.500 2.500 2.500 4.500 5 5l1.500-1.500-2-1-1 .800c-.900-.400-1.600-1.100-2-2l.800-1-1-2L9 9.500Z"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3.500 6.500 8.500 6.500 8.500-6.500"/>',
  lit: '<path d="M3 18v-9M21 18v-5a3 3 0 0 0-3-3h-7v6M3 15h18"/><circle cx="7" cy="12" r="1.800"/>',
  douche: '<path d="M4 20V8a4 4 0 0 1 8 0"/><path d="M9 11h6M10 14v.300M12.500 14v.300M15 14v.300M11 17v.300M14 17v.300"/>',
  maison: '<path d="m3 11 9-7 9 7"/><path d="M5 9.500V20h14V9.500M10 20v-6h4v6"/>',
  poignee: '<path d="m2 12 4-4 4 2 3-2 4 1 5 3-3 3"/><path d="m8 14 3 3a1.500 1.500 0 0 0 2-2m-2-1 3 3a1.500 1.500 0 0 0 2-2l-3-3M2 12l4 4"/>',
  grue: '<path d="M6 21V4h2v17M4 21h8M8 5h12l-3 3M8 5l4 3M17 8v4"/><rect x="15.500" y="12" width="3" height="3"/>',
  coeur: '<path d="M12 20s-7.500-4.400-7.500-10A4.300 4.300 0 0 1 12 7.300 4.300 4.300 0 0 1 19.500 10c0 5.600-7.500 10-7.500 10Z"/>',
  etoile: '<path d="m12 3 2.700 5.600 6.100.800-4.500 4.200 1.100 6.100L12 16.800l-5.400 2.900 1.100-6.100L3.200 9.400l6.100-.800Z"/>',
  bouclier: '<path d="M12 3 4 6v6c0 4.500 3.400 7.800 8 9 4.600-1.200 8-4.500 8-9V6l-8-3Z"/><path d="m8.500 12 2.500 2.500 4.500-4.500"/>',
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
  encapsulation: ViewEncapsulation.None,
  styles: [`fact-icone { display: inline-flex; flex: none; vertical-align: middle; } fact-icone .ac { stroke: var(--ocre); }`],
})
export class Icone implements AfterViewInit, OnDestroy {
  private readonly hote = inject(ElementRef<HTMLElement>);
  private readonly zone = inject(NgZone);
  private contexte: gsap.Context | null = null;
  /** Vrai : les traits se dessinent quand l'icône entre à l'écran (une fois). */
  readonly dessin = input(false);

  ngAfterViewInit(): void {
    if (!this.dessin() || mouvementReduit()) return;
    this.zone.runOutsideAngular(() => {
      this.contexte = gsap.context(() => {
        const traces = Array.from(this.hote.nativeElement.querySelectorAll('path, circle, rect')) as SVGGeometryElement[];
        traces.forEach((t) => {
          const l = Math.ceil(t.getTotalLength?.() ?? 60) + 2;
          gsap.set(t, { strokeDasharray: l, strokeDashoffset: l });
        });
        gsap.to(traces, { strokeDashoffset: 0, duration: 0.9, ease: 'power2.inOut', stagger: 0.08,
          scrollTrigger: { trigger: this.hote.nativeElement, start: 'top 92%', once: true },
          onComplete: () => { gsap.set(traces, { clearProps: 'strokeDasharray,strokeDashoffset' }); } });
      }, this.hote.nativeElement);
    });
  }

  ngOnDestroy(): void {
    this.contexte?.revert();
  }

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
