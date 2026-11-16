import { Component, ElementRef, OnDestroy, afterNextRender, effect, input, output, signal, viewChild } from '@angular/core';
import * as L from 'leaflet';
import { coordonnees } from '../noyau/format';
import { animationsReduites } from './mouvement';
import { Icone } from './icone';

/** Une position GPS en WGS 84, et la précision annoncée par l'appareil quand elle vient de lui. */
export interface Position {
  latitude: number;
  longitude: number;
  precision?: number | null;
}

const PLAN = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
const SATELLITE = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';

/**
 * Pose ou montre la position d'un bâtiment sur la carte. « Un bâtiment ne peut pas être
 * enregistré sans position » (§ 4.2) : un clic sur la carte place le point, on le fait glisser
 * pour l'ajuster — sur l'image satellite au besoin (§ 4.6) — et « Ma position » le capture au
 * GPS de l'appareil, avec son cercle de précision.
 *   <fact-carte-position [position]="p" [modifiable]="true" (change)="p = $event" />
 */
@Component({
  selector: 'fact-carte-position',
  imports: [Icone],
  template: `
    <div class="cadre">
      <div #carte class="carte" role="application" [attr.aria-label]="modifiable() ? 'Carte : cliquez pour placer le bâtiment' : 'Carte de localisation'"></div>
      <div class="outils">
        <button type="button" class="btn petit" (click)="basculerFond()" [attr.aria-pressed]="satellite()">
          <fact-icone nom="calques" [taille]="16" /> {{ satellite() ? 'Plan' : 'Satellite' }}
        </button>
        @if (modifiable()) {
          <button type="button" class="btn petit" [class.occupe]="recherche()" (click)="maPosition()">
            <fact-icone nom="cible" [taille]="16" /> Ma position
          </button>
        }
      </div>
      <p class="lecture" aria-live="polite">
        @if (position(); as p) {
          <span class="donnee">{{ texte(p) }}</span>
          @if (p.precision) { <span> · précision {{ arrondi(p.precision) }} m</span> }
        } @else if (modifiable()) {
          Cliquez sur la carte pour placer le bâtiment.
        } @else {
          Ce bâtiment n'est pas encore localisé.
        }
      </p>
    </div>`,
  styles: [`
    :host { display: block; }
    .cadre { position: relative; border: 1px solid var(--filet-fort); border-radius: var(--rayon-2); overflow: hidden; background: var(--surface-3); }
    .carte { height: var(--hauteur-carte, 260px); z-index: 0; }
    .outils { position: absolute; top: 8px; right: 8px; z-index: 400; display: flex; gap: 6px; }
    .lecture { margin: 0; padding: 8px 12px; font-size: 13px; color: var(--encre-2); background: var(--surface); border-top: 1px solid var(--filet); }
    /* Le marqueur : une pastille bleu nuit cerclée de blanc, comme sur la carte du patrimoine. */
    :host ::ng-deep .fact-marqueur { width: 22px; height: 22px; border-radius: 50%; background: var(--nuit); border: 3px solid #fff;
      box-shadow: 0 1px 5px rgba(0, 0, 0, .45); }
    :host ::ng-deep .fact-marqueur.glisse { cursor: grab; }
    :host ::ng-deep .leaflet-container { font-family: var(--texte); background: var(--surface-3); }
  `],
})
export class CartePosition implements OnDestroy {
  readonly position = input<Position | null>(null);
  readonly modifiable = input(false);
  /** Le point où centrer la carte tant qu'aucune position n'est posée : la commune, ou Abidjan. */
  readonly centre = input<Position | null>(null);
  readonly change = output<Position>();

  protected readonly satellite = signal(false);
  protected readonly recherche = signal(false);

  private readonly hote = viewChild.required<ElementRef<HTMLDivElement>>('carte');
  private carte?: L.Map;
  private fond?: L.TileLayer;
  private marqueur?: L.Marker;
  private cercle?: L.Circle;
  private centree = false;
  private suivi?: ResizeObserver;

  constructor() {
    afterNextRender(() => this.creer());
    // La position ou le centre changent : la carte suit.
    effect(() => {
      const p = this.position(), c = this.centre();
      if (this.carte) this.poser(p, c);
    });
  }

  ngOnDestroy(): void {
    this.suivi?.disconnect();
    this.carte?.remove();
  }

  protected texte(p: Position): string {
    return coordonnees(p.latitude, p.longitude);
  }

  protected arrondi(n: number): number {
    return Math.round(n);
  }

  protected basculerFond(): void {
    this.satellite.update((s) => !s);
    this.poserFond();
  }

  /** Capture la position de l'appareil ; le cercle montre sa précision (§ 4.6). */
  protected maPosition(): void {
    if (!navigator.geolocation) return;
    this.recherche.set(true);
    navigator.geolocation.getCurrentPosition(
      (g) => {
        this.recherche.set(false);
        this.centree = false;
        this.change.emit({ latitude: g.coords.latitude, longitude: g.coords.longitude, precision: g.coords.accuracy });
      },
      () => this.recherche.set(false),
      { enableHighAccuracy: true, timeout: 15000 },
    );
  }

  private creer(): void {
    this.carte = L.map(this.hote().nativeElement, { zoomControl: true, attributionControl: true, scrollWheelZoom: false });
    this.carte.setView([5.345, -4.012], 12);
    this.poserFond();
    this.carte.on('click', (e: L.LeafletMouseEvent) => {
      if (this.modifiable()) this.change.emit({ latitude: e.latlng.lat, longitude: e.latlng.lng, precision: null });
    });
    // La carte s'ouvre souvent dans un panneau qui glisse : elle recalcule sa taille à chaque
    // changement de son cadre, sans quoi les tuiles restent grises.
    this.suivi = new ResizeObserver(() => this.carte?.invalidateSize());
    this.suivi.observe(this.hote().nativeElement);
    setTimeout(() => this.carte?.invalidateSize(), 300);
    this.poser(this.position(), this.centre());
  }

  private poserFond(): void {
    if (!this.carte) return;
    this.fond?.remove();
    this.fond = this.satellite()
      ? L.tileLayer(SATELLITE, { maxZoom: 19, attribution: 'Imagerie © Esri' })
      : L.tileLayer(PLAN, { maxZoom: 19, attribution: '© OpenStreetMap' });
    this.fond.addTo(this.carte);
  }

  private poser(p: Position | null, centre: Position | null): void {
    if (!this.carte) return;
    this.cercle?.remove();
    this.cercle = undefined;
    if (!p) {
      this.marqueur?.remove();
      this.marqueur = undefined;
      if (centre) this.carte.setView([centre.latitude, centre.longitude], 14);
      this.centree = false;
      return;
    }
    const ou: L.LatLngExpression = [p.latitude, p.longitude];
    if (!this.marqueur) {
      this.marqueur = L.marker(ou, {
        draggable: this.modifiable(),
        keyboard: false,
        icon: L.divIcon({ className: '', html: `<div class="fact-marqueur${this.modifiable() ? ' glisse' : ''}"></div>`, iconSize: [22, 22], iconAnchor: [11, 11] }),
      }).addTo(this.carte);
      this.marqueur.on('dragend', () => {
        const ll = this.marqueur!.getLatLng();
        this.change.emit({ latitude: ll.lat, longitude: ll.lng, precision: null });
      });
    } else {
      this.marqueur.setLatLng(ou);
    }
    if (p.precision) {
      this.cercle = L.circle(ou, { radius: p.precision, color: '#3a78c2', weight: 1, fillOpacity: 0.12 }).addTo(this.carte);
    }
    // Premier placement : la carte vole vers le point (§ 6.3) ; ensuite elle ne bouge plus sous le doigt.
    if (!this.centree) {
      this.centree = true;
      const zoom = Math.max(this.carte.getZoom(), 17);
      if (animationsReduites()) this.carte.setView(ou, zoom); else this.carte.flyTo(ou, zoom, { duration: 0.6 });
    }
  }
}
