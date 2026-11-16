import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Etapes } from '../../composants/etapes';
import { Icone } from '../../composants/icone';
import { PhotoSecurisee } from '../../composants/photo-securisee';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { date, dateHeure, fcfa } from '../../noyau/format';
import { aujourdhui } from '../../noyau/location.service';
import { FicheIntervention as Fiche, PhotoIntervention, Prestataire } from '../../noyau/modeles';
import { ReferentielService } from '../../noyau/referentiel.service';
import { ETAPES_INTERVENTION, TravauxService, depuis, rangEtape } from '../../noyau/travaux.service';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

type Action = 'affecter' | 'demarrer' | 'valider' | 'terminer' | 'cloturer' | 'annuler';

const LIBELLES: Record<Action, string> = {
  affecter: 'Affecter à un prestataire',
  demarrer: 'Enregistrer le constat',
  valider: 'Valider le devis',
  terminer: 'Travaux terminés',
  cloturer: 'Clôturer',
  annuler: 'Annuler l\'intervention',
};

/**
 * La fiche d'une intervention (SIG-13) : sa progression dans le cycle, ce qui a été dit à chaque
 * étape, les photos avant et après, et l'étape suivante que le compte peut lui faire franchir.
 * Les actions proposées sont celles que l'API autorise à ce compte (champ « actions »).
 */
@Component({
  selector: 'fact-fiche-intervention',
  imports: [FormsModule, RouterLink, Icone, Etapes, PhotoSecurisee, Choix, OptionsPipe],
  templateUrl: './fiche-intervention.html',
  styleUrl: './fiche-intervention.scss',
})
export class FicheIntervention {
  protected readonly opt = OPTIONS;
  private readonly travaux = inject(TravauxService);
  private readonly avis = inject(AvisService);
  protected readonly ref = inject(ReferentielService);

  readonly idIntervention = input.required<number>();
  readonly change = output<void>();

  protected readonly chargement = signal(true);
  protected readonly fiche = signal<Fiche | null>(null);
  protected readonly action = signal<Action | null>(null);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly prestataires = signal<Prestataire[]>([]);
  protected readonly envoi = signal<'AVANT' | 'APRES' | null>(null);
  protected readonly agrandie = signal<PhotoIntervention | null>(null);

  protected readonly i = computed(() => this.fiche()?.intervention ?? null);
  protected readonly etapes = ETAPES_INTERVENTION;
  protected readonly courante = computed(() => {
    const i = this.i();
    if (!i) return 0;
    if (i.statut === 'CLOTUREE') return this.etapes.length;
    return Math.max(0, rangEtape(i.statut));
  });
  protected readonly actionsEtape = computed(() =>
    (this.fiche()?.actions ?? []).filter((a): a is Action => a !== 'photos'));
  protected readonly peutPhotos = computed(() => this.fiche()?.actions.includes('photos') ?? false);
  protected readonly photosAvant = computed(() => (this.fiche()?.photos ?? []).filter((p) => p.moment === 'AVANT'));
  protected readonly photosApres = computed(() => (this.fiche()?.photos ?? []).filter((p) => p.moment === 'APRES'));
  protected readonly devisAuDela = computed(() => (this.i()?.devisMontant ?? 0) > (this.fiche()?.seuilAccordProprietaire ?? Infinity));

  /** La saisie de l'étape en cours. */
  protected s = this.saisieVide();

  protected readonly f = { date, dateHeure, fcfa, depuis };
  protected readonly libelles = LIBELLES;
  protected readonly aujourdhui = aujourdhui();

  constructor() {
    effect(() => {
      const id = this.idIntervention();
      untracked(() => this.charger(id));
    });
  }

  protected ouvrir(a: Action): void {
    this.erreur.set(null);
    this.s = this.saisieVide();
    const i = this.i();
    if (a === 'affecter') {
      this.s.idPrestataire = i?.idPrestataire ?? null;
      this.s.datePrevue = i?.datePrevue ?? aujourdhui();
      if (!this.prestataires().length) this.travaux.prestataires().then((p) => this.prestataires.set(p)).catch(() => undefined);
    }
    if (a === 'terminer') this.s.coutReel = i?.devisMontant ?? null;
    if (a === 'cloturer') this.s.imputation = 'PROPRIETAIRE';
    this.action.set(a);
  }

  protected async valider(): Promise<void> {
    const a = this.action(), i = this.i();
    if (!a || !i || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      // Sans devis coché, aucun montant ne part, même saisi puis décoché.
      const corps = a === 'demarrer' && !this.s.avecDevis ? { ...this.s, devisMontant: null } : this.s;
      this.fiche.set(await this.travaux.etape(i.id, a, corps));
      this.action.set(null);
      this.avis.succes(`${i.reference} : ${this.fiche()!.intervention.libelleStatut.toLowerCase()}.`);
      this.change.emit();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async envoyer(moment: 'AVANT' | 'APRES', evenement: Event): Promise<void> {
    const champ = evenement.target as HTMLInputElement;
    const fichiers = Array.from(champ.files ?? []);
    champ.value = '';
    const i = this.i();
    if (!fichiers.length || !i) return;
    this.envoi.set(moment);
    try {
      const photos = await this.travaux.envoyerPhotos(i.id, moment, fichiers);
      this.fiche.update((f) => (f ? { ...f, photos, intervention: { ...f.intervention, photos: photos.length } } : f));
      this.avis.succes(`${fichiers.length} photo(s) ajoutée(s).`);
      this.change.emit();
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.envoi.set(null);
    }
  }

  protected libelle(domaine: string, code: string | null | undefined): string {
    return this.ref.libelle(domaine, code);
  }

  protected tonUrgence(u: string): string {
    return u === 'CRITIQUE' ? 'critique' : u === 'HAUTE' ? 'attention' : u === 'NORMALE' ? 'info' : 'neutre';
  }

  private async charger(id: number): Promise<void> {
    this.chargement.set(true);
    this.action.set(null);
    try {
      const [fiche] = await Promise.all([this.travaux.fiche(id), this.ref.assurer()]);
      this.fiche.set(fiche);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private saisieVide() {
    return {
      idPrestataire: null as number | null, datePrevue: null as string | null, constat: '', devisMontant: null as number | null,
      accordProprietaire: false, coutReel: null as number | null, imputation: null as string | null, note: '',
      avecDevis: false,
    };
  }
}
