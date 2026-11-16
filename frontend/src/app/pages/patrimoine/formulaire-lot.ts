import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icone } from '../../composants/icone';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { DROITS, Lot } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';

interface Saisie {
  id: number | null;
  numero: string;
  etage: number | null;
  type: string;
  surface: number | null;
  pieces: number | null;
  chambres: number | null;
  sallesEau: number | null;
  equipements: string[];
  loyerReference: number | null;
  charges: number | null;
  statut: string;
  description: string;
}

/**
 * Création ou modification d'un lot (SIG-03) : numéro de porte unique dans le bâtiment,
 * aucune valeur négative (§ 3.2, § 4.2). Un lot ne se supprime pas : il s'archive.
 */
@Component({
  selector: 'fact-formulaire-lot',
  imports: [FormsModule, Icone],
  templateUrl: './formulaire-lot.html',
  styleUrl: './formulaire.scss',
})
export class FormulaireLot implements OnInit {
  private readonly patrimoine = inject(PatrimoineService);
  private readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  readonly idBatiment = input.required<number>();
  readonly lot = input<Lot | null>(null);
  /** L'étage proposé pour un nouveau lot : celui du dernier lot saisi. */
  readonly etageParDefaut = input(0);
  readonly enregistre = output<Lot>();
  readonly archive = output<Lot>();
  readonly annule = output<void>();

  protected s: Saisie = {
    id: null, numero: '', etage: 0, type: 'F3', surface: null, pieces: null, chambres: null, sallesEau: null,
    equipements: [], loyerReference: null, charges: null, statut: 'VACANT', description: '',
  };
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  /** « Occupé » et « En préavis » viennent du bail : on ne les choisit pas à la main (§ 4.2). */
  protected readonly statuts = computed(() => {
    const actuel = this.lot()?.statut;
    return this.ref.liste('STATUT_LOT').filter((v) => !['OCCUPE', 'EN_PREAVIS'].includes(v.code) || v.code === actuel);
  });
  protected readonly statutDuBail = computed(() => ['OCCUPE', 'EN_PREAVIS'].includes(this.lot()?.statut ?? ''));

  protected readonly peutArchiver = computed(() => this.auth.a(DROITS.patrimoineEcrire) && this.auth.compte()?.profil !== 'AGENT');

  async ngOnInit(): Promise<void> {
    const l = this.lot();
    if (l) {
      this.s = {
        id: l.id, numero: l.numero, etage: l.etage, type: l.type, surface: l.surface, pieces: l.pieces, chambres: l.chambres,
        sallesEau: l.sallesEau, equipements: [...l.equipements], loyerReference: l.loyerReference, charges: l.charges,
        statut: l.statut, description: l.description ?? '',
      };
    } else {
      this.s.etage = this.etageParDefaut();
    }
    await this.ref.assurer().catch((e) => this.erreur.set(messageErreur(e)));
  }

  protected basculer(code: string): void {
    this.s.equipements = this.s.equipements.includes(code)
      ? this.s.equipements.filter((e) => e !== code)
      : [...this.s.equipements, code];
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      this.enregistre.emit(await this.patrimoine.enregistrerLot({ ...this.s, idBatiment: this.idBatiment() }));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }
}
