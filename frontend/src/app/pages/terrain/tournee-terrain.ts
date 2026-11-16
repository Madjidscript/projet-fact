import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, ViewEncapsulation, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import * as L from 'leaflet';
import { firstValueFrom } from 'rxjs';
import { Icone } from '../../composants/icone';
import { animationsReduites } from '../../composants/mouvement';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { EtapeTournee, MOTIFS, TerrainService, Tournee, distanceM, maPosition } from '../../noyau/terrain.service';
import { EnteteTerrain } from './entete-terrain';

interface Suggestion { idBatiment: number; batiment: string; quartier: string | null; commune: string; motif: string; raison: string; }

/**
 * La tournée du jour sur le téléphone (§ 4.6) : la carte trace l'itinéraire d'étape en étape
 * (§ 6.2), la liste dit quoi faire où ; sur place, « Je suis sur place » pointe l'étape avec la
 * position du téléphone (la distance au bâtiment prouve le passage) et un compte rendu. À la fin,
 * le compte rendu de la tournée part aux gestionnaires. Tout fonctionne sans réseau.
 */
@Component({
  selector: 'fact-tournee-terrain',
  imports: [FormsModule, RouterLink, Icone, EnteteTerrain],
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="tr">
      <fact-entete-terrain titre="Ma tournée" retour="/terrain" />

      @if (tournee(); as t) {
        <section class="tt-tete">
          <p>{{ t.reference }} · {{ t.statut === 'TERMINEE' ? 'terminée' : t.statut === 'EN_COURS' ? 'en cours' : 'prévue' }}</p>
          <h1>{{ t.pointees }} étape{{ t.pointees > 1 ? 's' : '' }} faite{{ t.pointees > 1 ? 's' : '' }} sur {{ t.etapes }}</h1>
          <div class="tt-barre"><span [style.width.%]="t.etapes ? (t.pointees / t.etapes) * 100 : 0"></span></div>
          <p class="tt-trajet"><fact-icone nom="carte" [taille]="16" /> Environ {{ km() }} km · {{ duree() }} de route, d'après la carte</p>
          @if (t.consigne) { <p class="tt-consigne"><fact-icone nom="info" [taille]="16" /> {{ t.consigne }}</p> }
        </section>

        <div class="carte tt-carte"><div #carte class="tt-leaflet"></div></div>
        <div class="tt-outils">
          <button type="button" class="btn petit" [class.occupe]="calcul()" (click)="depuisMaPosition()"><fact-icone nom="cible" [taille]="16" /> Itinéraire depuis ma position</button>
        </div>

        <ol class="tt-etapes">
          @for (e of etapes(); track e.id; let i = $index) {
            <li class="carte" [class.faite]="e.pointeeLe" [class.prochaine]="e.id === prochaine()?.id" [style.--rang]="i" [id]="'etape-' + e.id">
              <div class="tt-ligne">
                <span class="tt-num">@if (e.pointeeLe) { <fact-icone nom="coche" [taille]="16" /> } @else { {{ i + 1 }} }</span>
                <div class="tt-quoi">
                  <strong>{{ e.batiment }}</strong>
                  <small>{{ e.quartier ? e.quartier + ', ' : '' }}{{ e.commune }}{{ e.adresse ? ' · ' + e.adresse : '' }}</small>
                </div>
                <span class="pastille" [class]="ton(e.motif)">{{ motifs[e.motif] ?? e.motif }}</span>
              </div>
              @if (e.consigne) { <p class="tt-raison">{{ e.consigne }}</p> }
              @if (e.pointeeLe) {
                <p class="tt-fait" [class.loin]="(e.distanceM ?? 0) > 250">
                  {{ e.resultat === 'ABSENT' ? 'Personne sur place' : e.resultat === 'REPORTE' ? 'Reportée' : 'Faite' }} à {{ heure(e.pointeeLe) }}
                  @if (e.distanceM !== null) { · à {{ distance(e.distanceM) }} du bâtiment }
                  @if (e.enAttente) { · en attente d'envoi }
                  @if (e.compteRendu) { <br><em>« {{ e.compteRendu }} »</em> }
                </p>
              } @else if (pointage()?.id === e.id) {
                <div class="tt-pointage">
                  <p class="tr-precision" [class.bonne]="(ecart() ?? 9999) <= 100" [class.moyenne]="(ecart() ?? 9999) > 100 && (ecart() ?? 9999) <= 250">
                    <fact-icone nom="cible" [taille]="18" />
                    @if (position()) { Vous êtes à {{ ecart() }} m du bâtiment{{ (ecart() ?? 0) > 250 ? ' : rapprochez-vous si possible' : '' }} }
                    @else { Recherche de votre position… }
                  </p>
                  <div class="tt-resultats" role="radiogroup" aria-label="Résultat de la visite">
                    <button type="button" role="radio" [attr.aria-checked]="resultat === 'FAIT'" [class.actif]="resultat === 'FAIT'" (click)="resultat = 'FAIT'">Visite faite</button>
                    <button type="button" role="radio" [attr.aria-checked]="resultat === 'ABSENT'" [class.actif]="resultat === 'ABSENT'" (click)="resultat = 'ABSENT'">Personne</button>
                    <button type="button" role="radio" [attr.aria-checked]="resultat === 'REPORTE'" [class.actif]="resultat === 'REPORTE'" (click)="resultat = 'REPORTE'">Reportée</button>
                  </div>
                  <label class="champ"><textarea name="cr" placeholder=" " rows="3" [(ngModel)]="compteRendu"></textarea>
                    <span>{{ resultat === 'FAIT' ? 'Ce que vous avez fait ou constaté' : 'Pourquoi ? (obligatoire)' }}</span></label>
                  <div class="tt-raccourcis">
                    <a class="btn petit" [routerLink]="['/terrain/encaissement']" [queryParams]="{ batiment: e.idBatiment }"><fact-icone nom="monnaie" [taille]="16" /> Encaisser ici</a>
                    <a class="btn petit" [routerLink]="['/terrain/etat-des-lieux']" [queryParams]="{ batiment: e.idBatiment }"><fact-icone nom="document" [taille]="16" /> État des lieux ici</a>
                  </div>
                  <div class="tt-boutons">
                    <button type="button" class="btn" (click)="pointage.set(null)">Annuler</button>
                    <button type="button" class="btn principal" [class.occupe]="envoi()" [disabled]="envoi()" (click)="pointer(e)"><fact-icone nom="coche" [taille]="18" /> Valider le pointage</button>
                  </div>
                </div>
              } @else if (t.statut !== 'TERMINEE') {
                <div class="tt-boutons">
                  @if (e.latitude !== null) {
                    <a class="btn petit" [href]="itineraire(e)" target="_blank" rel="noopener"><fact-icone nom="carte" [taille]="16" /> Y aller</a>
                  }
                  <button type="button" class="btn petit principal" (click)="surPlace(e)"><fact-icone nom="cible" [taille]="16" /> Je suis sur place</button>
                </div>
              }
            </li>
          }
        </ol>

        @if (t.statut === 'TERMINEE') {
          <section class="carte tt-fin">
            <h2><fact-icone nom="coche" [taille]="20" /> Tournée terminée</h2>
            <p>{{ t.compteRendu }}</p>
          </section>
        } @else {
          <section class="tr-bloc tt-fin">
            <h2>Compte rendu de la tournée</h2>
            <label class="champ"><textarea name="crTournee" placeholder=" " rows="4" [(ngModel)]="compteRenduTournee"></textarea>
              <span>Ce qui a été fait, ce qui reste, ce que le gestionnaire doit savoir</span></label>
            @if (t.pointees < t.etapes) { <p class="tr-aide">{{ t.etapes - t.pointees }} étape(s) non pointée(s) : dites pourquoi dans le compte rendu.</p> }
            <button type="button" class="btn principal grand" [class.occupe]="envoi()" [disabled]="envoi()" (click)="terminer(t)"><fact-icone nom="coche" [taille]="18" /> Terminer la tournée</button>
          </section>
        }
      } @else if (prete()) {
        <section class="tr-bloc">
          <h2>Pas de tournée aujourd'hui</h2>
          @if (!terrain.enLigne()) {
            <p class="tr-aide">Préparez votre tournée quand le réseau sera revenu : il faut la liste à jour des bâtiments à visiter.</p>
          } @else {
            <p class="tr-aide">Votre gestionnaire n'a pas prévu de tournée. Choisissez les bâtiments à visiter : l'ordre de passage est calculé pour vous.</p>
            <ul class="tt-suggestions">
              @for (s of suggestions(); track s.idBatiment) {
                <li>
                  <label class="carte" [class.choisi]="choisis().has(s.idBatiment)">
                    <input type="checkbox" [checked]="choisis().has(s.idBatiment)" (change)="basculer(s.idBatiment)">
                    <span><strong>{{ s.batiment }}</strong><small>{{ s.quartier ? s.quartier + ', ' : '' }}{{ s.commune }} — {{ s.raison }}</small></span>
                    <span class="pastille" [class]="ton(s.motif)">{{ motifs[s.motif] }}</span>
                  </label>
                </li>
              } @empty {
                <li class="tr-aide">Rien de pressant dans votre secteur.</li>
              }
            </ul>
          }
        </section>
        @if (choisis().size) {
          <footer class="tr-pied">
            <button type="button" class="btn principal grand" [class.occupe]="envoi()" [disabled]="envoi()" (click)="preparer()">
              <fact-icone nom="carte" [taille]="18" /> Préparer ma tournée ({{ choisis().size }})</button>
          </footer>
        }
      }
    </div>
  `,
  styles: [`
    .tt-tete { padding: 14px 2px 6px; animation: montee var(--longue) var(--courbe) both; }
    .tt-tete p { margin: 0; font-size: 13px; color: var(--discret); }
    .tt-tete h1 { margin: 2px 0 8px; font-size: 22px; color: var(--marque); }
    .tt-barre { height: 8px; border-radius: 99px; background: var(--surface-3); overflow: hidden; }
    .tt-barre span { display: block; height: 100%; border-radius: inherit; background: var(--ocre); transition: width 800ms var(--courbe); }
    .tt-trajet, .tt-consigne { display: flex; align-items: center; gap: 6px; margin-top: 8px !important; color: var(--encre-2) !important; }
    .tt-carte { padding: 0; overflow: hidden; margin-top: 10px; }
    .tt-leaflet { height: 300px; z-index: 0; }
    .tt-outils { display: flex; justify-content: flex-end; margin: 8px 0 4px; }
    .tt-marqueur { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 50%; border: 3px solid #fff; background: var(--ocre); color: #fff;
                   font: 700 13px var(--titre); box-shadow: 0 3px 10px rgba(0, 0, 0, .35); animation: tt-tombe 450ms var(--courbe) both; }
    .tt-marqueur.faite { background: var(--bon); }
    .tt-marqueur.depart { background: #13233f; font-size: 11px; }
    @keyframes tt-tombe { from { opacity: 0; transform: translateY(-16px) scale(.6); } }
    .tt-trait { stroke-dasharray: 1; stroke-dashoffset: 1; animation: tt-trace 520ms ease-out forwards; }
    @keyframes tt-trace { to { stroke-dashoffset: 0; } }
    .tt-etapes { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 10px; }
    .tt-etapes > li { display: grid; gap: 8px; animation: montee var(--standard) var(--courbe) both; animation-delay: calc(var(--rang) * 50ms);
                      transition: border-color var(--courte) var(--courbe); }
    .tt-etapes > li.prochaine { border-color: var(--ocre); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ocre) 18%, transparent); }
    .tt-etapes > li.faite { background: color-mix(in srgb, var(--bon-clair) 45%, var(--surface)); }
    .tt-ligne { display: flex; align-items: flex-start; gap: 10px; }
    .tt-num { width: 30px; height: 30px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--surface-3); font: 700 13px var(--titre); }
    .faite .tt-num { background: var(--bon); color: #fff; }
    .prochaine .tt-num { background: var(--ocre); color: #fff; }
    .tt-quoi { flex: 1; display: grid; min-width: 0; }
    .tt-quoi small { color: var(--discret); font-size: 12.5px; }
    .tt-raison { margin: 0; font-size: 13.5px; color: var(--encre-2); }
    .tt-fait { margin: 0; font-size: 13px; color: var(--bon-texte); }
    .tt-fait.loin { color: var(--attention-texte); }
    .tt-pointage { display: grid; gap: 10px; animation: montee var(--standard) var(--courbe) both; }
    .tt-resultats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
    .tt-resultats button { min-height: 46px; border-radius: var(--rayon-2); border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre);
                           font: 600 13.5px var(--texte); cursor: pointer; transition: all var(--courte) var(--courbe); }
    .tt-resultats button.actif { background: var(--ocre); border-color: var(--ocre); color: #fff; }
    .tt-raccourcis, .tt-boutons { display: flex; flex-wrap: wrap; gap: 6px; }
    .tt-boutons { justify-content: flex-end; }
    .tt-fin { margin-top: 18px; display: grid; gap: 10px; }
    .tt-fin h2 { display: flex; align-items: center; gap: 8px; }
    .tt-suggestions { list-style: none; margin: 10px 0 0; padding: 0; display: grid; gap: 8px; }
    .tt-suggestions label { display: flex; align-items: center; gap: 10px; cursor: pointer; }
    .tt-suggestions label.choisi { border-color: var(--ocre); }
    .tt-suggestions input { width: 22px; height: 22px; flex: none; accent-color: var(--ocre); }
    .tt-suggestions span:nth-child(2) { flex: 1; display: grid; min-width: 0; }
    .tt-suggestions small { color: var(--discret); font-size: 12.5px; }
    @media (prefers-reduced-motion: reduce) { .tt-trait { animation: none; stroke-dashoffset: 0; } .tt-marqueur { animation: none; } }
  `],
})
export class TourneeTerrain implements OnInit, OnDestroy {
  protected readonly terrain = inject(TerrainService);
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);
  private readonly hote = viewChild<ElementRef<HTMLElement>>('carte');

  protected readonly motifs = MOTIFS;
  protected readonly tournee = computed<Tournee | null>(() => this.terrain.preparation()?.tournee ?? null);
  protected readonly prete = signal(false);
  /** L'ordre affiché : celui de l'agence, ou recalculé depuis la position du téléphone. */
  private readonly ordre = signal<number[] | null>(null);
  protected readonly etapes = computed(() => {
    const l = this.tournee()?.etapesDetail ?? [];
    const o = this.ordre();
    return o ? [...l].sort((a, b) => o.indexOf(a.id) - o.indexOf(b.id)) : l;
  });
  protected readonly prochaine = computed(() => this.etapes().find((e) => !e.pointeeLe) ?? null);
  private readonly depart = signal<{ latitude: number; longitude: number } | null>(null);
  protected readonly km = computed(() => {
    const t = this.tournee();
    if (!t) return '0';
    let d = 0;
    let p = this.depart() ?? t.depart;
    for (const e of this.etapes()) {
      if (e.latitude === null || e.longitude === null) continue;
      d += distanceM(p.latitude, p.longitude, e.latitude, e.longitude);
      p = { latitude: e.latitude, longitude: e.longitude };
    }
    // Les rues ne vont pas en ligne droite : on compte un tiers de plus.
    return (d * 1.3 / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 1 });
  });
  protected readonly duree = computed(() => {
    const min = Math.round(Number(this.km().replace(',', '.')) / 22 * 60);
    return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`;
  });

  protected readonly pointage = signal<EtapeTournee | null>(null);
  protected readonly position = signal<GeolocationPosition | null>(null);
  protected readonly ecart = computed(() => {
    const p = this.position(), e = this.pointage();
    if (!p || !e || e.latitude === null || e.longitude === null) return null;
    return Math.round(distanceM(p.coords.latitude, p.coords.longitude, e.latitude, e.longitude));
  });
  protected resultat: 'FAIT' | 'ABSENT' | 'REPORTE' = 'FAIT';
  protected compteRendu = '';
  protected compteRenduTournee = '';
  protected readonly envoi = signal(false);
  protected readonly calcul = signal(false);
  protected readonly suggestions = signal<Suggestion[]>([]);
  protected readonly choisis = signal<Set<number>>(new Set());

  private carte: L.Map | null = null;
  private couche: L.LayerGroup | null = null;
  private suivi: number | null = null;
  private dessine = '';

  constructor() {
    effect(() => {
      const etapes = this.etapes();
      this.depart();
      untracked(() => setTimeout(() => this.dessiner(etapes)));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.terrain.preparer();
    this.prete.set(true);
    if (!this.tournee() && this.terrain.enLigne()) {
      try {
        this.suggestions.set(await firstValueFrom(this.http.get<Suggestion[]>('/api/tournees/suggestions')));
      } catch { /* sans suggestions, l'écran le dit */ }
    }
  }

  ngOnDestroy(): void {
    if (this.suivi !== null) navigator.geolocation?.clearWatch(this.suivi);
    this.carte?.remove();
  }

  protected ton(motif: string): string {
    return motif === 'RECOUVREMENT' ? 'critique' : motif === 'ETAT_DES_LIEUX' ? 'info' : motif === 'CONTROLE' ? 'attention' : 'neutre';
  }

  protected distance(m: number): string {
    return m < 1000 ? `${m} m` : `${(m / 1000).toLocaleString('fr-FR', { maximumFractionDigits: 1 })} km`;
  }

  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  protected itineraire(e: EtapeTournee): string {
    return `https://www.google.com/maps/dir/?api=1&destination=${e.latitude},${e.longitude}&travelmode=driving`;
  }

  // ------------------------------------------------------------------ carte
  private dessiner(etapes: EtapeTournee[]): void {
    const hote = this.hote()?.nativeElement;
    const t = this.tournee();
    if (!hote || !t) return;
    const cle = JSON.stringify([etapes.map((e) => [e.id, !!e.pointeeLe]), this.depart()]);
    if (cle === this.dessine && this.carte) return;
    this.dessine = cle;
    if (!this.carte) {
      this.carte = L.map(hote, { zoomControl: true, attributionControl: true });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
      this.couche = L.layerGroup().addTo(this.carte);
    }
    this.couche!.clearLayers();
    const d = this.depart() ?? t.depart;
    const points: L.LatLngTuple[] = [[d.latitude, d.longitude]];
    L.marker([d.latitude, d.longitude], { icon: L.divIcon({ className: '', html: `<span class="tt-marqueur depart">${this.depart() ? 'Moi' : 'FACT'}</span>`,
      iconSize: [30, 30], iconAnchor: [15, 15] }), title: this.depart() ? 'Ma position' : 'Agence' }).addTo(this.couche!);
    const reduit = animationsReduites();
    etapes.forEach((e, i) => {
      if (e.latitude === null || e.longitude === null) return;
      points.push([e.latitude, e.longitude]);
      L.marker([e.latitude, e.longitude], {
        title: e.batiment,
        icon: L.divIcon({ className: '', html: `<span class="tt-marqueur ${e.pointeeLe ? 'faite' : ''}" style="animation-delay:${reduit ? 0 : 300 + i * 420}ms">${i + 1}</span>`,
          iconSize: [30, 30], iconAnchor: [15, 15] }),
      }).on('click', () => document.getElementById('etape-' + e.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })).addTo(this.couche!);
    });
    // L'itinéraire se trace d'étape en étape (§ 6.2) : un segment après l'autre.
    for (let i = 1; i < points.length; i++) {
      const fait = etapes[i - 1]?.pointeeLe;
      const seg = L.polyline([points[i - 1], points[i]], { color: fait ? '#2e9e6b' : '#2353a6', weight: 4, opacity: .85,
        dashArray: undefined, className: reduit ? '' : 'tt-trait' }).addTo(this.couche!);
      const el = seg.getElement() as SVGPathElement | undefined;
      if (el && !reduit) {
        el.setAttribute('pathLength', '1');
        el.style.animationDelay = `${(i - 1) * 420}ms`;
      }
    }
    if (points.length > 1) this.carte.fitBounds(L.latLngBounds(points), { padding: [28, 28], maxZoom: 15 });
    else this.carte.setView(points[0], 13);
    setTimeout(() => this.carte?.invalidateSize(), 200);
  }

  /** Recalcule l'ordre de passage au plus proche, depuis la position du téléphone. */
  protected async depuisMaPosition(): Promise<void> {
    this.calcul.set(true);
    try {
      const p = await maPosition();
      let lat = p.coords.latitude, lon = p.coords.longitude;
      this.depart.set({ latitude: lat, longitude: lon });
      const restants = (this.tournee()?.etapesDetail ?? []).filter((e) => !e.pointeeLe && e.latitude !== null);
      const faites = (this.tournee()?.etapesDetail ?? []).filter((e) => e.pointeeLe || e.latitude === null).map((e) => e.id);
      const ordre: number[] = [];
      while (restants.length) {
        let k = 0, best = Infinity;
        restants.forEach((e, i) => {
          const d = distanceM(lat, lon, e.latitude!, e.longitude!);
          if (d < best) { best = d; k = i; }
        });
        const [e] = restants.splice(k, 1);
        ordre.push(e.id);
        lat = e.latitude!;
        lon = e.longitude!;
      }
      this.ordre.set([...faites, ...ordre]);
      this.avis.succes('Itinéraire recalculé depuis votre position.');
    } catch (e) {
      this.avis.erreur(e instanceof Error ? e.message : 'Position introuvable.');
    } finally {
      this.calcul.set(false);
    }
  }

  // ------------------------------------------------------------------ pointage
  protected surPlace(e: EtapeTournee): void {
    this.pointage.set(e);
    this.resultat = 'FAIT';
    this.compteRendu = '';
    this.position.set(null);
    if (this.suivi !== null) navigator.geolocation?.clearWatch(this.suivi);
    this.suivi = navigator.geolocation?.watchPosition((p) => this.position.set(p), () => undefined,
      { enableHighAccuracy: true, maximumAge: 10_000, timeout: 30_000 }) ?? null;
  }

  protected async pointer(e: EtapeTournee): Promise<void> {
    const t = this.tournee();
    if (!t || this.envoi()) return;
    if (this.resultat !== 'FAIT' && !this.compteRendu.trim()) {
      this.avis.erreur('Dites en quelques mots pourquoi la visite n\'a pas eu lieu.');
      return;
    }
    this.envoi.set(true);
    try {
      const p = this.position();
      const cle = this.terrain.nouvelleCle();
      await this.terrain.mettreEnFile({
        cle, genre: 'pointage', libelle: `Pointage : ${e.batiment}`, detail: t.reference,
        donnees: { idTournee: t.id, idEtape: e.id, distanceM: this.ecart(), corps: { pointeeLe: new Date().toISOString(),
          latitude: p?.coords.latitude ?? null, longitude: p?.coords.longitude ?? null, precision: p?.coords.accuracy ?? null,
          resultat: this.resultat, compteRendu: this.compteRendu.trim() || null } },
      });
      if (this.suivi !== null) navigator.geolocation?.clearWatch(this.suivi);
      this.suivi = null;
      this.pointage.set(null);
      this.avis.succes(`${e.batiment} : étape pointée.`);
      setTimeout(() => { const n = this.prochaine(); if (n) document.getElementById('etape-' + n.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); }, 300);
    } catch {
      this.avis.erreur('Le téléphone n\'a pas pu garder ce pointage.');
    } finally {
      this.envoi.set(false);
    }
  }

  protected async terminer(t: Tournee): Promise<void> {
    if (!this.compteRenduTournee.trim()) {
      this.avis.erreur('Écrivez le compte rendu de la tournée.');
      return;
    }
    this.envoi.set(true);
    try {
      await this.terrain.mettreEnFile({ cle: this.terrain.nouvelleCle(), genre: 'fin-tournee', libelle: `Fin de la tournée ${t.reference}`,
        detail: `${t.pointees}/${t.etapes} étapes`, donnees: { idTournee: t.id, compteRendu: this.compteRenduTournee.trim() } });
      this.avis.succes('Tournée terminée : le compte rendu part aux gestionnaires.');
    } catch {
      this.avis.erreur('Le téléphone n\'a pas pu garder le compte rendu.');
    } finally {
      this.envoi.set(false);
    }
  }

  // ------------------------------------------------------------------ préparer sa tournée
  protected basculer(id: number): void {
    this.choisis.update((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id); else n.add(id);
      return n;
    });
  }

  protected async preparer(): Promise<void> {
    this.envoi.set(true);
    try {
      const etapes = this.suggestions().filter((s) => this.choisis().has(s.idBatiment))
        .map((s) => ({ idBatiment: s.idBatiment, motif: s.motif, consigne: s.raison }));
      await firstValueFrom(this.http.post('/api/tournees', { etapes }));
      await this.terrain.preparer();
      this.avis.succes('Tournée préparée : l\'ordre de passage est calculé.');
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.envoi.set(false);
    }
  }
}
