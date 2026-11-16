import { AfterViewInit, ChangeDetectionStrategy, Component, ElementRef, NgZone, OnDestroy, effect, inject, input, output, viewChild } from '@angular/core';
import * as L from 'leaflet';

const PLAN = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

/** Une petite carte pour poser son bien (§ 3.4) : un clic place le point, qu'on peut déplacer. */
@Component({
  selector: 'site-carte-pose',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div #hote class="carte" role="application" aria-label="Carte : touchez l'emplacement de votre bien"></div>`,
  styles: [`
    :host { display: block; }
    .carte { height: 240px; border-radius: 14px; border: 1px solid var(--filet-fort); z-index: 0; }
    :host ::ng-deep .point-pose { width: 22px; height: 22px; margin: -11px 0 0 -11px; border-radius: 50%; background: #1a63d8;
      border: 3px solid #fff; box-shadow: 0 0 0 4px rgba(26, 99, 216, .35), 0 6px 14px -4px rgba(0,0,0,.4); }
  `],
})
export class CartePose implements AfterViewInit, OnDestroy {
  private readonly zone = inject(NgZone);
  private readonly hote = viewChild.required<ElementRef<HTMLElement>>('hote');
  /** Le centre de la commune choisie, pour s'y rendre. */
  readonly centre = input<[number, number] | null>(null);
  readonly pose = output<{ latitude: number; longitude: number }>();
  private carte: L.Map | null = null;
  private point: L.Marker | null = null;

  constructor() {
    effect(() => {
      const c = this.centre();
      if (c && this.carte && !this.point) this.carte.flyTo(c, 14, { duration: 0.8 });
    });
  }

  ngAfterViewInit(): void {
    this.zone.runOutsideAngular(() => {
      this.carte = L.map(this.hote().nativeElement, { scrollWheelZoom: false, center: this.centre() ?? [5.345, -4.01], zoom: this.centre() ? 14 : 11 });
      L.tileLayer(PLAN, { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
      this.carte.on('click', (e: L.LeafletMouseEvent) => this.placer(e.latlng));
      new ResizeObserver(() => this.carte?.invalidateSize()).observe(this.hote().nativeElement);
    });
  }

  ngOnDestroy(): void {
    this.carte?.remove();
  }

  private placer(p: L.LatLng): void {
    if (!this.carte) return;
    if (!this.point) {
      this.point = L.marker(p, { draggable: true, icon: L.divIcon({ className: '', html: '<span class="point-pose"></span>', iconSize: [0, 0] }) })
        .addTo(this.carte);
      this.point.on('dragend', () => this.emettre(this.point!.getLatLng()));
    } else {
      this.point.setLatLng(p);
    }
    this.emettre(p);
  }

  private emettre(p: L.LatLng): void {
    this.zone.run(() => this.pose.emit({ latitude: Math.round(p.lat * 1e6) / 1e6, longitude: Math.round(p.lng * 1e6) / 1e6 }));
  }
}
