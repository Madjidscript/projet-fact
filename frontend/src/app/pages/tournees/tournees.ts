import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, ViewEncapsulation, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import * as L from 'leaflet';
import { firstValueFrom } from 'rxjs';
import { Choix, OptionChoix, OptionsPipe } from '../../composants/choix';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { animationsReduites } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { Batiment } from '../../noyau/modeles';
import { OPTIONS } from '../../noyau/options';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { EtapeTournee, MOTIFS, Tournee } from '../../noyau/terrain.service';

interface Resume { id: number; reference: string; idAgent: number; agent: string; jour: string; statut: string; consigne: string | null;
  compteRendu: string | null; etapes: number; pointees: number; creePar: string; termineeLe: string | null; }
interface Agent { id: number; nom: string; profil: string; identifiant: string; }
interface Suggestion { idBatiment: number; batiment: string; quartier: string | null; commune: string; motif: string; raison: string; }
interface EtapeSaisie { idBatiment: number; batiment: string; detail: string; motif: string; consigne: string; }

function aujourdhui(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Les tournées des agents, côté bureau (§ 4.6) : le gestionnaire prépare la tournée d'un agent
 * à partir des bâtiments à visiter (loyers en retard, états des lieux à faire, fiches à
 * contrôler…) ; il suit l'avancement dans la journée et lit le compte rendu, étape par étape.
 */
@Component({
  selector: 'fact-tournees',
  imports: [FormsModule, RouterLink, Icone, Choix, OptionsPipe, EtatVide, PanneauLateral],
  encapsulation: ViewEncapsulation.None,
  template: `
    <div class="to">
      <header class="to-entete">
        <div>
          <h1>Tournées</h1>
          <p class="to-accroche">Les visites des agents sur le terrain, jour par jour</p>
        </div>
        <div class="to-actions">
          <label class="champ to-jour"><input type="date" name="jour" [ngModel]="jour()" (ngModelChange)="changerJour($event)"><span>Jour</span></label>
          <button type="button" class="btn principal" (click)="preparer()"><fact-icone nom="plus" /> Préparer une tournée</button>
        </div>
      </header>

      @if (chargement()) {
        <div class="to-grille">@for (i of [1, 2, 3]; track i) { <div class="carte to-squelette"></div> }</div>
      } @else {
        <div class="to-grille">
          @for (t of tournees(); track t.id; let i = $index) {
            <button type="button" class="carte to-carte" [style.--rang]="i" (click)="ouvrir(t.id)">
              <span class="to-haut">
                <span class="to-avatar">{{ initiales(t.agent) }}</span>
                <span class="to-qui"><strong>{{ t.agent }}</strong><small class="donnee">{{ t.reference }}</small></span>
                <span class="pastille" [class]="t.statut === 'TERMINEE' ? 'bon' : t.statut === 'EN_COURS' ? 'info' : 'neutre'">{{ statuts[t.statut] }}</span>
              </span>
              <span class="to-barre"><span [style.width.%]="t.etapes ? t.pointees / t.etapes * 100 : 0"></span></span>
              <small>{{ t.pointees }} étape(s) faite(s) sur {{ t.etapes }}</small>
              @if (t.compteRendu) { <p class="to-cr">« {{ t.compteRendu }} »</p> }
              @else if (t.consigne) { <p class="to-cr discret">{{ t.consigne }}</p> }
            </button>
          } @empty {
            <fact-etat-vide icone="cible" titre="Aucune tournée ce jour-là" message="Préparez la tournée d'un agent : l'ordre de passage est calculé pour lui.">
              <button type="button" class="btn principal" (click)="preparer()">Préparer une tournée</button>
            </fact-etat-vide>
          }
        </div>
      }
    </div>

    <!-- --------------------------------------------------------------- détail -->
    <fact-panneau-lateral [page]="true" [titre]="detail() ? 'Tournée ' + detail()!.reference : 'Tournée'" [ouvert]="vue() === 'detail'" (fermer)="fermer()">
      @if (detail(); as t) {
        <p class="to-entete-detail"><strong>{{ t.agent }}</strong> · {{ jourEnClair(t.jour) }} · {{ statuts[t.statut] }}</p>
        <div class="carte to-carte-map"><div #carte class="to-leaflet"></div></div>
        <ol class="to-etapes">
          @for (e of t.etapesDetail; track e.id; let i = $index) {
            <li [class.faite]="e.pointeeLe" [style.--rang]="i">
              <span class="to-num">{{ i + 1 }}</span>
              <div>
                <strong><a [routerLink]="['/patrimoine', e.idBatiment]">{{ e.batiment }}</a></strong>
                <small>{{ motifs[e.motif] }}{{ e.consigne ? ' — ' + e.consigne : '' }}</small>
                @if (e.pointeeLe) {
                  <small class="to-res" [class.loin]="(e.distanceM ?? 0) > 250">
                    {{ e.resultat === 'ABSENT' ? 'Personne sur place' : e.resultat === 'REPORTE' ? 'Reportée' : 'Faite' }} à {{ heure(e.pointeeLe) }}
                    @if (e.distanceM !== null) { · pointée à {{ e.distanceM }} m du bâtiment{{ e.distanceM > 250 ? ' (loin)' : '' }} }
                  </small>
                  @if (e.compteRendu) { <small class="to-cr-etape">« {{ e.compteRendu }} »</small> }
                } @else {
                  <small class="discret">Pas encore pointée</small>
                }
              </div>
            </li>
          }
        </ol>
        @if (t.compteRendu) {
          <h3 class="to-h3">Compte rendu</h3>
          <p class="to-cr-final">{{ t.compteRendu }}</p>
        }
        @if (t.statut !== 'TERMINEE') {
          <div class="pied"><button type="button" class="btn" (click)="modifier(t)"><fact-icone nom="crayon" [taille]="16" /> Modifier les étapes</button></div>
        }
      }
    </fact-panneau-lateral>

    <!-- ---------------------------------------------------------- préparation -->
    <fact-panneau-lateral [page]="true" titre="Préparer une tournée" [ouvert]="vue() === 'preparer'" (fermer)="fermer()">
      <form class="to-form" (ngSubmit)="enregistrer()" novalidate>
        <fact-choix name="agent" libelle="Agent" [seuilRecherche]="0" [options]="agents() | options : optionAgent" [(ngModel)]="s.idAgent" />
        <label class="champ"><input type="date" name="jourTournee" [min]="aujourdhui" [(ngModel)]="s.jour"><span>Jour de la tournée</span></label>
        <label class="champ"><input type="text" name="consigne" placeholder=" " maxlength="500" [(ngModel)]="s.consigne" autocomplete="off"><span>Consigne pour l'agent (facultatif)</span></label>

        <h3 class="to-h3">Étapes <small>{{ etapesSaisie().length }}</small></h3>
        <ul class="to-saisie">
          @for (e of etapesSaisie(); track e.idBatiment; let i = $index) {
            <li class="carte">
              <div><strong>{{ e.batiment }}</strong><small>{{ e.detail }}</small></div>
              <select [name]="'motif' + i" [(ngModel)]="e.motif" aria-label="Motif de la visite">
                @for (m of listeMotifs; track m[0]) { <option [value]="m[0]">{{ m[1] }}</option> }
              </select>
              <button type="button" class="btn discret petit" (click)="retirer(e.idBatiment)" [attr.aria-label]="'Retirer ' + e.batiment"><fact-icone nom="fermer" [taille]="16" /></button>
            </li>
          } @empty {
            <li class="discret">Cochez des bâtiments ci-dessous, ou ajoutez-en un.</li>
          }
        </ul>
        <fact-choix name="ajout" libelle="Ajouter un bâtiment" vide="Choisir…" [options]="batiments() | options : opt.batiment" [ngModel]="null" (ngModelChange)="ajouter($event)" />

        <h3 class="to-h3">À visiter en priorité</h3>
        <ul class="to-suggestions">
          @for (x of suggestions(); track x.idBatiment) {
            <li>
              <label [class.choisi]="dans(x.idBatiment)">
                <input type="checkbox" [checked]="dans(x.idBatiment)" (change)="basculer(x)">
                <span><strong>{{ x.batiment }}</strong><small>{{ x.raison }}</small></span>
                <span class="pastille" [class]="ton(x.motif)">{{ motifs[x.motif] }}</span>
              </label>
            </li>
          } @empty {
            <li class="discret">Rien de pressant dans votre portefeuille.</li>
          }
        </ul>
        @if (erreur()) { <p class="message-erreur" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
        <div class="pied">
          <button type="button" class="btn" (click)="fermer()">Annuler</button>
          <button type="submit" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe()">Enregistrer la tournée</button>
        </div>
      </form>
    </fact-panneau-lateral>
  `,
  styles: [`
    fact-tournees { display: block; }
    .to { max-width: 1240px; margin: 0 auto; padding: 24px 16px 72px; }
    .to-entete { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 14px; animation: montee var(--longue) var(--courbe) both; }
    .to-entete h1 { font-size: clamp(25px, 5vw, 34px); color: var(--marque); }
    .to-accroche { margin: 4px 0 0; color: var(--encre-2); }
    .to-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .to-jour { width: 180px; }
    .to-grille { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 14px; margin-top: 18px; }
    .to-squelette { height: 150px; animation: pulse 1.2s ease-in-out infinite; }
    .to-carte { display: grid; gap: 10px; text-align: left; font: inherit; color: inherit; cursor: pointer; animation: montee var(--standard) var(--courbe) both;
                animation-delay: calc(var(--rang) * 60ms); transition: transform var(--courte) var(--courbe), border-color var(--courte) var(--courbe); }
    .to-carte:hover { transform: translateY(-3px); border-color: var(--ocre); }
    .to-haut { display: flex; align-items: center; gap: 10px; }
    .to-avatar { width: 42px; height: 42px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--ocre); color: #fff; font: 700 15px var(--titre); }
    .to-qui { flex: 1; display: grid; min-width: 0; }
    .to-qui small { color: var(--discret); }
    .to-barre { height: 8px; border-radius: 99px; background: var(--surface-3); overflow: hidden; }
    .to-barre span { display: block; height: 100%; background: var(--ocre); border-radius: inherit; animation: to-remplit 900ms var(--courbe) both; transform-origin: left; }
    @keyframes to-remplit { from { transform: scaleX(0); } }
    .to-carte > small { color: var(--encre-2); }
    .to-cr { margin: 0; font-size: 13.5px; font-style: italic; color: var(--encre-2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .to-entete-detail { margin: 0 0 12px; color: var(--encre-2); }
    .to-carte-map { padding: 0; overflow: hidden; }
    .to-leaflet { height: 300px; z-index: 0; }
    .to-marqueur { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 50%; border: 3px solid #fff; background: var(--ocre); color: #fff;
                   font: 700 12px var(--titre); box-shadow: 0 3px 10px rgba(0, 0, 0, .35); animation: to-tombe 450ms var(--courbe) both; }
    .to-marqueur.faite { background: var(--bon); }
    .to-marqueur.depart { background: #13233f; font-size: 10px; }
    @keyframes to-tombe { from { opacity: 0; transform: translateY(-14px) scale(.6); } }
    .to-trait { stroke-dasharray: 1; stroke-dashoffset: 1; animation: to-trace 520ms ease-out forwards; }
    @keyframes to-trace { to { stroke-dashoffset: 0; } }
    .to-etapes { list-style: none; margin: 14px 0 0; padding: 0; display: grid; gap: 4px; }
    .to-etapes li { display: flex; gap: 10px; padding: 10px; border-radius: var(--rayon-2); animation: montee var(--standard) var(--courbe) both; animation-delay: calc(var(--rang) * 50ms); }
    .to-etapes li.faite { background: color-mix(in srgb, var(--bon-clair) 50%, transparent); }
    .to-etapes li > div { display: grid; gap: 2px; min-width: 0; }
    .to-etapes small { color: var(--encre-2); font-size: 13px; }
    .to-num { width: 26px; height: 26px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--surface-3); font: 700 12px var(--titre); }
    .faite .to-num { background: var(--bon); color: #fff; }
    .to-res { color: var(--bon-texte) !important; }
    .to-res.loin { color: var(--attention-texte) !important; }
    .to-cr-etape { font-style: italic; }
    .to-h3 { margin: 18px 0 8px; font-size: 15px; display: flex; gap: 8px; align-items: baseline; }
    .to-h3 small { color: var(--discret); font-weight: 500; }
    .to-cr-final { margin: 0; padding: 12px; border-radius: var(--rayon-2); background: var(--surface-2); white-space: pre-line; }
    .to-form { display: grid; gap: 12px; }
    .to-saisie, .to-suggestions { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
    .to-saisie li { display: flex; align-items: center; gap: 8px; padding: 10px 12px; animation: montee var(--standard) var(--courbe) both; }
    .to-saisie li > div { flex: 1; display: grid; min-width: 0; }
    .to-saisie small, .to-suggestions small { color: var(--discret); font-size: 12.5px; }
    .to-saisie select { min-height: 38px; border-radius: 8px; border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre); padding: 0 8px; }
    .to-suggestions label { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: var(--rayon-2); border: 1px solid var(--filet); cursor: pointer;
                            transition: border-color var(--courte) var(--courbe); }
    .to-suggestions label.choisi { border-color: var(--ocre); background: color-mix(in srgb, var(--ocre) 6%, var(--surface)); }
    .to-suggestions input { width: 20px; height: 20px; flex: none; accent-color: var(--ocre); }
    .to-suggestions span:nth-child(2) { flex: 1; display: grid; min-width: 0; }
    .message-erreur { display: flex; gap: 8px; margin: 0; padding: 10px 12px; border-radius: var(--rayon-2); background: var(--critique-clair); color: var(--critique-texte); }
    @media (prefers-reduced-motion: reduce) { .to-trait { animation: none; stroke-dashoffset: 0; } .to-marqueur { animation: none; } }
  `],
})
export class Tournees implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);
  private readonly patrimoine = inject(PatrimoineService);
  private readonly hote = viewChild<ElementRef<HTMLElement>>('carte');
  /** ?tournee=12 : ouverte depuis l'alerte du compte rendu. */
  readonly tourneeAdresse = input<string | undefined>(undefined, { alias: 'tournee' });

  protected readonly motifs = MOTIFS;
  protected readonly listeMotifs = Object.entries(MOTIFS);
  protected readonly statuts: Record<string, string> = { PREVUE: 'Prévue', EN_COURS: 'En cours', TERMINEE: 'Terminée' };
  protected readonly opt = OPTIONS;
  protected readonly aujourdhui = aujourdhui();
  protected readonly jour = signal(aujourdhui());
  protected readonly tournees = signal<Resume[]>([]);
  protected readonly chargement = signal(true);
  protected readonly vue = signal<'detail' | 'preparer' | null>(null);
  protected readonly detail = signal<Tournee | null>(null);
  protected readonly agents = signal<Agent[]>([]);
  protected readonly suggestions = signal<Suggestion[]>([]);
  protected readonly batiments = signal<Batiment[]>([]);
  protected readonly etapesSaisie = signal<EtapeSaisie[]>([]);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected s = { idAgent: null as number | null, jour: aujourdhui(), consigne: '' };
  protected readonly optionAgent = (a: Agent): OptionChoix => ({ valeur: a.id, libelle: a.nom, detail: a.profil === 'AGENT' ? 'Agent de terrain' : 'Gestionnaire' });

  private carte: L.Map | null = null;
  private couche: L.LayerGroup | null = null;

  constructor() {
    effect(() => {
      const t = this.tourneeAdresse();
      if (t) untracked(() => this.ouvrir(Number(t)));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  ngOnDestroy(): void {
    this.carte?.remove();
  }

  protected async changerJour(j: string): Promise<void> {
    if (!j) return;
    this.jour.set(j);
    await this.charger();
  }

  private async charger(): Promise<void> {
    this.chargement.set(true);
    try {
      this.tournees.set(await firstValueFrom(this.http.get<Resume[]>('/api/tournees', { params: { jour: this.jour() } })));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected initiales(n: string): string {
    return n.replace(/^ESSAI\s+/, '').split(/\s+/).filter(Boolean).slice(0, 2).map((m) => m[0]!.toUpperCase()).join('');
  }

  protected ton(motif: string): string {
    return motif === 'RECOUVREMENT' ? 'critique' : motif === 'ETAT_DES_LIEUX' ? 'info' : motif === 'CONTROLE' ? 'attention' : 'neutre';
  }

  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  protected jourEnClair(j: string): string {
    return new Date(j + 'T12:00:00').toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
  }

  protected fermer(): void {
    this.vue.set(null);
  }

  // ------------------------------------------------------------------ détail
  protected async ouvrir(id: number): Promise<void> {
    try {
      const t = await firstValueFrom(this.http.get<Tournee>(`/api/tournees/${id}`));
      this.detail.set(t);
      this.vue.set('detail');
      setTimeout(() => this.dessiner(t), 380);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  private dessiner(t: Tournee): void {
    const hote = this.hote()?.nativeElement;
    if (!hote) return;
    if (this.carte && this.carte.getContainer() !== hote) {
      this.carte.remove();
      this.carte = null;
    }
    if (!this.carte) {
      this.carte = L.map(hote, { zoomControl: true, scrollWheelZoom: false });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
      this.couche = L.layerGroup().addTo(this.carte);
    }
    this.couche!.clearLayers();
    this.carte.invalidateSize();
    const reduit = animationsReduites();
    const points: L.LatLngTuple[] = [[t.depart.latitude, t.depart.longitude]];
    L.marker(points[0], { icon: L.divIcon({ className: '', html: '<span class="to-marqueur depart">FACT</span>', iconSize: [28, 28], iconAnchor: [14, 14] }),
      title: 'Agence' }).addTo(this.couche!);
    t.etapesDetail.forEach((e: EtapeTournee, i) => {
      if (e.latitude === null || e.longitude === null) return;
      points.push([e.latitude, e.longitude]);
      L.marker([e.latitude, e.longitude], { title: e.batiment, icon: L.divIcon({ className: '',
        html: `<span class="to-marqueur ${e.pointeeLe ? 'faite' : ''}" style="animation-delay:${reduit ? 0 : 300 + i * 380}ms">${i + 1}</span>`,
        iconSize: [28, 28], iconAnchor: [14, 14] }) }).addTo(this.couche!);
    });
    for (let i = 1; i < points.length; i++) {
      const seg = L.polyline([points[i - 1], points[i]], { color: t.etapesDetail[i - 1]?.pointeeLe ? '#2e9e6b' : '#2353a6', weight: 4, opacity: .85,
        className: reduit ? '' : 'to-trait' }).addTo(this.couche!);
      const el = seg.getElement() as SVGPathElement | undefined;
      if (el && !reduit) {
        el.setAttribute('pathLength', '1');
        el.style.animationDelay = `${(i - 1) * 380}ms`;
      }
    }
    this.carte.fitBounds(L.latLngBounds(points), { padding: [26, 26], maxZoom: 15 });
  }

  // ------------------------------------------------------------------ préparation
  protected async preparer(base?: Tournee): Promise<void> {
    this.erreur.set(null);
    this.s = { idAgent: base?.idAgent ?? null, jour: base?.jour ?? (this.jour() >= aujourdhui() ? this.jour() : aujourdhui()), consigne: base?.consigne ?? '' };
    this.etapesSaisie.set((base?.etapesDetail ?? []).map((e) => ({ idBatiment: e.idBatiment, batiment: e.batiment,
      detail: (e.quartier ? e.quartier + ', ' : '') + e.commune, motif: e.motif, consigne: e.consigne ?? '' })));
    this.vue.set('preparer');
    try {
      const [a, s, b] = await Promise.all([
        this.agents().length ? Promise.resolve(this.agents()) : firstValueFrom(this.http.get<Agent[]>('/api/tournees/agents')),
        firstValueFrom(this.http.get<Suggestion[]>('/api/tournees/suggestions', { params: { jour: this.s.jour } })),
        this.batiments().length ? Promise.resolve(this.batiments()) : this.patrimoine.batiments(),
      ]);
      this.agents.set(a);
      this.suggestions.set(s);
      this.batiments.set(b.filter((x) => x.latitude !== null));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected modifier(t: Tournee): void {
    this.preparer(t);
  }

  protected dans(id: number): boolean {
    return this.etapesSaisie().some((e) => e.idBatiment === id);
  }

  protected basculer(x: Suggestion): void {
    if (this.dans(x.idBatiment)) this.retirer(x.idBatiment);
    else this.etapesSaisie.update((l) => [...l, { idBatiment: x.idBatiment, batiment: x.batiment,
      detail: (x.quartier ? x.quartier + ', ' : '') + x.commune, motif: x.motif, consigne: x.raison }]);
  }

  protected ajouter(id: number | null): void {
    const b = this.batiments().find((x) => x.id === id);
    if (!b || this.dans(b.id)) return;
    this.etapesSaisie.update((l) => [...l, { idBatiment: b.id, batiment: b.nom, detail: (b.quartier ? b.quartier + ', ' : '') + b.commune,
      motif: 'VISITE', consigne: '' }]);
  }

  protected retirer(id: number): void {
    this.etapesSaisie.update((l) => l.filter((e) => e.idBatiment !== id));
  }

  protected async enregistrer(): Promise<void> {
    this.erreur.set(null);
    if (!this.s.idAgent) {
      this.erreur.set('Choisissez l\'agent.');
      return;
    }
    if (!this.etapesSaisie().length) {
      this.erreur.set('Ajoutez au moins un bâtiment à visiter.');
      return;
    }
    this.occupe.set(true);
    try {
      const t = await firstValueFrom(this.http.post<Tournee>('/api/tournees', { idAgent: this.s.idAgent, jour: this.s.jour, consigne: this.s.consigne || null,
        etapes: this.etapesSaisie().map((e) => ({ idBatiment: e.idBatiment, motif: e.motif, consigne: e.consigne || null })) }));
      this.avis.succes(`Tournée ${t.reference} enregistrée : ${t.etapes} étape(s), dans l'ordre de passage le plus court.`);
      this.jour.set(this.s.jour);
      await this.charger();
      await this.ouvrir(t.id);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }
}
