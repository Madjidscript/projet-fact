import { Component, ElementRef, OnDestroy, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import * as L from 'leaflet';
import { Icone } from '../../composants/icone';
import { animationsReduites } from '../../composants/mouvement';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { fcfa } from '../../noyau/format';
import { Batiment, DROITS } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import {
  COULEURS, CodeThematique, Teinte, couleursGestionnaires, teinteGestionnaire, teinteImpayes, teinteOccupation, teinteTravaux, teinteValidation,
} from '../../noyau/thematiques';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

const PLAN = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
const SATELLITE = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
/** Taille, en pixels, de la maille de regroupement des bâtiments proches. */
const MAILLE = 58;

/** Valeur du filtre « bâtiments sans gestionnaire » (les identifiants de compte sont positifs). */
const SANS_GESTIONNAIRE = -1;

const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

type Volet = 'liste' | 'localiser';

/**
 * La carte du patrimoine : chaque bâtiment est un point coloré selon la thématique choisie
 * (occupation, gestionnaire, validation). À faible zoom, les bâtiments proches se regroupent
 * avec un compteur ; un clic sur un bâtiment ouvre sa fiche résumée. Les bâtiments sans
 * position se placent d'ici : on choisit le bâtiment, on clique sur la carte, on enregistre.
 */
@Component({
  selector: 'fact-carte',
  imports: [FormsModule, RouterLink, Icone, Choix, OptionsPipe],
  templateUrl: './carte.html',
  styleUrl: './carte.scss',
})
export class Carte implements OnDestroy {
  protected readonly opt = OPTIONS;
  private readonly patrimoine = inject(PatrimoineService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** Paramètre de l'adresse : ?volet=localiser ouvre la liste des bâtiments à placer. */
  readonly voletAdresse = input<string | undefined>(undefined, { alias: 'volet' });

  private readonly hote = viewChild.required<ElementRef<HTMLDivElement>>('carte');

  protected readonly chargement = signal(true);
  protected readonly batiments = signal<Batiment[]>([]);
  protected readonly recherche = signal('');
  protected readonly commune = signal<number | null>(null);
  protected readonly thematique = signal<CodeThematique>('occupation');
  /** Gestionnaire mis en avant : la carte ne montre plus que ses bâtiments ; SANS_GESTIONNAIRE pour ceux qui n'en ont pas. */
  protected readonly gestionnaire = signal<number | null>(null);
  protected readonly satellite = signal(false);
  protected readonly volet = signal<Volet>('liste');
  /** Sur téléphone, le volet se déplie depuis le bas. */
  protected readonly voletOuvert = signal(false);
  protected readonly choisi = signal<Batiment | null>(null);
  /** Les bâtiments dans le cadre de la carte, pour la liste. */
  protected readonly dansLeCadre = signal<number[]>([]);

  // Placement d'un bâtiment sur la carte
  protected readonly aPlacer = signal<Batiment | null>(null);
  protected readonly pointPropose = signal<L.LatLng | null>(null);
  protected readonly enregistrement = signal(false);

  protected readonly peutPlacer = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  protected readonly voitGestion = computed(() => this.auth.compte()?.portee === 'TOUT');
  protected readonly voitLoyers = computed(() => this.auth.a(DROITS.locationLire));
  protected readonly voitTravaux = computed(() => this.auth.a(DROITS.travauxLire));

  protected readonly couleursG = computed(() => couleursGestionnaires(this.batiments()));

  /** Les bâtiments retenus par la recherche, la commune et le gestionnaire. */
  protected readonly retenus = computed(() => {
    const t = sansAccent(this.recherche().trim());
    const c = this.commune(), g = this.gestionnaire();
    return this.batiments().filter((b) => (!c || b.idCommune === c) &&
      (g === null || (g === SANS_GESTIONNAIRE ? b.idGestionnaire === null : b.idGestionnaire === g)) &&
      (!t || sansAccent(`${b.nom} ${b.reference} ${b.proprietaire} ${b.quartier ?? ''} ${b.commune} ${b.gestionnaire ?? ''}`).includes(t)));
  });
  protected readonly places = computed(() => this.retenus().filter((b) => b.latitude !== null));
  protected readonly aLocaliser = computed(() => this.retenus().filter((b) => b.latitude === null));
  protected readonly listeVisible = computed(() => {
    const ids = new Set(this.dansLeCadre());
    return this.places().filter((b) => ids.has(b.id));
  });

  /** La légende de la thématique courante, avec le nombre de bâtiments de chaque couleur. */
  protected readonly legende = computed(() => {
    const parLibelle = new Map<string, { couleur: string; nombre: number; id?: number }>();
    if (this.thematique() === 'gestionnaire') {
      for (const [id, g] of this.couleursG()) parLibelle.set(g.nom, { couleur: g.couleur, nombre: 0, id });
      parLibelle.set('Sans gestionnaire', { couleur: COULEURS.neutre, nombre: 0, id: SANS_GESTIONNAIRE });
    }
    for (const b of this.places()) {
      const t = this.teinte(b);
      const e = parLibelle.get(t.legende) ?? { couleur: t.couleur, nombre: 0 };
      e.nombre++;
      parLibelle.set(t.legende, e);
    }
    return [...parLibelle.entries()].map(([libelle, e]) => ({ libelle, ...e })).filter((e) => e.nombre > 0 || e.id !== undefined);
  });

  protected readonly totaux = computed(() => {
    const l = this.retenus();
    const lots = l.reduce((t, b) => t + b.lots, 0), occupes = l.reduce((t, b) => t + b.lotsOccupes, 0);
    return { batiments: l.length, lots, occupes, taux: lots ? Math.round((occupes / lots) * 100) : null };
  });

  protected readonly fcfa = fcfa;

  private carte?: L.Map;
  private fond?: L.TileLayer;
  private couche = L.layerGroup();
  private repere?: L.Marker;
  private premiereFois = true;
  private suivi?: ResizeObserver;

  constructor() {
    afterNextRender(() => this.creer());
    // Données, filtres ou thématique changent : la carte se redessine.
    effect(() => {
      this.places();
      this.thematique();
      this.couleursG();
      this.choisi();
      untracked(() => this.dessiner());
    });
    effect(() => {
      if (this.voletAdresse() === 'localiser') untracked(() => { this.volet.set('localiser'); this.voletOuvert.set(true); });
    });
    effect(() => {
      const p = this.pointPropose();
      untracked(() => this.poserRepere(p));
    });
  }

  ngOnDestroy(): void {
    this.suivi?.disconnect();
    this.carte?.remove();
  }

  // ------------------------------------------------------------------ commandes
  protected basculerFond(): void {
    this.satellite.update((s) => !s);
    this.poserFond();
  }

  protected choisirThematique(t: CodeThematique): void {
    this.thematique.set(t);
    if (t !== 'gestionnaire') this.gestionnaire.set(null);
  }

  /** Un clic sur un gestionnaire de la légende : la carte ne montre plus que son portefeuille. */
  protected filtrerGestionnaire(id: number | undefined): void {
    if (id === undefined) return;
    this.gestionnaire.update((g) => (g === id ? null : id));
    setTimeout(() => this.cadrer(this.places()));
  }

  protected choisir(b: Batiment): void {
    this.choisi.set(b);
    this.voletOuvert.set(false);
    if (b.latitude !== null && b.longitude !== null && this.carte) {
      const zoom = Math.max(this.carte.getZoom(), 16);
      if (animationsReduites()) this.carte.setView([b.latitude, b.longitude], zoom);
      else this.carte.flyTo([b.latitude, b.longitude], zoom, { duration: 0.7 });
    }
  }

  protected survoler(id: number | null): void {
    this.hote().nativeElement.querySelectorAll('.bat-marqueur.survol').forEach((e) => e.classList.remove('survol'));
    if (id !== null) this.hote().nativeElement.querySelector(`.bat-marqueur[data-id="${id}"]`)?.classList.add('survol');
  }

  protected teinte(b: Batiment): Teinte {
    switch (this.thematique()) {
      case 'gestionnaire': return teinteGestionnaire(b, this.couleursG());
      case 'validation': return teinteValidation(b);
      case 'travaux': return teinteTravaux(b);
      case 'impayes': return teinteImpayes(b, this.ref.reglage('loyer.retard_leger_mois', 1), this.ref.reglage('loyer.retard_grave_mois', 2));
      default: return teinteOccupation(b);
    }
  }

  protected libelleType(code: string): string {
    return this.ref.libelle('TYPE_BATIMENT', code);
  }

  // ------------------------------------------------------------------ placement
  protected placer(b: Batiment): void {
    this.choisi.set(null);
    this.aPlacer.set(b);
    this.pointPropose.set(b.latitude !== null && b.longitude !== null ? L.latLng(b.latitude, b.longitude) : null);
    this.voletOuvert.set(false);
    const centre = b.latitude !== null && b.longitude !== null ? { latitude: b.latitude, longitude: b.longitude } : this.ref.centreCommune(b.idCommune);
    if (centre && this.carte) this.carte.flyTo([centre.latitude, centre.longitude], 16, { duration: animationsReduites() ? 0 : 0.7 });
  }

  protected annulerPlacement(): void {
    this.aPlacer.set(null);
    this.pointPropose.set(null);
  }

  protected maPosition(): void {
    if (!navigator.geolocation) {
      this.avis.erreur("Cet appareil ne donne pas sa position.");
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (g) => {
        const p = L.latLng(g.coords.latitude, g.coords.longitude);
        this.pointPropose.set(p);
        this.carte?.flyTo(p, 18, { duration: 0.6 });
      },
      () => this.avis.erreur("Position indisponible : autorisez la localisation, ou cliquez sur la carte."),
      { enableHighAccuracy: true, timeout: 15000 },
    );
  }

  protected async enregistrerPosition(): Promise<void> {
    const b = this.aPlacer(), p = this.pointPropose();
    if (!b || !p || this.enregistrement()) return;
    this.enregistrement.set(true);
    try {
      const maj = await this.patrimoine.enregistrerBatiment({
        id: b.id, nom: b.nom, type: b.type, adresse: b.adresse, idCommune: b.idCommune, idQuartier: b.idQuartier,
        latitude: p.lat, longitude: p.lng, precisionGps: null, niveaux: b.niveaux, anneeConstruction: b.anneeConstruction,
        description: b.description, idProprietaire: b.idProprietaire, idGestionnaire: b.idGestionnaire, idSuppleant: b.idSuppleant,
      });
      this.batiments.update((l) => l.map((x) => (x.id === maj.id ? maj : x)));
      this.aPlacer.set(null);
      this.pointPropose.set(null);
      this.choisi.set(maj);
      this.avis.succes(`« ${maj.nom} » est placé sur la carte.`);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.enregistrement.set(false);
    }
  }

  // ------------------------------------------------------------------ carte
  private async creer(): Promise<void> {
    this.carte = L.map(this.hote().nativeElement, { zoomControl: false, attributionControl: true });
    L.control.zoom({ position: 'bottomright' }).addTo(this.carte);
    this.carte.setView([5.345, -4.012], 11);
    this.poserFond();
    this.couche.addTo(this.carte);
    this.carte.on('zoomend moveend', () => {
      this.dessiner();
      this.mesurerCadre();
    });
    this.carte.on('click', (e: L.LeafletMouseEvent) => {
      if (this.aPlacer()) this.pointPropose.set(e.latlng);
      else this.choisi.set(null);
    });
    this.suivi = new ResizeObserver(() => this.carte?.invalidateSize());
    this.suivi.observe(this.hote().nativeElement);

    try {
      const [batiments] = await Promise.all([this.patrimoine.batiments(), this.ref.assurer()]);
      this.batiments.set(batiments);
      if (!batiments.some((b) => b.latitude !== null) && batiments.length) this.volet.set('localiser');
      // Ouverture : vol depuis la vue d'Abidjan vers le patrimoine (§ 6.3).
      setTimeout(() => this.cadrer(this.places()), 250);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private cadrer(batiments: Batiment[]): void {
    if (!this.carte) return;
    const points = batiments.filter((b) => b.latitude !== null).map((b) => L.latLng(b.latitude!, b.longitude!));
    if (!points.length) return;
    // Le cadrage laisse de la place à ce qui se pose sur la carte : barre de recherche en haut,
    // volet à gauche sur ordinateur, poignée du volet en bas sur téléphone.
    const large = window.innerWidth >= 768;
    const options = {
      paddingTopLeft: (large ? [400, 76] : [24, 76]) as L.PointTuple,
      paddingBottomRight: (large ? [40, 40] : [24, 70]) as L.PointTuple,
      maxZoom: 16,
      duration: animationsReduites() ? 0 : 1.1,
    };
    if (points.length === 1) this.carte.flyTo(points[0], 16, { duration: options.duration });
    else this.carte.flyToBounds(L.latLngBounds(points), options);
  }

  private poserFond(): void {
    if (!this.carte) return;
    this.fond?.remove();
    this.fond = this.satellite()
      ? L.tileLayer(SATELLITE, { maxZoom: 19, attribution: 'Imagerie © Esri' })
      : L.tileLayer(PLAN, { maxZoom: 19, attribution: '© OpenStreetMap' });
    this.fond.addTo(this.carte);
  }

  private mesurerCadre(): void {
    if (!this.carte) return;
    const cadre = this.carte.getBounds();
    this.dansLeCadre.set(this.places().filter((b) => cadre.contains([b.latitude!, b.longitude!])).map((b) => b.id));
  }

  /** Regroupe les bâtiments proches à l'écran, puis dessine un point par bâtiment ou par groupe. */
  private dessiner(): void {
    if (!this.carte) return;
    this.couche.clearLayers();
    const zoom = this.carte.getZoom();
    const groupes = new Map<string, Batiment[]>();
    for (const b of this.places()) {
      const p = this.carte.project([b.latitude!, b.longitude!], zoom);
      // À fort zoom, chaque bâtiment garde son point.
      const cle = zoom >= 17 ? `b${b.id}` : `${Math.floor(p.x / MAILLE)}:${Math.floor(p.y / MAILLE)}`;
      groupes.set(cle, [...(groupes.get(cle) ?? []), b]);
    }
    const tombe = this.premiereFois && !animationsReduites();
    let rang = 0;
    const choisi = this.choisi()?.id;
    for (const membres of groupes.values()) {
      const delai = tombe ? Math.min(rang++, 12) * 60 : 0;
      if (membres.length === 1) {
        const b = membres[0];
        const t = this.teinte(b);
        const classes = ['bat-marqueur', tombe ? 'tombe' : '', t.alerte ? 'alerte' : '', b.id === choisi ? 'choisi' : ''].join(' ');
        const m = L.marker([b.latitude!, b.longitude!], {
          title: b.nom,
          keyboard: true,
          icon: L.divIcon({
            className: '',
            html: `<span class="${classes}" data-id="${b.id}" style="--teinte:${t.couleur};animation-delay:${delai}ms"></span>`,
            iconSize: [26, 26], iconAnchor: [13, 13],
          }),
        });
        m.on('click', () => this.choisir(b));
        m.addTo(this.couche);
      } else {
        // Le groupe montre son compteur, et la part de chaque couleur sur son pourtour.
        const parts = new Map<string, number>();
        for (const b of membres) {
          const c = this.teinte(b).couleur;
          parts.set(c, (parts.get(c) ?? 0) + 1);
        }
        let cumul = 0;
        const anneau = [...parts.entries()].map(([c, n]) => {
          const debut = (cumul / membres.length) * 360;
          cumul += n;
          return `${c} ${debut}deg ${(cumul / membres.length) * 360}deg`;
        }).join(',');
        const taille = membres.length < 10 ? 40 : membres.length < 50 ? 48 : 56;
        const lat = membres.reduce((t, b) => t + b.latitude!, 0) / membres.length;
        const lon = membres.reduce((t, b) => t + b.longitude!, 0) / membres.length;
        const m = L.marker([lat, lon], {
          title: `${membres.length} bâtiments`,
          icon: L.divIcon({
            className: '',
            html: `<span class="groupe-marqueur${tombe ? ' tombe' : ''}" style="--anneau:conic-gradient(${anneau});width:${taille}px;height:${taille}px;animation-delay:${delai}ms"><b>${membres.length}</b></span>`,
            iconSize: [taille, taille], iconAnchor: [taille / 2, taille / 2],
          }),
        });
        // Un clic éclate le groupe : la carte zoome sur ses bâtiments.
        m.on('click', () => this.carte!.flyToBounds(L.latLngBounds(membres.map((b) => [b.latitude!, b.longitude!])),
          { padding: [80, 80], maxZoom: 18, duration: animationsReduites() ? 0 : 0.6 }));
        m.addTo(this.couche);
      }
    }
    if (groupes.size) this.premiereFois = false;
    this.mesurerCadre();
  }

  private poserRepere(p: L.LatLng | null): void {
    this.repere?.remove();
    this.repere = undefined;
    if (!p || !this.carte) return;
    this.repere = L.marker(p, {
      draggable: true,
      icon: L.divIcon({ className: '', html: '<span class="repere-placement"></span>', iconSize: [34, 34], iconAnchor: [17, 17] }),
    }).addTo(this.carte);
    this.repere.on('dragend', () => this.pointPropose.set(this.repere!.getLatLng()));
  }
}
