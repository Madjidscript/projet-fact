import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { fcfa } from '../../noyau/format';
import { DROITS, Intervention, Prestataire, SyntheseTravaux } from '../../noyau/modeles';
import { ReferentielService } from '../../noyau/referentiel.service';
import { TravauxService, depuis } from '../../noyau/travaux.service';
import { FicheIntervention } from './fiche-intervention';
import { Signalement } from './signalement';

type Vue = 'affecter' | 'planifiees' | 'en-cours' | 'cloturer' | 'closes' | 'prestataires';

const STATUTS: Record<Exclude<Vue, 'prestataires'>, string[]> = {
  affecter: ['SIGNALEE'],
  planifiees: ['AFFECTEE'],
  'en-cours': ['EN_COURS', 'VALIDEE'],
  cloturer: ['TERMINEE'],
  closes: ['CLOTUREE', 'ANNULEE'],
};

const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Le suivi des interventions et des travaux (SIG-13) : les pannes à affecter, planifiées, en cours,
 * à clôturer, et l'historique ; le répertoire des prestataires. Un clic ouvre la fiche de
 * l'intervention, où se franchit l'étape suivante.
 */
@Component({
  selector: 'fact-travaux',
  imports: [FormsModule, Icone, EtatVide, Compteur, Apparition, PanneauLateral, FicheIntervention, Signalement],
  templateUrl: './travaux.html',
  styleUrl: './travaux.scss',
})
export class Travaux implements OnInit {
  private readonly travaux = inject(TravauxService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** ?vue=affecter, ?signaler=1 */
  readonly vueAdresse = input<string | undefined>(undefined, { alias: 'vue' });
  readonly signalerAdresse = input<string | undefined>(undefined, { alias: 'signaler' });
  /** ?intervention=12 : la fiche s'ouvre d'elle-même (lien d'une alerte). */
  readonly interventionAdresse = input<string | undefined>(undefined, { alias: 'intervention' });
  private dejaOuverte: number | null = null;

  constructor() {
    effect(() => {
      const id = Number(this.interventionAdresse());
      const liste = this.interventions();
      if (!id || id === this.dejaOuverte || !liste.length) return;
      untracked(() => {
        const i = liste.find((x) => x.id === id);
        if (i) {
          this.dejaOuverte = id;
          this.ouverte.set(i);
        }
      });
    });
  }

  protected readonly chargement = signal(true);
  protected readonly interventions = signal<Intervention[]>([]);
  protected readonly synthese = signal<SyntheseTravaux | null>(null);
  protected readonly prestataires = signal<Prestataire[]>([]);
  protected readonly vue = signal<Vue>('affecter');
  protected readonly recherche = signal('');
  protected readonly ouverte = signal<Intervention | null>(null);
  protected readonly signalement = signal(false);
  protected readonly prestataireOuvert = signal<Prestataire | 'nouveau' | null>(null);

  protected readonly peutSignaler = computed(() => this.auth.a(DROITS.travauxEcrire));
  protected readonly gereRepertoire = computed(() => this.auth.a(DROITS.travauxValider) || this.auth.compte()?.profil === 'ADMIN');

  protected readonly compte = (v: Exclude<Vue, 'prestataires'>) => this.interventions().filter((i) => STATUTS[v].includes(i.statut)).length;
  protected readonly visibles = computed(() => {
    const v = this.vue();
    if (v === 'prestataires') return [];
    const t = sansAccent(this.recherche().trim());
    return this.interventions().filter((i) => STATUTS[v].includes(i.statut) &&
      (!t || sansAccent(`${i.titre} ${i.reference} ${i.batiment} ${i.prestataire ?? ''} ${i.libelleCategorie}`).includes(t)));
  });
  protected readonly ouvertes = computed(() => this.interventions().filter((i) => i.ouverte).length);

  protected sp = { nom: '', specialite: null as string | null, telephone: '', email: '', notes: '', actif: true };
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected readonly f = { fcfa, depuis };

  async ngOnInit(): Promise<void> {
    const v = this.vueAdresse();
    if (v && ['affecter', 'planifiees', 'en-cours', 'cloturer', 'closes', 'prestataires'].includes(v)) this.vue.set(v as Vue);
    if (this.signalerAdresse() && this.peutSignaler()) this.signalement.set(true);
    await this.charger();
    // À l'ouverture, la première étape qui a du travail.
    if (!v) {
      const premiere = (['affecter', 'planifiees', 'en-cours', 'cloturer'] as const).find((x) => this.compte(x) > 0);
      if (premiere) this.vue.set(premiere);
    }
  }

  protected tonUrgence(u: string): string {
    return u === 'CRITIQUE' ? 'critique' : u === 'HAUTE' ? 'attention' : u === 'NORMALE' ? 'info' : 'neutre';
  }

  protected cree(i: Intervention): void {
    this.signalement.set(false);
    this.avis.succes(`Intervention ${i.reference} signalée.`);
    this.vue.set('affecter');
    this.charger().then(() => this.ouverte.set(this.interventions().find((x) => x.id === i.id) ?? null));
  }

  // ------------------------------------------------------------------ prestataires
  protected ouvrirPrestataire(p: Prestataire | 'nouveau'): void {
    this.erreur.set(null);
    this.sp = p === 'nouveau'
      ? { nom: '', specialite: null, telephone: '', email: '', notes: '', actif: true }
      : { nom: p.nom, specialite: p.specialite, telephone: p.telephone ?? '', email: p.email ?? '', notes: p.notes ?? '', actif: p.actif };
    this.prestataireOuvert.set(p);
  }

  protected async enregistrerPrestataire(): Promise<void> {
    const p = this.prestataireOuvert();
    if (!p || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      await this.travaux.enregistrerPrestataire({ ...this.sp, id: p === 'nouveau' ? null : p.id });
      this.prestataireOuvert.set(null);
      this.avis.succes('Prestataire enregistré.');
      this.prestataires.set(await this.travaux.prestataires(true));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async charger(): Promise<void> {
    try {
      const [interventions, synthese, prestataires] = await Promise.all([
        this.travaux.interventions(), this.travaux.synthese(), this.travaux.prestataires(true), this.ref.assurer(),
      ]);
      this.interventions.set(interventions);
      this.synthese.set(synthese);
      this.prestataires.set(prestataires);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
