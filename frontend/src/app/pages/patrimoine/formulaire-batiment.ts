import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CartePosition, Position } from '../../composants/carte-position';
import { Icone } from '../../composants/icone';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { Batiment, DROITS, Proprietaire } from '../../noyau/modeles';
import { Gestionnaire, PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

interface Saisie {
  id: number | null;
  nom: string;
  type: string;
  adresse: string;
  idCommune: number | null;
  idQuartier: number | null;
  niveaux: number | null;
  anneeConstruction: number | null;
  description: string;
  idProprietaire: number | null;
  idGestionnaire: number | null;
  idSuppleant: number | null;
}

/**
 * Création ou modification d'un bâtiment (SIG-02). La position se pose sur la carte, ou se
 * capture au GPS ; le propriétaire est obligatoire (§ 4.2). Le choix du gestionnaire n'est
 * proposé qu'à qui peut affecter (Direction, § 2.2).
 */
@Component({
  selector: 'fact-formulaire-batiment',
  imports: [FormsModule, RouterLink, CartePosition, Icone, Choix, OptionsPipe],
  templateUrl: './formulaire-batiment.html',
  styleUrl: './formulaire.scss',
})
export class FormulaireBatiment implements OnInit {
  protected readonly opt = OPTIONS;
  private readonly patrimoine = inject(PatrimoineService);
  private readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  readonly batiment = input<Batiment | null>(null);
  readonly enregistre = output<Batiment>();
  readonly annule = output<void>();

  protected s: Saisie = {
    id: null, nom: '', type: 'IMMEUBLE', adresse: '', idCommune: null, idQuartier: null, niveaux: null,
    anneeConstruction: null, description: '', idProprietaire: null, idGestionnaire: null, idSuppleant: null,
  };
  protected readonly position = signal<Position | null>(null);
  protected readonly commune = signal<number | null>(null);
  protected readonly centre = computed(() => this.ref.centreCommune(this.commune()) ?? this.cadrage());

  protected readonly proprietaires = signal<Proprietaire[]>([]);
  protected readonly gestionnaires = signal<Gestionnaire[]>([]);
  protected readonly peutAffecter = computed(() => this.auth.a(DROITS.affectationEcrire));
  protected readonly peutCreerProprietaire = computed(() => this.auth.a(DROITS.patrimoineEcrire));

  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  /** Un bâtiment importé ou repris qui attend son passage sur le terrain (§ 7.3). */
  protected readonly enAttenteDeLocalisation = computed(() => !!this.batiment() && this.batiment()!.latitude === null);

  async ngOnInit(): Promise<void> {
    const b = this.batiment();
    if (b) {
      this.s = {
        id: b.id, nom: b.nom, type: b.type, adresse: b.adresse ?? '', idCommune: b.idCommune, idQuartier: b.idQuartier,
        niveaux: b.niveaux, anneeConstruction: b.anneeConstruction, description: b.description ?? '',
        idProprietaire: b.idProprietaire, idGestionnaire: b.idGestionnaire, idSuppleant: b.idSuppleant,
      };
      this.commune.set(b.idCommune);
      if (b.latitude !== null && b.longitude !== null) {
        this.position.set({ latitude: b.latitude, longitude: b.longitude, precision: b.precisionGps });
      }
    }
    try {
      const [proprietaires, gestionnaires] = await Promise.all([
        this.patrimoine.proprietaires(),
        this.peutAffecter() ? this.patrimoine.gestionnaires() : Promise.resolve([] as Gestionnaire[]),
        this.ref.assurer(),
      ]);
      this.proprietaires.set(proprietaires);
      this.gestionnaires.set(gestionnaires);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }

  protected choisirCommune(id: number | null): void {
    this.s.idCommune = id;
    this.s.idQuartier = null;
    this.commune.set(id);
  }

  protected placer(p: Position): void {
    this.position.set(p);
  }

  protected retirerPosition(): void {
    this.position.set(null);
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    const p = this.position();
    try {
      const b = await this.patrimoine.enregistrerBatiment({
        ...this.s,
        latitude: p?.latitude ?? null,
        longitude: p?.longitude ?? null,
        precisionGps: p?.precision ?? null,
      });
      this.enregistre.emit(b);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  private cadrage(): Position | null {
    const c = this.ref.referentiel()?.carte;
    return c ? { latitude: c.latitude, longitude: c.longitude } : null;
  }
}
