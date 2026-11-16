import { Component, ElementRef, OnInit, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { gsap } from 'gsap';
import { Flip } from 'gsap/Flip';
import { Roulette } from '../composants/roulette';
import { CarteBiens } from '../composants/carte-biens';
import { Choix, OptionChoix } from '../composants/choix';
import { EMAIL, STYLE_PIEGE, TELEPHONE } from '../composants/envoi';
import { Icone } from '../composants/icone';
import { VignetteBien } from '../composants/vignette-bien';
import { Api, Criteres, Vignette, Zone, messageErreur } from '../noyau/api';
import { CommunesService } from '../noyau/communes.service';
import { AGENCE, TYPES_BIEN, montant } from '../noyau/donnees';
import { COURBE, MOUVEMENT, mouvementReduit } from '../noyau/mouvement';

type Tri = Criteres['tri'];
const TRIS: Tri[] = ['recent', 'prix-croissant', 'prix-decroissant', 'surface'];

gsap.registerPlugin(Flip);

const nombre = (v: string | undefined): number | null => (v && Number(v) > 0 ? Math.round(Number(v)) : null);
const liste = (v: string | undefined): string[] => (v ? v.split(',').map((x) => x.trim()).filter(Boolean) : []);

/**
 * Recherche et liste des biens (§ 3.2) : opération, types, commune et quartier, budget,
 * pièces, surface, options, tri. Tous les critères sont dans l'adresse, pour partager une
 * recherche. Liste et carte sont synchronisées : déplacer la carte recharge la liste.
 */
@Component({
  selector: 'site-biens',
  imports: [FormsModule, RouterLink, Icone, Choix, VignetteBien, CarteBiens, Roulette, ...MOUVEMENT],
  templateUrl: './biens.html',
  styleUrl: './biens.scss',
})
export class Biens implements OnInit {
  private readonly router = inject(Router);
  private readonly api = inject(Api);
  protected readonly ref = inject(CommunesService);
  protected readonly agence = AGENCE;
  private readonly grille = viewChild<ElementRef<HTMLElement>>('grille');
  private readonly carte = viewChild(CarteBiens);

  /** Donnée de la route : « louer » ou « acheter ». */
  readonly operation = input<'louer' | 'acheter'>('louer');
  // Critères lus dans l'adresse
  readonly types = input<string | undefined>();
  readonly commune = input<string | undefined>();
  readonly quartier = input<string | undefined>();
  readonly prixMin = input<string | undefined>();
  readonly prixMax = input<string | undefined>();
  readonly pieces = input<string | undefined>();
  readonly surface = input<string | undefined>();
  readonly options = input<string | undefined>();
  readonly tri = input<string | undefined>();

  protected f: Criteres = this.vide();
  protected readonly criteres = signal<Criteres>(this.vide());
  protected readonly zone = signal<Zone | null>(null);
  protected readonly resultats = signal<Vignette[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly enAvant = signal<number | null>(null);
  protected readonly vueMobile = signal<'liste' | 'carte'>('liste');
  protected readonly plusDeCriteres = signal(false);

  // Alerte de recherche
  protected readonly alerteOuverte = signal(false);
  protected alerte = { contact: '', accord: false, site: '' };
  protected readonly alerteEtat = signal<{ ok: boolean; texte: string } | null>(null);
  protected readonly alerteEnvoi = signal(false);
  protected readonly stylePiege = STYLE_PIEGE;

  protected readonly louer = computed(() => this.operation() === 'louer');
  protected readonly optionsTypes = TYPES_BIEN;
  protected readonly optionsCommunes = computed<OptionChoix[]>(() => this.ref.communes().map((c) => ({ valeur: c.id, libelle: c.libelle })));
  protected readonly piecesPossibles = [1, 2, 3, 4, 5, 6];
  protected readonly nbCriteres = computed(() => {
    const c = this.criteres();
    return c.types.length + c.options.length + [c.commune, c.prixMin, c.prixMax, c.pieces, c.surface].filter((x) => x !== null).length;
  });
  /** Les biens sans position ne sont pas sur la carte : on le dit. */
  protected readonly horsCarte = computed(() => this.resultats().filter((b) => b.latitude === null).length);

  private requete = 0;
  private cacheQuartiers: { cle: number | null; liste: OptionChoix[] } = { cle: -1, liste: [] };

  constructor() {
    // Les critères de l'adresse remplissent le formulaire (lien partagé, retour arrière) et relancent la recherche.
    effect(() => {
      const c: Criteres = {
        operation: this.operation() === 'acheter' ? 'VENTE' : 'LOCATION',
        types: liste(this.types()).filter((t) => TYPES_BIEN.includes(t)),
        commune: nombre(this.commune()),
        quartier: nombre(this.quartier()),
        prixMin: nombre(this.prixMin()),
        prixMax: nombre(this.prixMax()),
        pieces: nombre(this.pieces()),
        surface: nombre(this.surface()),
        options: liste(this.options()),
        tri: (TRIS.includes(this.tri() as Tri) ? this.tri() : 'recent') as Tri,
      };
      untracked(() => {
        this.f = { ...c, types: [...c.types], options: [...c.options] };
        this.criteres.set(c);
        this.zone.set(null);
        this.charger(true);
      });
    });
  }

  ngOnInit(): void {
    this.ref.assurer();
  }

  protected quartiers(idCommune: number | null): OptionChoix[] {
    if (this.cacheQuartiers.cle !== idCommune) {
      const c = this.ref.commune(idCommune);
      this.cacheQuartiers = { cle: idCommune, liste: (c?.quartiers ?? []).map((q) => ({ valeur: q.id, libelle: q.libelle })) };
    }
    return this.cacheQuartiers.liste;
  }

  protected basculerType(t: string): void {
    this.f.types = this.f.types.includes(t) ? this.f.types.filter((x) => x !== t) : [...this.f.types, t];
    this.appliquer();
  }

  protected basculerOption(o: string): void {
    this.f.options = this.f.options.includes(o) ? this.f.options.filter((x) => x !== o) : [...this.f.options, o];
    this.appliquer();
  }

  protected changerCommune(id: number | null): void {
    this.f.commune = id;
    this.f.quartier = null;
    this.appliquer();
  }

  /** Les critères vont dans l'adresse ; l'effet du constructeur relance la recherche. */
  protected appliquer(): void {
    const f = this.f;
    const pos = (n: number | null) => (n !== null && n > 0 ? Math.round(n) : null);
    this.router.navigate([], {
      replaceUrl: true,
      queryParams: {
        types: f.types.length ? f.types.join(',') : null,
        commune: f.commune, quartier: f.commune ? f.quartier : null,
        prixMin: pos(f.prixMin), prixMax: pos(f.prixMax), pieces: f.pieces, surface: pos(f.surface),
        options: f.options.length ? f.options.join(',') : null,
        tri: f.tri === 'recent' ? null : f.tri,
      },
    });
  }

  protected effacer(): void {
    this.f = this.vide();
    this.appliquer();
  }

  /** La carte a été déplacée par le visiteur : on ne garde que les biens de la zone visible. */
  protected zoneChangee(z: Zone): void {
    this.zone.set(z);
    this.charger(false);
  }

  protected touteLaVille(): void {
    this.zone.set(null);
    this.charger(true);
  }

  protected async creerAlerte(): Promise<void> {
    const contact = this.alerte.contact.trim();
    if (!EMAIL.test(contact) && !TELEPHONE.test(contact)) {
      this.alerteEtat.set({ ok: false, texte: 'Indiquez une adresse e-mail ou un numéro WhatsApp à 10 chiffres.' });
      return;
    }
    if (!this.alerte.accord) {
      this.alerteEtat.set({ ok: false, texte: 'Cochez la case pour accepter de recevoir ces messages.' });
      return;
    }
    const c = this.criteres();
    this.alerteEnvoi.set(true);
    try {
      const r = await this.api.alerte(contact, {
        operation: c.operation, types: c.types, idCommune: c.commune, idQuartier: c.quartier,
        prixMin: c.prixMin, prixMax: c.prixMax, pieces: c.pieces, surface: c.surface, libelle: this.resume(),
      });
      this.alerteEtat.set({ ok: true, texte: r.message });
    } catch (e) {
      this.alerteEtat.set({ ok: false, texte: messageErreur(e) });
    } finally {
      this.alerteEnvoi.set(false);
    }
  }

  /** « Appartement ou Villa à louer, Cocody, jusqu'à 400 000 FCFA » : ce que suit l'alerte. */
  protected resume(): string {
    const c = this.criteres();
    const morceaux = [(c.types.length ? c.types.join(' ou ') : 'Bien') + (c.operation === 'LOCATION' ? ' à louer' : ' à vendre')];
    const commune = this.ref.commune(c.commune);
    if (commune) morceaux.push(commune.quartiers.find((q) => q.id === c.quartier)?.libelle ?? commune.libelle);
    if (c.prixMin) morceaux.push(`dès ${montant(c.prixMin)} FCFA`);
    if (c.prixMax) morceaux.push(`jusqu’à ${montant(c.prixMax)} FCFA`);
    if (c.pieces) morceaux.push(`${c.pieces} pièces et plus`);
    if (c.surface) morceaux.push(`${c.surface} m² et plus`);
    return morceaux.join(', ');
  }

  private async charger(recadrer: boolean): Promise<void> {
    const n = ++this.requete;
    this.chargement.set(true);
    this.erreur.set(null);
    try {
      const r = await this.api.biens(this.criteres(), this.zone());
      if (n !== this.requete) return;
      // Flip : on note la place de chaque carte avant le changement, puis elles glissent vers
      // leur nouvelle place ; les cartes qui partent s'effacent, celles qui arrivent apparaissent.
      const avant = this.etatFlip();
      this.resultats.set(r);
      if (avant) setTimeout(() => this.jouerFlip(avant));
      if (recadrer) setTimeout(() => this.carte()?.recadrer());
    } catch (e) {
      if (n === this.requete) this.erreur.set(messageErreur(e));
    } finally {
      if (n === this.requete) {
        const premiere = !this.dejaAffiche;
        this.chargement.set(false);
        if (premiere) setTimeout(() => this.animerListe());
      }
    }
  }

  private dejaAffiche = false;
  /** Les cartes qui quittent la liste : clones posés à leur place, qui s'effacent. */
  private fantomes: HTMLElement[] = [];

  /** Fondu des résultats après les squelettes, en cascade (§ 6.2) : la première fois seulement. */
  private animerListe(): void {
    const g = this.grille()?.nativeElement;
    if (!g) return;
    this.dejaAffiche = true;
    const items = Array.from(g.children).slice(0, 8);
    if (mouvementReduit()) gsap.from(items, { opacity: 0, duration: 0.25 });
    else gsap.from(items, { y: 24, opacity: 0, duration: 0.5, ease: COURBE, stagger: 0.06, clearProps: 'transform,opacity' });
  }

  private etatFlip(): { etat: Flip.FlipState; positions: Map<string, DOMRect>; elements: Map<string, HTMLElement> } | null {
    const g = this.grille()?.nativeElement;
    if (!g || !this.dejaAffiche || mouvementReduit()) return null;
    const cartes = Array.from(g.children) as HTMLElement[];
    const positions = new Map<string, DOMRect>();
    const elements = new Map<string, HTMLElement>();
    cartes.forEach((c) => {
      positions.set(c.dataset['flipId']!, c.getBoundingClientRect());
      elements.set(c.dataset['flipId']!, c);
    });
    return { etat: Flip.getState(cartes), positions, elements };
  }

  private jouerFlip(avant: { etat: Flip.FlipState; positions: Map<string, DOMRect>; elements: Map<string, HTMLElement> }): void {
    const g = this.grille()?.nativeElement;
    if (!g) return;
    const cartes = Array.from(g.children) as HTMLElement[];
    const restent = new Set(cartes.map((c) => c.dataset['flipId']));
    // Les cartes parties ont déjà été retirées par Angular : un clone s'efface à leur ancienne place.
    this.fantomes.forEach((f) => f.remove());
    this.fantomes = [];
    avant.positions.forEach((r, id) => {
      if (restent.has(id)) return;
      const clone = avant.elements.get(id)!.cloneNode(true) as HTMLElement;
      Object.assign(clone.style, { position: 'fixed', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px',
        margin: '0', zIndex: '4', pointerEvents: 'none' });
      document.body.appendChild(clone);
      this.fantomes.push(clone);
      gsap.to(clone, { opacity: 0, scale: 0.88, duration: 0.35, ease: 'power2.in', onComplete: () => clone.remove() });
    });
    Flip.from(avant.etat, {
      targets: cartes,
      duration: 0.6,
      ease: COURBE,
      stagger: 0.03,
      onEnter: (entrees) => gsap.fromTo(entrees, { opacity: 0, scale: 0.9, y: 20 }, { opacity: 1, scale: 1, y: 0, duration: 0.5, ease: COURBE, delay: 0.15 }),
    });
  }

  private vide(): Criteres {
    return { operation: 'LOCATION', types: [], commune: null, quartier: null, prixMin: null, prixMax: null, pieces: null,
      surface: null, options: [], tri: 'recent' };
  }
}
