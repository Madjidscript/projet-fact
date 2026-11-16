import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PhotoSecurisee } from '../../composants/photo-securisee';
import { CartePosition } from '../../composants/carte-position';
import { Confirmation } from '../../composants/confirmation';
import { EtatVide } from '../../composants/etat-vide';
import { Evenement, FilHistorique } from '../../composants/fil-historique';
import { Anneau, Part } from '../../composants/graphiques';
import { Icone } from '../../composants/icone';
import { Compteur } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { Colonne, Ligne, TableDonnees } from '../../composants/table-donnees';
import { LotEtage, VueEtages } from '../../composants/vue-etages';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { coordonnees, date, dateHeure, fcfa, surface } from '../../noyau/format';
import { Bail, Batiment, DROITS, LigneJournal, Lot, Proprietaire } from '../../noyau/modeles';
import { LocationService } from '../../noyau/location.service';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import { Ton } from '../../noyau/statuts';
import { FormulaireBatiment } from './formulaire-batiment';
import { FicheLot } from './fiche-lot';
import { FicheIntervention } from '../travaux/fiche-intervention';
import { Signalement } from '../travaux/signalement';
import { TravauxService, depuis } from '../../noyau/travaux.service';
import { Intervention } from '../../noyau/modeles';
import { FormulaireLot } from './formulaire-lot';

type Onglet = 'identite' | 'lots' | 'location' | 'interventions' | 'proprietaire' | 'historique';

/**
 * La fiche d'un bâtiment (SIG-02) et de ses lots (SIG-03), en onglets (§ 4.2) : Identité,
 * Lots (vue par étage et tableau), Propriétaire, Historique. Les onglets Mandat, Interventions
 * et Documents arriveront avec leurs étapes.
 */
@Component({
  selector: 'fact-fiche-batiment',
  imports: [RouterLink, Icone, PhotoSecurisee, CartePosition, Confirmation, EtatVide, FilHistorique, Anneau, Compteur, PanneauLateral,
    TableDonnees, VueEtages, FormulaireBatiment, FormulaireLot, FicheLot, FicheIntervention, Signalement],
  templateUrl: './fiche-batiment.html',
  styleUrl: './patrimoine.scss',
})
export class FicheBatiment {
  private readonly patrimoine = inject(PatrimoineService);
  private readonly locationService = inject(LocationService);
  private readonly travauxService = inject(TravauxService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** L'identifiant lu dans l'adresse (/patrimoine/:id). */
  readonly id = input.required<string>();
  /** ?lot=77 : la fiche de ce lot s'ouvre d'elle-même (lien d'une alerte). */
  readonly lotAdresse = input<string | undefined>(undefined, { alias: 'lot' });
  private lotDejaOuvert: string | null = null;

  protected readonly chargement = signal(true);
  protected readonly introuvable = signal(false);
  protected readonly batiment = signal<Batiment | null>(null);
  protected readonly lots = signal<Lot[]>([]);
  protected readonly photos = signal<{ id: number; priseLe: string | null; creePar: string | null }[]>([]);
  protected readonly proprietaire = signal<Proprietaire | null>(null);
  protected readonly historique = signal<LigneJournal[] | null>(null);
  protected readonly onglet = signal<Onglet>('identite');

  protected readonly modification = signal(false);
  protected readonly lotOuvert = signal<Lot | 'nouveau' | null>(null);
  protected readonly lotModifie = signal<number | null>(null);
  /** Le lot dont la fiche (caractéristiques et location) est ouverte. */
  protected readonly lotFiche = signal<Lot | null>(null);
  /** Vrai : la fiche du lot s'ouvre directement sur la signature du bail. */
  protected readonly lotEnBail = signal(false);
  protected readonly peutLouer = computed(() => this.auth.a(DROITS.locationEcrire) && !this.batiment()?.archiveLe);
  protected readonly baux = signal<Bail[] | null>(null);
  protected readonly voitLocation = computed(() => this.auth.a(DROITS.locationLire));
  protected readonly voitTravaux = computed(() => this.auth.a(DROITS.travauxLire));
  protected readonly peutSignaler = computed(() => this.auth.a(DROITS.travauxEcrire) && !this.batiment()?.archiveLe);
  protected readonly interventions = signal<Intervention[] | null>(null);
  protected readonly interventionOuverte = signal<Intervention | null>(null);
  protected readonly signalement = signal(false);
  protected readonly depuis = depuis;
  protected readonly aConfirmer = signal<{ genre: 'archiver-batiment' | 'archiver-lot' | 'valider'; lot?: Lot } | null>(null);

  protected readonly peutModifier = computed(() => this.auth.a(DROITS.patrimoineEcrire) && !this.batiment()?.archiveLe);
  protected readonly peutValider = computed(() =>
    this.peutModifier() && this.auth.compte()?.profil !== 'AGENT' && this.batiment()?.validation === 'A_VALIDER');
  protected readonly peutArchiver = computed(() => this.auth.a(DROITS.patrimoineEcrire) && this.auth.compte()?.portee === 'TOUT'
    && ['DIRECTION', 'ADMIN'].includes(this.auth.compte()?.profil ?? ''));
  protected readonly voitHistorique = computed(() => this.auth.a(DROITS.patrimoineEcrire));

  protected readonly position = computed(() => {
    const b = this.batiment();
    return b && b.latitude !== null && b.longitude !== null ? { latitude: b.latitude, longitude: b.longitude, precision: b.precisionGps } : null;
  });

  protected readonly occupation = computed<Part[]>(() => {
    const l = this.lots();
    const compte = (statuts: string[]) => l.filter((x) => statuts.includes(x.statut)).length;
    return [
      { libelle: 'Occupés', valeur: compte(['OCCUPE', 'EN_PREAVIS']), ton: 'bon' },
      { libelle: 'Vacants ou à louer', valeur: compte(['VACANT', 'A_LOUER', 'A_VENDRE']), ton: 'attention' },
      { libelle: 'Réservés', valeur: compte(['RESERVE']), ton: 'info' },
      { libelle: 'En travaux', valeur: compte(['EN_TRAVAUX']), ton: 'neutre' },
    ].filter((p) => p.valeur > 0) as Part[];
  });

  protected readonly lotsEtages = computed<LotEtage[]>(() => this.lots().map((l) => ({
    id: l.id, numero: l.numero, etage: l.etage, type: l.libelleType, statut: l.statut, libelleStatut: l.libelleStatut,
  })));

  protected readonly colonnesLots: Colonne[] = [
    { cle: 'numero', libelle: 'Porte', format: 'reference' },
    { cle: 'etage', libelle: 'Étage', format: 'entier' },
    { cle: 'libelleType', libelle: 'Type' },
    { cle: 'surface', libelle: 'Surface', format: 'm2' },
    { cle: 'pieces', libelle: 'Pièces', format: 'entier' },
    { cle: 'loyerReference', libelle: 'Loyer', format: 'fcfa' },
    { cle: 'statut', libelle: 'Statut', format: 'statut', cleLibelle: 'libelleStatut' },
    { cle: 'statutDepuis', libelle: 'Depuis le', format: 'date' },
  ];
  protected readonly lignesLots = computed(() => this.lots() as unknown as Ligne[]);

  protected readonly evenements = computed<Evenement[]>(() => (this.historique() ?? []).map((h) => ({
    le: h.le, auteur: h.auteur, action: h.action, detail: this.detail(h), ton: this.ton(h.action),
  })));

  protected readonly f = { date, dateHeure, fcfa, surface, coordonnees };

  constructor() {
    effect(() => {
      const id = Number(this.id());
      untracked(() => this.charger(id));
    });
    effect(() => {
      const lot = this.lotAdresse();
      const lots = this.lots();
      const cle = this.id() + '/' + lot;
      if (!lot || !lots.length || cle === this.lotDejaOuvert) return;
      untracked(() => {
        if (lots.some((l) => l.id === Number(lot))) {
          this.lotDejaOuvert = cle;
          this.ouvrirLot(Number(lot));
        }
      });
    });
  }

  protected choisirOnglet(o: Onglet): void {
    this.onglet.set(o);
    if (o === 'historique' && this.historique() === null) this.chargerHistorique();
    if (o === 'location' && this.baux() === null) this.chargerBaux();
    if (o === 'interventions' && this.interventions() === null) this.chargerInterventions();
  }

  protected libelle(domaine: string, code: string | null | undefined): string {
    return this.ref.libelle(domaine, code);
  }

  protected ouvrirLot(id: number): void {
    const lot = this.lots().find((l) => l.id === id);
    if (lot) {
      this.lotEnBail.set(false);
      this.lotFiche.set(lot);
    }
  }

  /** « Ajouter un locataire » : la fiche du lot s'ouvre sur le formulaire du bail. */
  protected louer(lot: Lot): void {
    this.lotEnBail.set(true);
    this.lotFiche.set(lot);
  }

  /** Un lot, un bail ou un encaissement a changé dans la fiche du lot : les décomptes suivent. */
  protected lotChange(l: Lot | null): void {
    if (l) this.lots.update((liste) => liste.map((x) => (x.id === l.id ? l : x)));
    this.historique.set(null);
    this.baux.set(null);
    this.rafraichirBatiment();
    if (this.onglet() === 'location') this.chargerBaux();
    if (l) this.marquer(l.id);
  }

  protected readonly colonnesBaux: Colonne[] = [
    { cle: 'numeroLot', libelle: 'Lot', format: 'reference' },
    { cle: 'occupant', libelle: 'Locataire' },
    { cle: 'loyerTotal', libelle: 'Loyer et charges', format: 'fcfa' },
    { cle: 'situation', libelle: 'Situation', format: 'statut', cleLibelle: 'libelleSituation' },
    { cle: 'soldeDu', libelle: 'Solde dû', format: 'fcfa' },
    { cle: 'dateEffet', libelle: 'Depuis le', format: 'date' },
  ];
  protected readonly lignesBaux = computed<Ligne[]>(() => (this.baux() ?? []).filter((b) => b.statut === 'ACTIF' || b.statut === 'PREAVIS')
    .map((b) => ({
      ...b,
      loyerTotal: b.loyer + b.charges,
      soldeDu: Math.max(0, b.solde),
      situation: b.moisRetard >= 2 ? 'IMPAYE' : b.moisRetard === 1 ? 'PARTIEL' : b.statut === 'PREAVIS' ? 'EN_PREAVIS' : 'A_JOUR',
      libelleSituation: b.moisRetard ? `${b.moisRetard} mois de retard` : b.statut === 'PREAVIS' ? 'En préavis' : 'À jour',
    })));
  /** Les lots marqués occupés sans bail saisi : à régulariser. */
  protected readonly sansBail = computed(() => {
    const avecBail = new Set((this.baux() ?? []).filter((b) => b.statut === 'ACTIF' || b.statut === 'PREAVIS').map((b) => b.idLot));
    return this.lots().filter((l) => (l.statut === 'OCCUPE' || l.statut === 'EN_PREAVIS') && !avecBail.has(l.id));
  });
  /** Les lots qu'on peut louer : libres, non archivés, sans bail en cours. */
  protected readonly lotsLibres = computed(() => {
    const avecBail = new Set((this.baux() ?? []).filter((b) => b.statut === 'ACTIF' || b.statut === 'PREAVIS').map((b) => b.idLot));
    return this.lots().filter((l) => ['VACANT', 'A_LOUER', 'A_VENDRE', 'RESERVE'].includes(l.statut) && !avecBail.has(l.id));
  });
  protected readonly numerosSansBail = computed(() => this.sansBail().map((x) => x.numero).join(', '));

  protected ouvrirBail(l: Ligne): void {
    this.ouvrirLot(Number(l['idLot']));
  }

  protected tonUrgence(u: string): string {
    return u === 'CRITIQUE' ? 'critique' : u === 'HAUTE' ? 'attention' : u === 'NORMALE' ? 'info' : 'neutre';
  }

  protected interventionSignalee(i: Intervention): void {
    this.signalement.set(false);
    this.avis.succes(`Intervention ${i.reference} signalée.`);
    this.interventionChange();
    this.interventionOuverte.set(i);
  }

  protected interventionChange(): void {
    this.chargerInterventions();
    this.historique.set(null);
    this.rafraichirBatiment();
  }

  private async chargerInterventions(): Promise<void> {
    const b = this.batiment();
    if (!b) return;
    try {
      this.interventions.set(await this.travauxService.interventions(undefined, b.id));
    } catch (e) {
      this.interventions.set([]);
      this.avis.erreur(messageErreur(e));
    }
  }

  private async chargerBaux(): Promise<void> {
    const b = this.batiment();
    if (!b) return;
    try {
      this.baux.set(await this.locationService.baux(undefined, b.id));
    } catch (e) {
      this.baux.set([]);
      this.avis.erreur(messageErreur(e));
    }
  }

  protected ouvrirLigne(l: Ligne): void {
    this.ouvrirLot(Number(l['id']));
  }

  protected etageSuivant(): number {
    const l = this.lots();
    return l.length ? l[l.length - 1].etage : 0;
  }

  // ------------------------------------------------------------ enregistrements
  protected batimentEnregistre(b: Batiment): void {
    this.batiment.set(b);
    this.modification.set(false);
    this.historique.set(null);
    this.avis.succes('Bâtiment enregistré.');
    this.rechargerProprietaire(b);
  }

  protected lotEnregistre(l: Lot): void {
    const nouveau = !this.lots().some((x) => x.id === l.id);
    this.lots.update((liste) => (nouveau ? [...liste, l] : liste.map((x) => (x.id === l.id ? l : x)))
      .sort((a, b) => a.etage - b.etage || a.numero.localeCompare(b.numero, 'fr', { numeric: true })));
    this.lotOuvert.set(null);
    this.marquer(l.id);
    this.historique.set(null);
    this.avis.succes(nouveau ? `Lot ${l.numero} ajouté.` : `Lot ${l.numero} enregistré.`);
    this.rafraichirBatiment();
  }

  protected async confirmer(): Promise<void> {
    const demande = this.aConfirmer();
    const b = this.batiment();
    if (!demande || !b) return;
    this.aConfirmer.set(null);
    try {
      if (demande.genre === 'valider') {
        this.batiment.set(await this.patrimoine.validerBatiment(b.id));
        this.avis.succes('Bâtiment validé.');
      } else if (demande.genre === 'archiver-batiment') {
        this.batiment.set(await this.patrimoine.archiverBatiment(b.id, !b.archiveLe));
        this.avis.succes(b.archiveLe ? 'Bâtiment restauré.' : 'Bâtiment archivé : il reste consultable.');
      } else if (demande.lot) {
        await this.patrimoine.archiverLot(demande.lot.id, true);
        this.lots.update((l) => l.filter((x) => x.id !== demande.lot!.id));
        this.lotOuvert.set(null);
        this.lotFiche.set(null);
        this.avis.succes(`Lot ${demande.lot.numero} archivé.`);
        this.rafraichirBatiment();
      }
      this.historique.set(null);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  // ------------------------------------------------------------------ chargement
  private async charger(id: number): Promise<void> {
    this.chargement.set(true);
    this.introuvable.set(false);
    this.historique.set(null);
    this.baux.set(null);
    this.interventions.set(null);
    this.lotFiche.set(null);
    this.onglet.set('identite');
    try {
      const [fiche] = await Promise.all([this.patrimoine.batiment(id), this.ref.assurer()]);
      this.batiment.set(fiche.batiment);
      this.photos.set(fiche.photos ?? []);
      this.lots.set(fiche.lots);
      this.proprietaire.set(fiche.proprietaire);
    } catch (e) {
      this.introuvable.set(true);
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private async chargerHistorique(): Promise<void> {
    const b = this.batiment();
    if (!b) return;
    try {
      this.historique.set(await this.patrimoine.historique(b.id));
    } catch (e) {
      this.historique.set([]);
      this.avis.erreur(messageErreur(e));
    }
  }

  /** Les décomptes du bâtiment suivent ses lots. */
  private async rafraichirBatiment(): Promise<void> {
    const b = this.batiment();
    if (!b) return;
    try {
      const fiche = await this.patrimoine.batiment(b.id);
      this.batiment.set(fiche.batiment);
      this.lots.set(fiche.lots);
    } catch {
      /* les lots sont à jour ; seuls les décomptes attendront le prochain chargement */
    }
  }

  private async rechargerProprietaire(b: Batiment): Promise<void> {
    if (this.proprietaire()?.id === b.idProprietaire) return;
    try {
      this.proprietaire.set((await this.patrimoine.batiment(b.id)).proprietaire);
    } catch {
      /* rien */
    }
  }

  private marquer(id: number): void {
    this.lotModifie.set(null);
    setTimeout(() => this.lotModifie.set(id));
  }

  /** Une phrase qui résume ce qui a changé, à partir de l'ancienne et de la nouvelle valeur. */
  private detail(h: LigneJournal): string | undefined {
    if (!h.avant || !h.apres) return undefined;
    const libelles: Record<string, string> = {
      nom: 'nom', type: 'type', adresse: 'adresse', commune: 'commune', quartier: 'quartier', proprietaire: 'propriétaire',
      gestionnaire: 'gestionnaire', suppleant: 'suppléant', niveaux: 'niveaux', latitude: 'position', numero: 'numéro',
      etage: 'étage', libelleType: 'type', surface: 'surface', pieces: 'pièces', loyerReference: 'loyer', charges: 'charges',
      libelleStatut: 'statut', validation: 'validation', description: 'description', equipements: 'équipements',
    };
    const changes = Object.keys(libelles).filter((c) => c in h.apres! && JSON.stringify(h.avant![c]) !== JSON.stringify(h.apres![c]));
    return changes.length ? 'Modifié : ' + [...new Set(changes.map((c) => libelles[c]))].join(', ') : undefined;
  }

  private ton(action: string): Ton {
    if (/créé|ajouté/i.test(action)) return 'info';
    if (/validé|restauré/i.test(action)) return 'bon';
    if (/archivé/i.test(action)) return 'neutre';
    if (/→/.test(action)) return 'accent';
    return 'neutre';
  }
}
