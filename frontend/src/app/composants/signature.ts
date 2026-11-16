import { AfterViewInit, Component, ElementRef, OnDestroy, input, output, signal, viewChild } from '@angular/core';
import { Icone } from './icone';

/**
 * Une signature au doigt (§ 4.6) : le trait suit le doigt ou le stylet, lissé et d'épaisseur
 * variable selon la vitesse. {@link image} rend la signature en PNG, sur fond blanc.
 */
@Component({
  selector: 'fact-signature',
  imports: [Icone],
  template: `
    <div class="sg" [class.signe]="!vide()">
      <canvas #toile [attr.aria-label]="'Zone de signature : ' + libelle()" role="img"></canvas>
      @if (vide()) { <span class="sg-invite" aria-hidden="true">Signez ici avec le doigt</span> }
      <span class="sg-ligne" aria-hidden="true"></span>
      <button type="button" class="sg-effacer" (click)="effacer()" [disabled]="vide()"><fact-icone nom="fermer" [taille]="14" /> Effacer</button>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .sg { position: relative; height: 190px; border-radius: var(--rayon-2); border: 2px dashed var(--filet-fort); background: #fff;
          touch-action: none; transition: border-color var(--courte) var(--courbe); overflow: hidden; }
    .sg.signe { border-style: solid; border-color: var(--ocre); }
    canvas { position: absolute; inset: 0; width: 100%; height: 100%; cursor: crosshair; }
    .sg-invite { position: absolute; inset: 0; display: grid; place-items: center; color: #8a93a3; font-size: 15px; pointer-events: none; }
    .sg-ligne { position: absolute; left: 18px; right: 18px; bottom: 44px; border-bottom: 1px solid #d5dae2; pointer-events: none; }
    .sg-effacer { position: absolute; right: 8px; bottom: 8px; display: inline-flex; align-items: center; gap: 4px; padding: 6px 10px; border: 0;
                  border-radius: 999px; background: #eef1f5; color: #3a4252; font: 600 12.5px var(--texte); cursor: pointer; }
    .sg-effacer:disabled { opacity: .45; cursor: default; }
  `],
})
export class Signature implements AfterViewInit, OnDestroy {
  readonly libelle = input('signature');
  readonly change = output<boolean>();
  protected readonly vide = signal(true);
  private readonly toile = viewChild.required<ElementRef<HTMLCanvasElement>>('toile');
  private ctx!: CanvasRenderingContext2D;
  private points: { x: number; y: number; t: number }[] = [];
  private largeur = 2.6;
  private observateur: ResizeObserver | null = null;
  private trace: ImageData | null = null;

  ngAfterViewInit(): void {
    const c = this.toile().nativeElement;
    this.ctx = c.getContext('2d')!;
    this.dimensionner();
    this.observateur = new ResizeObserver(() => this.dimensionner());
    this.observateur.observe(c);
    c.addEventListener('pointerdown', this.poser);
    c.addEventListener('pointermove', this.bouger);
    c.addEventListener('pointerup', this.lever);
    c.addEventListener('pointercancel', this.lever);
    c.addEventListener('pointerleave', this.lever);
  }

  ngOnDestroy(): void {
    this.observateur?.disconnect();
  }

  /** La signature en PNG sur fond blanc, ou null si rien n'est signé. */
  image(): Promise<Blob | null> {
    if (this.vide()) return Promise.resolve(null);
    const c = this.toile().nativeElement;
    const sortie = document.createElement('canvas');
    sortie.width = c.width;
    sortie.height = c.height;
    const x = sortie.getContext('2d')!;
    x.fillStyle = '#fff';
    x.fillRect(0, 0, sortie.width, sortie.height);
    x.drawImage(c, 0, 0);
    return new Promise((ok) => sortie.toBlob((b) => ok(b), 'image/png'));
  }

  effacer(): void {
    const c = this.toile().nativeElement;
    this.ctx.clearRect(0, 0, c.width, c.height);
    this.trace = null;
    this.vide.set(true);
    this.change.emit(false);
  }

  private dimensionner(): void {
    const c = this.toile().nativeElement;
    const r = c.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const l = Math.round(r.width * dpr), h = Math.round(r.height * dpr);
    if (!l || !h || (c.width === l && c.height === h)) return;
    const avant = this.vide() ? null : this.ctx.getImageData(0, 0, c.width, c.height);
    c.width = l;
    c.height = h;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.ctx.lineCap = 'round';
    this.ctx.lineJoin = 'round';
    this.ctx.strokeStyle = '#13233f';
    if (avant) this.ctx.putImageData(avant, 0, 0);
    this.trace = avant;
  }

  private position(e: PointerEvent): { x: number; y: number; t: number } {
    const r = this.toile().nativeElement.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top, t: e.timeStamp };
  }

  private readonly poser = (e: PointerEvent) => {
    e.preventDefault();
    this.toile().nativeElement.setPointerCapture(e.pointerId);
    const p = this.position(e);
    this.points = [p];
    this.largeur = 2.6;
    this.ctx.beginPath();
    this.ctx.fillStyle = '#13233f';
    this.ctx.arc(p.x, p.y, 1.3, 0, Math.PI * 2);
    this.ctx.fill();
  };

  private readonly bouger = (e: PointerEvent) => {
    if (!this.points.length) return;
    e.preventDefault();
    const p = this.position(e);
    const a = this.points[this.points.length - 1];
    const d = Math.hypot(p.x - a.x, p.y - a.y);
    if (d < 1.2) return;
    // Plus le doigt va vite, plus le trait s'affine, comme à l'encre.
    const vitesse = d / Math.max(1, p.t - a.t);
    const cible = Math.max(1.2, Math.min(3.4, 3.6 - vitesse * 1.4));
    this.largeur = this.largeur * 0.7 + cible * 0.3;
    this.points.push(p);
    const n = this.points.length;
    this.ctx.lineWidth = this.largeur;
    this.ctx.beginPath();
    if (n >= 3) {
      const p0 = this.points[n - 3], p1 = this.points[n - 2];
      this.ctx.moveTo((p0.x + p1.x) / 2, (p0.y + p1.y) / 2);
      this.ctx.quadraticCurveTo(p1.x, p1.y, (p1.x + p.x) / 2, (p1.y + p.y) / 2);
    } else {
      this.ctx.moveTo(a.x, a.y);
      this.ctx.lineTo(p.x, p.y);
    }
    this.ctx.stroke();
    if (this.vide()) {
      this.vide.set(false);
      this.change.emit(true);
    }
  };

  private readonly lever = () => {
    this.points = [];
  };
}
