import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icone } from '../../composants/icone';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { date, fcfa, surface } from '../../noyau/format';
import { aujourdhui, LocationService, moisEnClair } from '../../noyau/location.service';
import { Bail, DROITS, Encaissement, LocationLot, Lot, Occupant, PeriodeReglee } from '../../noyau/modeles';
import { ReferentielService } from '../../noyau/referentiel.service';
import { FicheAnnonceComposant } from '../annonces/fiche-annonce';
import { FormulaireLot } from './formulaire-lot';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

type Mode = 'fiche' | 'modifier' | 'bail' | 'encaisser' | 'preavis' | 'terminer' | 'annonce';

interface SaisieBail {
  occupantExistant: boolean;
  idOccupant: number | null;
  nom: string;
  prenoms: string;
  telephone: string;
  email: string;
  pieceType: string;
  pieceNumero: string;
  profession: string;
  garantNom: string;
  garantTelephone: string;
  dateEffet: string;
  dureeMois: number;
  loyer: number | null;
  charges: number | null;
  caution: number | null;
  avanceMois: number;
  jourExigibilite: number;
  avanceEncaissee: boolean;
  modeAvance: string;
}

/**
 * La fiche d'un lot et de sa location (SIG-03, SIG-07, SIG-08) : caractéristiques, bail actif,
 * échéancier, encaissements et quittances. On y signe le bail, on encaisse, on pose le préavis
 * et la sortie. Elle se pose dans un panneau latéral ; ses modes se succèdent sans le fermer.
 */
@Component({
  selector: 'fact-fiche-lot',
  imports: [FormsModule, Icone, FormulaireLot, Choix, OptionsPipe, FicheAnnonceComposant],
  templateUrl: './fiche-lot.html',
  styleUrl: './fiche-lot.scss',
})
export class FicheLot {
  protected readonly opt = OPTIONS;
  private readonly location = inject(LocationService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  readonly idLot = input.required<number>();
  /** 'bail' : la fiche s'ouvre directement sur la signature du bail (bouton « Ajouter un locataire »). */
  readonly ouvrirEn = input<'fiche' | 'bail'>('fiche');
  /** Un lot ou un bail a changé : le parent recharge ses décomptes. */
  readonly change = output<Lot | null>();
  readonly ferme = output<void>();
  /** Archiver le lot : la confirmation revient à la fiche du bâtiment. */
  readonly archive = output<Lot>();

  protected readonly chargement = signal(true);
  protected readonly donnees = signal<LocationLot | null>(null);
  protected readonly mode = signal<Mode>('fiche');
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly occupants = signal<Occupant[]>([]);
  /** Le dernier encaissement enregistré : la quittance se déplie (§ 6.3). */
  protected readonly recu = signal<{ encaissement: Encaissement; periodes: PeriodeReglee[] } | null>(null);
  protected readonly aAnnuler = signal<Encaissement | null>(null);
  protected motifAnnulation = '';

  protected readonly lot = computed(() => this.donnees()?.lot ?? null);
  protected readonly bail = computed(() => this.donnees()?.bail ?? null);
  protected readonly etatsDesLieux = signal<{ id: number; reference: string; type: string; faitLe: string; signataire: string; creePar: string }[]>([]);

  /** « Koffi N'Guessan (essai.agent) » : le nom seul. */
  protected sansIdentifiant(signature: string): string {
    return signature.replace(/\s*\([^)]*\)$/, '');
  }

  protected async pdfEtatDesLieux(d: { id: number; reference: string }): Promise<void> {
    try {
      await this.location.ouvrirPdf(`/api/etats-des-lieux/${d.id}/pdf`, `etat-des-lieux_${d.reference}.pdf`);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }
  protected readonly peutLouer = computed(() => this.auth.a(DROITS.locationEcrire));
  protected readonly peutEncaisser = computed(() => this.auth.a(DROITS.encaissementEcrire));
  protected readonly peutModifier = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  /** « Publier sur le site » (§ 4.10) : gestionnaires, éditeur web et Direction, pour un lot libre. */
  protected readonly peutPublier = computed(() => {
    const l = this.lot();
    return !!l && !l.archiveLe && (this.auth.a(DROITS.annonceEcrire) || this.auth.a(DROITS.annonceValider))
      && ['VACANT', 'A_LOUER', 'A_VENDRE', 'RESERVE'].includes(l.statut);
  });
  protected readonly direction = computed(() => ['DIRECTION', 'ADMIN'].includes(this.auth.compte()?.profil ?? ''));
  protected readonly louable = computed(() => {
    const l = this.lot();
    return !!l && !this.bail() && !l.archiveLe && !['EN_TRAVAUX', 'HORS_GESTION'].includes(l.statut);
  });
  /** Un lot marqué occupé dans l'ancien fichier, sans bail saisi : à régulariser. */
  protected readonly sansBail = computed(() => !this.bail() && ['OCCUPE', 'EN_PREAVIS'].includes(this.lot()?.statut ?? ''));

  // Saisies
  protected sb: SaisieBail = this.bailVide();
  protected enc = { montant: null as number | null, datePaiement: aujourdhui(), mode: 'ESPECES', referencePaiement: '', notes: '' };
  protected fin = { dateSortie: aujourdhui(), motif: '' };

  protected readonly f = { date, fcfa, surface, mois: moisEnClair };
  protected readonly aujourdhui = aujourdhui();

  constructor() {
    effect(() => {
      const id = this.idLot();
      untracked(() => this.charger(id));
    });
  }

  /** Publier passe le lot « À louer » ; un retrait ou une vente peuvent aussi le toucher : on le relit. */
  protected annonceChangee(): void {
    this.recharger().then(() => this.change.emit(this.lot()));
  }

  // ------------------------------------------------------------------ navigation
  protected aller(m: Mode): void {
    this.erreur.set(null);
    this.recu.set(null);
    const b = this.bail();
    if (m === 'bail') {
      this.sb = this.bailVide();
      if (!this.occupants().length) this.location.occupants().then((o) => this.occupants.set(o)).catch(() => undefined);
    } else if (m === 'encaisser' && b) {
      this.enc = { montant: Math.max(b.solde > 0 ? b.solde : 0, b.loyer + b.charges), datePaiement: aujourdhui(), mode: 'ESPECES', referencePaiement: '', notes: '' };
    } else if (m === 'preavis') {
      const d = new Date();
      d.setMonth(d.getMonth() + 1);
      this.fin = { dateSortie: d.toISOString().slice(0, 10), motif: '' };
    } else if (m === 'terminer') {
      this.fin = { dateSortie: aujourdhui(), motif: b?.motifFin ?? '' };
    }
    this.mode.set(m);
  }

  protected lotEnregistre(l: Lot): void {
    this.avis.succes(`Lot ${l.numero} enregistré.`);
    this.change.emit(l);
    this.mode.set('fiche');
    this.charger(this.idLot());
  }

  // ------------------------------------------------------------------ actions
  protected async signer(): Promise<void> {
    const l = this.lot();
    if (!l || this.occupe()) return;
    const s = this.sb;
    await this.executer(async () => {
      const r = await this.location.creerBail({
        idLot: l.id,
        idOccupant: s.occupantExistant ? s.idOccupant : null,
        nouvelOccupant: s.occupantExistant ? null : {
          typePersonne: 'PHYSIQUE', nom: s.nom, prenoms: s.prenoms, telephone: s.telephone, email: s.email, pieceType: s.pieceType,
          pieceNumero: s.pieceNumero, profession: s.profession, garantNom: s.garantNom, garantTelephone: s.garantTelephone,
        },
        dateEffet: s.dateEffet, dureeMois: s.dureeMois, loyer: s.loyer, charges: s.charges, caution: s.caution, avanceMois: s.avanceMois,
        jourExigibilite: s.jourExigibilite, avanceEncaissee: s.avanceEncaissee && s.avanceMois > 0 && this.peutEncaisser(), modeAvance: s.modeAvance,
      });
      this.avis.succes(`Bail ${r.bail.reference} signé : le lot ${l.numero} est occupé.`);
      this.occupants.set([]);
    });
  }

  protected async encaisser(): Promise<void> {
    const b = this.bail();
    if (!b || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const r = await this.location.encaisser({ idBail: b.id, ...this.enc });
      await this.recharger();
      this.recu.set({ encaissement: r.encaissement, periodes: r.periodes });
      this.change.emit(null);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async preavis(): Promise<void> {
    const b = this.bail();
    if (!b) return;
    await this.executer(async () => {
      await this.location.preavis(b.id, this.fin.dateSortie, this.fin.motif);
      this.avis.succes('Préavis enregistré : le lot est en préavis.');
    });
  }

  protected async terminer(): Promise<void> {
    const b = this.bail();
    if (!b) return;
    await this.executer(async () => {
      const r = await this.location.terminer(b.id, this.fin.dateSortie, this.fin.motif);
      this.avis.succes(r.solde > 0 ? `Bail terminé. Il reste ${fcfa(r.solde)} à recouvrer.` : 'Bail terminé : le lot est de nouveau vacant.');
    });
  }

  protected async quittance(e: Encaissement): Promise<void> {
    try {
      await this.location.ouvrirQuittance(e.id, e.numero);
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    }
  }

  protected async annuler(): Promise<void> {
    const e = this.aAnnuler();
    if (!e) return;
    try {
      await this.location.annuler(e.id, this.motifAnnulation);
      this.aAnnuler.set(null);
      this.motifAnnulation = '';
      this.avis.succes(`Encaissement ${e.numero} annulé.`);
      await this.recharger();
      this.change.emit(null);
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    }
  }

  // ------------------------------------------------------------------ affichage
  protected libelle(domaine: string, code: string | null | undefined): string {
    return this.ref.libelle(domaine, code);
  }

  protected tonBail(b: Bail): string {
    if (b.moisRetard >= 2) return 'critique';
    if (b.moisRetard === 1) return 'attention';
    return b.statut === 'PREAVIS' ? 'info' : 'bon';
  }

  protected texteSituation(b: Bail): string {
    if (b.moisRetard > 0) return `${b.moisRetard} mois de retard · ${fcfa(b.impaye)}`;
    if (b.solde < 0) return `À jour · ${fcfa(-b.solde)} d'avance`;
    if (b.solde > 0) return `${fcfa(b.solde)} dû, dans le délai`;
    return 'À jour';
  }

  protected totalAvance(): number {
    const s = this.sb;
    return (s.loyer ?? 0) + (s.charges ?? 0) > 0 ? s.avanceMois * ((s.loyer ?? 0) + (s.charges ?? 0)) : 0;
  }

  /** Les frais d'entrée du locataire : caution et avance (§ 3.7, simulateur de budget). */
  protected fraisEntree(): number {
    return (this.sb.caution ?? 0) + this.totalAvance();
  }

  protected resteApres(): number {
    const b = this.bail();
    return b ? b.solde - (this.enc.montant ?? 0) : 0;
  }

  // ------------------------------------------------------------------ chargement
  private async charger(id: number): Promise<void> {
    this.chargement.set(true);
    this.mode.set('fiche');
    this.recu.set(null);
    try {
      await Promise.all([this.recharger(id), this.ref.assurer()]);
    } finally {
      this.chargement.set(false);
    }
    if (this.ouvrirEn() === 'bail' && this.peutLouer() && (this.louable() || this.sansBail())) this.aller('bail');
  }

  private async recharger(id = this.idLot()): Promise<void> {
    try {
      this.donnees.set(await this.location.locationDuLot(id));
      const b = this.bail();
      this.etatsDesLieux.set(b ? await this.location.etatsDesLieux(b.id).catch(() => []) : []);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  private async executer(action: () => Promise<void>): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      await action();
      await this.recharger();
      this.mode.set('fiche');
      this.change.emit(this.lot());
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  private bailVide(): SaisieBail {
    const l = this.lot();
    const premier = new Date();
    premier.setDate(1);
    return {
      occupantExistant: false, idOccupant: null, nom: '', prenoms: '', telephone: '', email: '', pieceType: 'CNI', pieceNumero: '',
      profession: '', garantNom: '', garantTelephone: '',
      dateEffet: aujourdhui(), dureeMois: 12, loyer: l?.loyerReference ?? null, charges: l?.charges ?? 0,
      // Usage d'Abidjan : deux mois de caution et deux mois d'avance à l'entrée.
      caution: l?.loyerReference ? l.loyerReference * 2 : null, avanceMois: 2, jourExigibilite: 5,
      avanceEncaissee: true, modeAvance: 'ESPECES',
    };
  }
}
