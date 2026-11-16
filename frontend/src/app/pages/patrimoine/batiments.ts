import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { Colonne, Ligne, TableDonnees } from '../../composants/table-donnees';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { fcfa } from '../../noyau/format';
import { Batiment, DROITS } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import { FormulaireBatiment } from './formulaire-batiment';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

type Filtre = 'tous' | 'a-valider' | 'a-localiser' | 'sans-gestionnaire';

const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Les bâtiments du périmètre du compte connecté : tout le patrimoine pour la Direction, son
 * portefeuille pour un gestionnaire, ses biens pour un propriétaire (§ 2.2). Les listes
 * « À valider » et « À localiser » servent la reprise du patrimoine (§ 7.3). La carte des
 * bâtiments arrive à l'étape 5 ; cette liste lui servira de panneau.
 */
@Component({
  selector: 'fact-batiments',
  imports: [FormsModule, Icone, EtatVide, PanneauLateral, TableDonnees, Apparition, Compteur, FormulaireBatiment, Choix, OptionsPipe],
  templateUrl: './batiments.html',
  styleUrl: './patrimoine.scss',
})
export class Batiments implements OnInit {
  protected readonly opt = OPTIONS;
  private readonly patrimoine = inject(PatrimoineService);
  private readonly router = inject(Router);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** Paramètres de l'adresse : ?filtre=a-valider, ?nouveau=1. */
  readonly filtreAdresse = input<string | undefined>(undefined, { alias: 'filtre' });
  readonly nouveau = input<string | undefined>();

  protected readonly chargement = signal(true);
  protected readonly batiments = signal<Batiment[]>([]);
  protected readonly recherche = signal('');
  protected readonly commune = signal<number | null>(null);
  protected readonly filtre = signal<Filtre>('tous');
  protected readonly vue = signal<'cartes' | 'tableau'>('cartes');
  protected readonly creation = signal(false);

  protected readonly peutCreer = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  protected readonly voitGestion = computed(() => this.auth.compte()?.portee === 'TOUT');

  protected readonly aValider = computed(() => this.batiments().filter((b) => b.validation === 'A_VALIDER').length);
  protected readonly aLocaliser = computed(() => this.batiments().filter((b) => b.latitude === null).length);
  protected readonly sansGestionnaire = computed(() => this.batiments().filter((b) => b.idGestionnaire === null).length);

  /** Les communes où le périmètre compte au moins un bâtiment. */
  protected readonly communes = computed(() => {
    const presentes = new Set(this.batiments().map((b) => b.idCommune));
    return this.ref.communes().filter((c) => presentes.has(c.id));
  });

  protected readonly visibles = computed(() => {
    const texte = sansAccent(this.recherche().trim());
    const commune = this.commune();
    const filtre = this.filtre();
    return this.batiments().filter((b) =>
      (!commune || b.idCommune === commune) &&
      (filtre !== 'a-valider' || b.validation === 'A_VALIDER') &&
      (filtre !== 'a-localiser' || b.latitude === null) &&
      (filtre !== 'sans-gestionnaire' || b.idGestionnaire === null) &&
      (!texte || sansAccent(`${b.nom} ${b.reference} ${b.proprietaire} ${b.quartier ?? ''} ${b.commune} ${b.gestionnaire ?? ''} ${b.adresse ?? ''}`).includes(texte)));
  });

  protected readonly totaux = computed(() => {
    const l = this.visibles();
    return {
      lots: l.reduce((t, b) => t + b.lots, 0),
      occupes: l.reduce((t, b) => t + b.lotsOccupes, 0),
      loyers: l.reduce((t, b) => t + b.loyersReference, 0),
    };
  });

  protected readonly colonnes: Colonne[] = [
    { cle: 'reference', libelle: 'Référence', format: 'reference' },
    { cle: 'nom', libelle: 'Bâtiment' },
    { cle: 'commune', libelle: 'Commune' },
    { cle: 'quartier', libelle: 'Quartier' },
    { cle: 'proprietaire', libelle: 'Propriétaire' },
    { cle: 'gestionnaire', libelle: 'Gestionnaire' },
    { cle: 'lots', libelle: 'Lots', format: 'entier' },
    { cle: 'lotsOccupes', libelle: 'Occupés', format: 'entier' },
    { cle: 'loyersReference', libelle: 'Loyers de référence', format: 'fcfa' },
    { cle: 'etat', libelle: 'État', format: 'statut', cleLibelle: 'libelleEtat' },
  ];
  protected readonly lignes = computed<Ligne[]>(() => this.visibles().map((b) => ({
    ...b,
    etat: b.validation === 'A_VALIDER' ? 'A_VALIDER' : 'ACTIF',
    libelleEtat: b.validation === 'A_VALIDER' ? 'À valider' : 'Validé',
  })));

  protected readonly fcfa = fcfa;

  constructor() {
    effect(() => {
      const f = this.filtreAdresse(), n = this.nouveau();
      untracked(() => {
        if (f === 'a-valider' || f === 'a-localiser' || f === 'sans-gestionnaire') this.filtre.set(f);
        if (n && this.peutCreer()) this.creation.set(true);
      });
    });
  }

  async ngOnInit(): Promise<void> {
    try {
      const [batiments] = await Promise.all([this.patrimoine.batiments(), this.ref.assurer()]);
      this.batiments.set(batiments);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrir(id: number): void {
    this.router.navigate(['/patrimoine', id]);
  }

  protected ouvrirLigne(l: Ligne): void {
    this.ouvrir(Number(l['id']));
  }

  protected cree(b: Batiment): void {
    this.creation.set(false);
    this.avis.succes(b.validation === 'A_VALIDER' ? 'Bâtiment recensé : il attend la validation du gestionnaire.' : 'Bâtiment créé.');
    this.ouvrir(b.id);
  }

  /** Part des lots occupés, pour la jauge ; sa couleur suit la légende « Occupation » (§ 4.1). */
  protected occupation(b: Batiment): { part: number; ton: string } {
    if (!b.lots) return { part: 0, ton: '' };
    const part = (b.lotsOccupes / b.lots) * 100;
    const vacants = b.lots - b.lotsOccupes;
    return { part, ton: vacants === 0 ? '' : vacants > b.lots / 2 ? 'critique' : 'attention' };
  }

  protected libelleType(code: string): string {
    return this.ref.libelle('TYPE_BATIMENT', code);
  }
}
