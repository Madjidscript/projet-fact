import { DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CartePosition, Position } from '../../composants/carte-position';
import { Choix, OptionChoix, OptionsPipe } from '../../composants/choix';
import { EnteteTerrain } from './entete-terrain';
import { Icone } from '../../composants/icone';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { InstallationService } from '../../noyau/installation.service';
import { OPTIONS } from '../../noyau/options';
import { ReferentielService } from '../../noyau/referentiel.service';
import { LotTerrain, Preparation, Recensement, TerrainService, reduirePhoto } from '../../noyau/terrain.service';

type Etape = 'position' | 'photos' | 'batiment' | 'lots';
const ETAPES: { cle: Etape; libelle: string }[] = [
  { cle: 'position', libelle: 'Position' },
  { cle: 'photos', libelle: 'Photos' },
  { cle: 'batiment', libelle: 'Bâtiment' },
  { cle: 'lots', libelle: 'Lots' },
];
const BROUILLON = 'fact-immobilier.terrain.brouillon';

interface Saisie {
  nom: string;
  type: string;
  idCommune: number | null;
  idQuartier: number | null;
  adresse: string;
  niveaux: number | null;
  idProprietaire: number | null;
  description: string;
}

/**
 * Le recensement sur le terrain (§ 4.6), pensé pour le téléphone et l'absence de réseau :
 * l'agent capte la position (précision affichée, point ajustable sur le satellite), prend les
 * photos, décrit le bâtiment et liste ses lots. L'enregistrement range tout dans le téléphone ;
 * l'envoi part de lui-même au retour du réseau. La fiche arrive « À valider » au gestionnaire.
 */
@Component({
  selector: 'fact-terrain',
  imports: [FormsModule, DecimalPipe, Icone, CartePosition, Choix, OptionsPipe, EnteteTerrain],
  templateUrl: './terrain.html',
  styleUrl: './terrain.scss',
})
export class Terrain implements OnInit, OnDestroy {
  protected readonly terrain = inject(TerrainService);
  protected readonly installation = inject(InstallationService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);
  private readonly avis = inject(AvisService);

  protected readonly etapes = ETAPES;
  protected readonly etape = signal<Etape>('position');
  protected readonly position = signal<Position | null>(null);
  protected readonly photos = signal<{ blob: Blob; url: string }[]>([]);
  protected readonly lots = signal<LotTerrain[]>([]);
  protected readonly preparation = signal<Preparation | null>(null);
  protected readonly enregistrement = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly termine = signal<string | null>(null);
  protected s: Saisie = this.vide();
  protected generateur = { niveaux: 4, parNiveau: 3, rdc: true, type: 'F3' };

  protected readonly opt = OPTIONS;
  protected readonly agent = computed(() => this.auth.compte()?.nom ?? this.terrain.agentRetenu()?.nom ?? '');
  protected readonly optionProprietaire = (p: { id: number; nom: string; reference: string }): OptionChoix =>
    ({ valeur: p.id, libelle: p.nom, detail: p.reference === 'PRO-SITE' ? 'si vous ne le connaissez pas' : p.reference });

  protected readonly rang = computed(() => ETAPES.findIndex((e) => e.cle === this.etape()));
  /** Ce qui manque avant d'enregistrer, dit simplement. */
  protected readonly manques = computed(() => {
    // lu à chaque changement d'étape : suffisant pour un résumé
    this.etape();
    const m: string[] = [];
    if (!this.position()) m.push('la position');
    if (!this.s.nom.trim()) m.push('le nom du bâtiment');
    if (!this.s.idCommune) m.push('la commune');
    return m;
  });

  async ngOnInit(): Promise<void> {
    this.ref.assurer().catch(() => undefined);
    this.preparation.set(await this.terrain.preparer());
    this.relireBrouillon();
  }

  ngOnDestroy(): void {
    this.garderBrouillon();
    this.photos().forEach((p) => URL.revokeObjectURL(p.url));
  }

  protected aller(e: Etape): void {
    this.garderBrouillon();
    this.etape.set(e);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected suivante(): void {
    const i = this.rang();
    if (i < ETAPES.length - 1) this.aller(ETAPES[i + 1].cle);
  }

  protected placer(p: Position): void {
    this.position.set(p);
    // La commune est devinée d'après le centre le plus proche ; l'agent peut la corriger.
    if (!this.s.idCommune) {
      let meilleure: number | null = null, distance = Infinity;
      for (const c of this.ref.communes()) {
        const d = (c.latitude - p.latitude) ** 2 + ((c.longitude - p.longitude) * Math.cos(p.latitude * Math.PI / 180)) ** 2;
        if (d < distance) { distance = d; meilleure = c.id; }
      }
      if (meilleure !== null && distance < 0.0025) this.s.idCommune = meilleure;
    }
    this.garderBrouillon();
  }

  // ------------------------------------------------------------------ photos
  protected async ajouterPhotos(e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichiers = Array.from(champ.files ?? []);
    champ.value = '';
    for (const f of fichiers) {
      if (this.photos().length >= 20) {
        this.avis.erreur('20 photos au plus par bâtiment.');
        break;
      }
      const blob = await reduirePhoto(f);
      this.photos.update((l) => [...l, { blob, url: URL.createObjectURL(blob) }]);
    }
  }

  protected retirerPhoto(i: number): void {
    this.photos.update((l) => {
      URL.revokeObjectURL(l[i].url);
      return l.filter((_, j) => j !== i);
    });
  }

  // ------------------------------------------------------------------ lots
  /** Crée d'un coup les lots d'un immeuble régulier : niveaux × portes par niveau. */
  protected generer(): void {
    const l: LotTerrain[] = [];
    const n = Math.min(Math.max(1, this.generateur.niveaux || 1), 40);
    const p = Math.min(Math.max(1, this.generateur.parNiveau || 1), 20);
    for (let i = 0; i < n; i++) {
      const etage = this.generateur.rdc ? i : i + 1;
      for (let j = 0; j < p; j++) {
        l.push({ numero: `${etage === 0 ? 'RDC' : etage}-${j + 1}`, etage, type: this.generateur.type, statut: 'OCCUPE', pieces: null, surface: null, description: '' });
      }
    }
    this.lots.set(l);
    if (!this.s.niveaux) this.s.niveaux = n;
    this.garderBrouillon();
  }

  protected ajouterLot(): void {
    const dernier = this.lots().at(-1);
    this.lots.update((l) => [...l, { numero: '', etage: dernier?.etage ?? 0, type: dernier?.type ?? 'F3', statut: 'VACANT', pieces: null, surface: null, description: '' }]);
  }

  protected retirerLot(i: number): void {
    this.lots.update((l) => l.filter((_, j) => j !== i));
  }

  protected lotChange(): void {
    this.lots.update((l) => [...l]);
    this.garderBrouillon();
  }

  // ------------------------------------------------------------------ enregistrer
  protected async enregistrer(): Promise<void> {
    this.erreur.set(null);
    if (this.manques().length) {
      this.erreur.set('Il manque ' + this.manques().join(', ') + '.');
      return;
    }
    const numeros = this.lots().map((l) => l.numero.trim().toLowerCase());
    if (numeros.some((n) => !n)) {
      this.erreur.set('Chaque lot a besoin d\'un numéro de porte.');
      this.etape.set('lots');
      return;
    }
    if (new Set(numeros).size !== numeros.length) {
      this.erreur.set('Deux lots ont le même numéro de porte.');
      this.etape.set('lots');
      return;
    }
    this.enregistrement.set(true);
    try {
      const p = this.position()!;
      const r: Recensement = {
        cle: this.terrain.nouvelleCle(), saisiLe: new Date().toISOString(), nom: this.s.nom.trim(), type: this.s.type,
        adresse: this.s.adresse.trim() || null, idCommune: this.s.idCommune, idQuartier: this.s.idQuartier,
        latitude: p.latitude, longitude: p.longitude, precisionGps: p.precision ?? null, niveaux: this.s.niveaux,
        idProprietaire: this.s.idProprietaire, description: this.s.description.trim() || null,
        lots: this.lots().map((l) => ({ ...l, numero: l.numero.trim() })),
      };
      await this.terrain.garder(r, this.photos().map((x) => x.blob));
      this.termine.set(r.nom);
      this.recommencer(false);
    } catch {
      this.erreur.set('Le téléphone n\'a pas pu garder ce recensement : libérez de la place et réessayez.');
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected recommencer(effacerMessage = true): void {
    this.photos().forEach((p) => URL.revokeObjectURL(p.url));
    this.photos.set([]);
    this.lots.set([]);
    this.position.set(null);
    this.s = this.vide();
    this.etape.set('position');
    try {
      localStorage.removeItem(BROUILLON);
    } catch { /* rien */ }
    if (effacerMessage) this.termine.set(null);
  }

  protected async installer(): Promise<void> {
    if (await this.installation.installer()) this.avis.succes('Application installée : retrouvez l\'icône FACT sur votre écran d\'accueil.');
  }

  // ------------------------------------------------------------------ brouillon
  /** La saisie en cours (sans les photos) survit à la fermeture de l'application. */
  private garderBrouillon(): void {
    try {
      localStorage.setItem(BROUILLON, JSON.stringify({ s: this.s, position: this.position(), lots: this.lots(), etape: this.etape() }));
    } catch { /* stockage indisponible */ }
  }

  private relireBrouillon(): void {
    try {
      const b = JSON.parse(localStorage.getItem(BROUILLON) ?? 'null');
      if (b) {
        this.s = { ...this.vide(), ...b.s };
        this.position.set(b.position ?? null);
        this.lots.set(b.lots ?? []);
        this.etape.set(b.etape ?? 'position');
      }
    } catch { /* brouillon illisible : on repart de zéro */ }
  }

  private vide(): Saisie {
    return { nom: '', type: 'IMMEUBLE', idCommune: null, idQuartier: null, adresse: '', niveaux: null,
      idProprietaire: this.preparation?.()?.proprietaireAPreciser || null, description: '' };
  }
}
