import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { Colonne, Ligne, TableDonnees } from '../../composants/table-donnees';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { date, fcfa } from '../../noyau/format';
import { LocationService, moisEnClair } from '../../noyau/location.service';
import { Bail, SyntheseLoyers } from '../../noyau/modeles';
import { ReferentielService } from '../../noyau/referentiel.service';
import { FicheLot } from '../patrimoine/fiche-lot';

type Vue = 'retard' | 'actifs';

/**
 * Le suivi des loyers (SIG-08) : ce qui était attendu ce mois, ce qui en est réglé, ce qui
 * a été encaissé, les impayés en retard et les baux à relancer. Un clic sur un bail ouvre la
 * fiche de son lot, pour encaisser ou ouvrir une quittance sans quitter l'écran.
 */
@Component({
  selector: 'fact-loyers',
  imports: [RouterLink, Icone, EtatVide, Compteur, Apparition, PanneauLateral, TableDonnees, FicheLot],
  templateUrl: './loyers.html',
  styleUrl: './loyers.scss',
})
export class Loyers implements OnInit {
  private readonly location = inject(LocationService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** ?vue=actifs ouvre la liste de tous les baux en cours. */
  readonly vueAdresse = input<string | undefined>(undefined, { alias: 'vue' });

  protected readonly chargement = signal(true);
  protected readonly synthese = signal<SyntheseLoyers | null>(null);
  protected readonly baux = signal<Bail[]>([]);
  protected readonly vue = signal<Vue>('retard');
  protected readonly lotOuvert = signal<{ id: number; numero: string } | null>(null);

  protected readonly moisCourant = computed(() => {
    const m = this.synthese()?.synthese.mois ?? [];
    return m[m.length - 1] ?? null;
  });
  protected readonly taux = computed(() => {
    const m = this.moisCourant();
    return m && m.attendu ? Math.round((m.regle / m.attendu) * 1000) / 10 : 0;
  });
  /** Les six derniers mois, en hauteur relative au plus gros mois attendu. */
  protected readonly histogramme = computed(() => {
    const m = this.synthese()?.synthese.mois ?? [];
    const max = Math.max(1, ...m.map((x) => x.attendu));
    return m.map((x) => ({
      ...x,
      libelle: new Intl.DateTimeFormat('fr-FR', { month: 'short' }).format(new Date(x.periode + 'T00:00:00')),
      hAttendu: (x.attendu / max) * 100,
      hRegle: (x.regle / max) * 100,
      taux: x.attendu ? Math.round((x.regle / x.attendu) * 100) : null,
    }));
  });

  protected readonly enRetard = computed(() => this.baux().filter((b) => b.moisRetard > 0));
  protected readonly visibles = computed(() => (this.vue() === 'retard' ? this.enRetard() : this.baux()));

  protected readonly colonnes = computed<Colonne[]>(() => [
    { cle: 'batiment', libelle: 'Bâtiment' },
    { cle: 'numeroLot', libelle: 'Lot', format: 'reference' },
    { cle: 'occupant', libelle: 'Locataire' },
    ...(this.auth.compte()?.portee === 'TOUT' ? [{ cle: 'gestionnaire', libelle: 'Gestionnaire' } as Colonne] : []),
    { cle: 'loyerTotal', libelle: 'Loyer', format: 'fcfa' },
    { cle: 'situation', libelle: 'Situation', format: 'statut', cleLibelle: 'libelleSituation' },
    { cle: 'impaye', libelle: 'En retard', format: 'fcfa' },
    { cle: 'retardDepuis', libelle: 'Depuis', format: 'date' },
  ]);
  protected readonly lignes = computed<Ligne[]>(() => this.visibles().map((b) => ({
    ...b,
    loyerTotal: b.loyer + b.charges,
    situation: b.moisRetard >= 2 ? 'IMPAYE' : b.moisRetard === 1 ? 'PARTIEL' : b.statut === 'PREAVIS' ? 'EN_PREAVIS' : 'A_JOUR',
    libelleSituation: b.moisRetard ? `${b.moisRetard} mois de retard` : b.statut === 'PREAVIS' ? 'En préavis' : 'À jour',
    impaye: b.impaye || null,
  })));

  protected readonly f = { date, fcfa, mois: moisEnClair };

  async ngOnInit(): Promise<void> {
    if (this.vueAdresse() === 'actifs') this.vue.set('actifs');
    await this.charger();
    if (!this.enRetard().length && this.baux().length) this.vue.set('actifs');
  }

  protected ouvrir(l: Ligne): void {
    this.lotOuvert.set({ id: Number(l['idLot']), numero: String(l['numeroLot']) });
  }

  protected async quittance(id: number, numero: string): Promise<void> {
    try {
      await this.location.ouvrirQuittance(id, numero);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected async charger(): Promise<void> {
    try {
      const [synthese, baux] = await Promise.all([this.location.synthese(), this.location.baux('actifs'), this.ref.assurer()]);
      this.synthese.set(synthese);
      this.baux.set(baux);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
