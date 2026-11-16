import {
  AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, NgZone, OnDestroy, effect, inject, input, output, untracked, viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import * as L from 'leaflet';
import { Vignette, Zone } from '../noyau/api';
import { montant } from '../noyau/donnees';
import { mouvementReduit } from '../noyau/mouvement';

const PLAN = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
const ABIDJAN: L.LatLngTuple = [5.345, -4.01];
/** Rayon du cercle d'un bien à position approchée : on montre une zone, jamais le point. */
const RAYON_ZONE = 450;

/**
 * La carte des biens (§ 3.2). Un bien à position exacte a son marqueur de prix ; un bien à
 * position approchée, un cercle de zone. Déplacer la carte émet la zone visible (la liste se
 * recharge) ; survoler un marqueur met la vignette en évidence, et inversement.
 */
@Component({
  selector: 'site-carte-biens',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #hote class="carte" role="region" [attr.aria-label]="'Carte de ' + biens().length + ' bien(s)'"></div>`,
  styles: [`
    :host { display: block; height: 100%; }
    .carte { height: 100%; min-height: 260px; border-radius: inherit; z-index: 0; }
    :host ::ng-deep .marqueur-prix { display: inline-block; transform: translate(-50%, -100%); padding: 5px 9px; border-radius: 999px; background: #1a2f55;
      color: #fff; font: 600 12.5px var(--texte); white-space: nowrap; box-shadow: 0 6px 14px -6px rgba(0,0,0,.5);
      transition: transform 200ms cubic-bezier(.2,0,0,1), background 200ms; }
    :host ::ng-deep .marqueur-prix::after { content: ''; position: absolute; left: 50%; bottom: -5px; margin-left: -5px; border: 5px solid transparent;
      border-bottom: 0; border-top-color: inherit; }
    :host ::ng-deep .marqueur-prix.vente { background: #154fb0; }
    :host ::ng-deep .icone-prix.en-avant { z-index: 1000 !important; }
    :host ::ng-deep .en-avant .marqueur-prix { background: #1a63d8; transform: translate(-50%, -100%) scale(1.25); animation: rebond 600ms cubic-bezier(.2,0,0,1); }
    @keyframes rebond { 0% { transform: translate(-50%, -100%) scale(1); } 40% { transform: translate(-50%, -130%) scale(1.3); }
                        100% { transform: translate(-50%, -100%) scale(1.25); } }
  `],
})
export class CarteBiens implements AfterViewInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly router = inject(Router);
  private readonly hote = viewChild.required<ElementRef<HTMLElement>>('hote');

  readonly biens = input<Vignette[]>([]);
  readonly enAvant = input<number | null>(null);
  /** Faux : carte d'un seul bien (fiche), sans suivi de la zone ni lien. */
  readonly suivreZone = input(true);
  readonly zoneChangee = output<Zone>();
  readonly survol = output<number | null>();

  private carte: L.Map | null = null;
  private couche = L.layerGroup();
  private marqueurs = new Map<number, L.Marker | L.Circle>();
  /** Vrai pendant un déplacement fait par le programme (cadrage) : il ne recharge pas la liste. */
  private cadrage = false;
  private dejaCadree = false;
  /** Vrai dès que le visiteur a glissé ou zoomé : on ne recadre plus à sa place. */
  private touchee = false;

  constructor() {
    effect(() => {
      const l = this.biens();
      untracked(() => this.dessiner(l));
    });
    effect(() => {
      const id = this.enAvant();
      untracked(() => this.mettreEnAvant(id));
    });
  }

  ngAfterViewInit(): void {
    this.zone.runOutsideAngular(() => {
      this.carte = L.map(this.hote().nativeElement, { zoomControl: true, scrollWheelZoom: false, center: ABIDJAN, zoom: 11 });
      L.tileLayer(PLAN, { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
      this.couche.addTo(this.carte);
      // Seuls un glissement ou un zoom du visiteur rechargent la liste (pas un recadrage ni un redimensionnement).
      const emettre = () => {
        if (this.cadrage || !this.suivreZone()) return;
        this.touchee = true;
        const b = this.carte!.getBounds();
        this.zone.run(() => this.zoneChangee.emit({ ouest: b.getWest(), sud: b.getSouth(), est: b.getEast(), nord: b.getNorth() }));
      };
      this.carte.on('dragend', emettre);
      this.carte.on('zoomend', emettre);
      new ResizeObserver(() => {
        this.carte?.invalidateSize();
        // Tant que le visiteur n'a pas déplacé la carte, elle se recadre quand son cadre change de
        // taille (téléphone : bascule liste / carte ; colonne qui s'agrandit au chargement).
        if (this.hote().nativeElement.clientWidth > 0 && !this.touchee) {
          this.dejaCadree = false;
          this.dessiner(this.biens());
        }
      }).observe(this.hote().nativeElement);
      this.dessiner(this.biens());
    });
  }

  ngOnDestroy(): void {
    this.carte?.remove();
  }

  /** Recadre la carte sur les biens affichés (après une nouvelle recherche). */
  recadrer(): void {
    this.dejaCadree = false;
    this.touchee = false;
    this.dessiner(this.biens());
  }

  private dessiner(liste: Vignette[]): void {
    if (!this.carte) return;
    this.couche.clearLayers();
    this.marqueurs.clear();
    const points: L.LatLng[] = [];
    for (const b of liste) {
      if (b.latitude === null || b.longitude === null) continue;
      const p = L.latLng(b.latitude, b.longitude);
      points.push(p);
      let m: L.Marker | L.Circle;
      if (b.positionExacte) {
        m = L.marker(p, {
          icon: L.divIcon({ className: 'icone-prix', html: `<span class="marqueur-prix${b.operation === 'VENTE' ? ' vente' : ''}">${this.court(b.prix)}</span>`,
            iconSize: [0, 0] }),
          keyboard: false,
          title: b.titre,
        });
      } else {
        m = L.circle(p, { radius: RAYON_ZONE, color: '#1a2f55', weight: 1.5, fillColor: '#3a78c2', fillOpacity: 0.18 });
        m.bindTooltip(`${b.titre} · zone approximative`, { direction: 'top' });
      }
      if (this.suivreZone()) {
        m.on('click', () => this.zone.run(() => this.router.navigate(['/biens', b.slug])));
        m.on('mouseover', () => this.zone.run(() => this.survol.emit(b.id)));
        m.on('mouseout', () => this.zone.run(() => this.survol.emit(null)));
      }
      m.addTo(this.couche);
      this.marqueurs.set(b.id, m);
    }
    if (!this.dejaCadree && points.length && this.hote().nativeElement.clientWidth > 0) {
      this.dejaCadree = true;
      this.cadrage = true;
      if (points.length === 1) this.carte.setView(points[0], this.suivreZone() ? 14 : 15, { animate: false });
      else this.carte.fitBounds(L.latLngBounds(points).pad(0.15), { animate: false, maxZoom: 15 });
      setTimeout(() => (this.cadrage = false), 400);
    }
  }

  private mettreEnAvant(id: number | null): void {
    this.marqueurs.forEach((m, cle) => {
      if (m instanceof L.Marker) {
        const el = m.getElement();
        el?.classList.toggle('en-avant', cle === id);
        if (cle === id && el && !mouvementReduit()) m.setZIndexOffset(1000);
        else m.setZIndexOffset(0);
      } else {
        m.setStyle(cle === id ? { fillOpacity: 0.4, weight: 3, color: '#1a63d8' } : { fillOpacity: 0.18, weight: 1.5, color: '#1a2f55' });
      }
    });
  }

  /** « 285 k » ou « 2,5 M » : un prix court pour le marqueur. */
  private court(p: number): string {
    if (p >= 1_000_000) return (Math.round(p / 100_000) / 10).toString().replace('.', ',') + ' M';
    if (p >= 10_000) return Math.round(p / 1000) + ' k';
    return montant(p);
  }
}
