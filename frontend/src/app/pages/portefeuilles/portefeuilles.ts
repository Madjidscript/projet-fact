import {
  Component, ElementRef, OnDestroy, OnInit, ViewEncapsulation, computed, effect, inject, signal, untracked, viewChild,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import * as L from 'leaflet';
import { firstValueFrom } from 'rxjs';
import { Choix, OptionChoix, OptionsPipe } from '../../composants/choix';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur, animationsReduites } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { dateHeure, fcfa } from '../../noyau/format';

interface Portefeuille {
  id: number | null; nom: string; fonction: string | null; email: string | null; telephone: string | null;
  batiments: number; lots: number; occupes: number; taux: number | null; impaye: number; interventions: number; supplees: number;
}
interface BatimentPf {
  id: number; reference: string; nom: string; commune: string; quartier: string | null; latitude: number | null; longitude: number | null;
  lots: number; occupes: number; impaye: number; idGestionnaire: number | null; gestionnaire: string | null;
  idSuppleant: number | null; suppleant: string | null; proprietaire: string;
}
interface Ligne {
  id: number; idBatiment: number; batiment: string; operation: string | null; motif: string | null; le: string; par: string | null;
  ancienGestionnaire: string | null; nouveauGestionnaire: string | null; ancienSuppleant: string | null; nouveauSuppleant: string | null;
}
interface Operation { cle: string; le: string; par: string | null; motif: string | null; lignes: Ligne[]; }

const PALETTE = ['#3a78c2', '#7b5ea7', '#1f9c9c', '#c2507a', '#5b8c2a', '#a5682a', '#4a5fc1', '#b84f3a', '#2f7d5b', '#c8862b'];
const SANS = '#8d99a6';
const PLAN = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

/**
 * Qui gère quoi (§ 4.3) : le portefeuille de chaque gestionnaire (bâtiments, lots, occupation,
 * impayés, interventions), puis la réaffectation d'un ou plusieurs bâtiments, choisis dans la liste
 * ou sur la carte. L'historique garde chaque réaffectation, datée, avec son auteur et son motif.
 */
@Component({
  selector: 'fact-portefeuilles',
  imports: [FormsModule, RouterLink, Icone, EtatVide, Apparition, Compteur, PanneauLateral, Choix, OptionsPipe],
  encapsulation: ViewEncapsulation.None,
  templateUrl: './portefeuilles.html',
  styleUrl: './portefeuilles.scss',
})
export class Portefeuilles implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);
  private readonly hoteCarte = viewChild<ElementRef<HTMLElement>>('carte');

  protected readonly chargement = signal(true);
  protected readonly portefeuilles = signal<Portefeuille[]>([]);
  protected readonly batiments = signal<BatimentPf[]>([]);
  protected readonly gestionnaires = signal<{ id: number; nom: string; fonction: string | null }[]>([]);
  protected readonly historique = signal<Ligne[]>([]);
  protected readonly peutReaffecter = signal(false);

  /** Filtre de la liste : un gestionnaire (« sans » pour les bâtiments sans gestionnaire), une commune, un texte. */
  protected readonly filtreGestionnaire = signal<number | 'sans' | null>(null);
  protected readonly filtreCommune = signal<string | null>(null);
  protected readonly recherche = signal('');
  protected readonly selection = signal<Set<number>>(new Set());

  protected readonly panneau = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected saisie: { idGestionnaire: number | null; changerSuppleant: boolean; idSuppleant: number | null; motif: string } =
    { idGestionnaire: null, changerSuppleant: false, idSuppleant: null, motif: '' };
  protected readonly f = { fcfa, dateHeure };

  private carte: L.Map | null = null;
  private couche: L.LayerGroup | null = null;
  private observateur: ResizeObserver | null = null;
  private dernierCadrage = '';
  /** Le marqueur de chaque bâtiment affiché, pour l'allumer sans tout redessiner. */
  private readonly elements = new Map<number, HTMLElement>();

  /** La couleur de chaque gestionnaire, stable : dans l'ordre alphabétique. */
  protected readonly couleurs = computed(() => {
    const m = new Map<number, string>();
    this.gestionnaires().slice().sort((a, b) => a.nom.localeCompare(b.nom, 'fr')).forEach((g, i) => m.set(g.id, PALETTE[i % PALETTE.length]));
    return m;
  });

  protected readonly communes = computed(() => [...new Set(this.batiments().map((b) => b.commune))].sort((a, b) => a.localeCompare(b, 'fr')));
  protected readonly optionsGestionnaires = computed<OptionChoix[]>(() => [
    { valeur: 'sans', libelle: 'Sans gestionnaire' },
    ...this.gestionnaires().map((g) => ({ valeur: g.id, libelle: g.nom, detail: g.fonction })),
  ]);
  protected readonly optionCommune = (c: string): OptionChoix => ({ valeur: c, libelle: c });
  protected readonly optionGestionnaire = (g: { id: number; nom: string; fonction: string | null }, sauf: number | null): OptionChoix | null =>
    g.id === sauf ? null : { valeur: g.id, libelle: g.nom, detail: g.fonction };

  protected readonly visibles = computed(() => {
    const g = this.filtreGestionnaire(), c = this.filtreCommune();
    const t = sansAccent(this.recherche().trim());
    return this.batiments().filter((b) => (g === null || (g === 'sans' ? b.idGestionnaire === null : b.idGestionnaire === g))
      && (!c || b.commune === c)
      && (!t || sansAccent(`${b.nom} ${b.reference} ${b.quartier ?? ''} ${b.commune} ${b.proprietaire}`).includes(t)));
  });

  protected readonly choisis = computed(() => this.batiments().filter((b) => this.selection().has(b.id)));
  protected readonly toutCoche = computed(() => this.visibles().length > 0 && this.visibles().every((b) => this.selection().has(b.id)));

  /** Ce que la réaffectation va changer : d'où viennent les bâtiments choisis. */
  protected readonly provenances = computed(() => {
    const m = new Map<string, number>();
    for (const b of this.choisis()) m.set(b.gestionnaire ?? 'Sans gestionnaire', (m.get(b.gestionnaire ?? 'Sans gestionnaire') ?? 0) + 1);
    return [...m.entries()].map(([nom, n]) => ({ nom, n }));
  });

  /** L'historique regroupé par opération (une réaffectation en masse = une opération). */
  protected readonly operations = computed<Operation[]>(() => {
    const ops: Operation[] = [];
    const index = new Map<string, Operation>();
    for (const l of this.historique()) {
      const cle = l.operation ?? 'seul-' + l.id;
      let o = index.get(cle);
      if (!o) {
        o = { cle, le: l.le, par: l.par, motif: l.motif, lignes: [] };
        index.set(cle, o);
        ops.push(o);
      }
      o.lignes.push(l);
    }
    return ops.slice(0, 20);
  });

  constructor() {
    // La carte se redessine quand la liste filtrée change ; une coche ne fait qu'allumer son marqueur.
    effect(() => {
      const visibles = this.visibles();
      untracked(() => this.dessiner(visibles, this.selection()));
    });
    effect(() => {
      const selection = this.selection();
      for (const [id, el] of this.elements) el.classList.toggle('choisi', selection.has(id));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  ngOnDestroy(): void {
    this.observateur?.disconnect();
    this.carte?.remove();
  }

  protected couleur(id: number | null): string {
    return id === null ? SANS : this.couleurs().get(id) ?? SANS;
  }

  protected filtrerPar(p: Portefeuille): void {
    const v = p.id ?? 'sans';
    this.filtreGestionnaire.set(this.filtreGestionnaire() === v ? null : v);
    document.getElementById('reaffecter')?.scrollIntoView({ behavior: animationsReduites() ? 'auto' : 'smooth', block: 'start' });
  }

  protected basculer(id: number): void {
    this.selection.update((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  protected toutBasculer(): void {
    const tout = this.toutCoche();
    this.selection.update((s) => {
      const n = new Set(s);
      for (const b of this.visibles()) {
        if (tout) n.delete(b.id);
        else n.add(b.id);
      }
      return n;
    });
  }

  protected vider(): void {
    this.selection.set(new Set());
  }

  protected ouvrir(): void {
    this.saisie = { idGestionnaire: null, changerSuppleant: false, idSuppleant: null, motif: '' };
    this.erreur.set(null);
    this.panneau.set(true);
  }

  protected async confirmer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const r = await firstValueFrom(this.http.post<{ modifies: number; selectionnes: number }>('/api/affectations', {
        batiments: [...this.selection()], idGestionnaire: this.saisie.idGestionnaire, changerSuppleant: this.saisie.changerSuppleant,
        idSuppleant: this.saisie.changerSuppleant ? this.saisie.idSuppleant : null, motif: this.saisie.motif || null,
      }));
      const vers = this.gestionnaires().find((g) => g.id === this.saisie.idGestionnaire)?.nom ?? 'aucun gestionnaire';
      this.avis.succes(r.modifies === 0 ? 'Rien à changer : ces bâtiments étaient déjà ainsi affectés.'
        : `${r.modifies} bâtiment(s) confié(s) à ${vers}.`);
      this.panneau.set(false);
      this.vider();
      await this.charger();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  private async charger(): Promise<void> {
    try {
      const r = await firstValueFrom(this.http.get<{
        portefeuilles: Portefeuille[]; batiments: BatimentPf[]; gestionnaires: { id: number; nom: string; fonction: string | null }[];
        historique: Ligne[]; peutReaffecter: boolean;
      }>('/api/affectations/portefeuilles'));
      this.portefeuilles.set(r.portefeuilles);
      this.gestionnaires.set(r.gestionnaires);
      this.historique.set(r.historique);
      this.peutReaffecter.set(r.peutReaffecter);
      this.batiments.set(r.batiments);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
      setTimeout(() => this.initialiserCarte());
    }
  }

  // ------------------------------------------------------------------ carte de sélection
  private initialiserCarte(): void {
    const hote = this.hoteCarte()?.nativeElement;
    if (!hote || this.carte) {
      this.dessiner(this.visibles(), this.selection());
      return;
    }
    this.carte = L.map(hote, { zoomControl: true, attributionControl: true, scrollWheelZoom: false }).setView([5.35, -3.99], 11);
    L.tileLayer(PLAN, { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
    this.couche = L.layerGroup().addTo(this.carte);
    this.observateur = new ResizeObserver(() => this.carte?.invalidateSize());
    this.observateur.observe(hote);
    this.dessiner(this.visibles(), this.selection(), true);
  }

  private dessiner(visibles: BatimentPf[], selection: Set<number>, cadrer = false): void {
    if (!this.carte || !this.couche) return;
    this.couche.clearLayers();
    this.elements.clear();
    const points: L.LatLngExpression[] = [];
    visibles.forEach((b, i) => {
      if (b.latitude === null || b.longitude === null) return;
      points.push([b.latitude, b.longitude]);
      const choisi = selection.has(b.id);
      const m = L.marker([b.latitude, b.longitude], {
        title: `${b.nom} — ${b.gestionnaire ?? 'sans gestionnaire'}`,
        keyboard: true,
        icon: L.divIcon({
          className: '',
          html: `<span class="pf-marqueur${choisi ? ' choisi' : ''}${b.idGestionnaire === null ? ' sans' : ''}" style="--teinte:${this.couleur(b.idGestionnaire)};animation-delay:${Math.min(i, 14) * 35}ms"></span>`,
          iconSize: [24, 24], iconAnchor: [12, 12],
        }),
      });
      if (this.peutReaffecter()) m.on('click', () => this.basculer(b.id));
      m.addTo(this.couche!);
      const el = m.getElement()?.querySelector<HTMLElement>('.pf-marqueur');
      if (el) this.elements.set(b.id, el);
    });
    // On recadre quand la liste change (filtre), pas quand on coche : la carte ne saute pas sous le doigt.
    const cle = visibles.map((b) => b.id).join(',');
    if (points.length && (cadrer || cle !== this.dernierCadrage)) {
      this.carte.fitBounds(L.latLngBounds(points), { padding: [28, 28], maxZoom: 15, animate: !animationsReduites() && !cadrer });
    }
    this.dernierCadrage = cle;
  }
}

function sansAccent(t: string): string {
  return t.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
}
