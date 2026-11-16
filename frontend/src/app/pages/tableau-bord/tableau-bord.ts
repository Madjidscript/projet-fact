import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EtatVide } from '../../composants/etat-vide';
import { Anneau, Barres, Part } from '../../composants/graphiques';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { Batiment, DROITS, SyntheseLoyers } from '../../noyau/modeles';
import { LocationService } from '../../noyau/location.service';
import { TravauxService } from '../../noyau/travaux.service';
import { SyntheseTravaux } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';

interface Action {
  nombre: number;
  texte: string;
  lien: string;
  params?: Record<string, string>;
  icone: string;
  ton: 'attention' | 'critique' | 'info';
}

/**
 * Le tableau de bord : les chiffres du patrimoine que le compte connecté a sous sa garde
 * (tout pour la Direction, son portefeuille pour un gestionnaire, ses biens pour un
 * propriétaire), et ce qui reste à traiter. Loyers encaissés, impayés et échéances s'y
 * ajouteront avec la gestion locative.
 */
@Component({
  selector: 'fact-tableau-bord',
  imports: [RouterLink, Icone, EtatVide, Anneau, Barres, Compteur, Apparition],
  templateUrl: './tableau-bord.html',
  styleUrl: './tableau-bord.scss',
})
export class TableauBord implements OnInit {
  private readonly patrimoine = inject(PatrimoineService);
  private readonly location = inject(LocationService);
  private readonly travaux = inject(TravauxService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);

  protected readonly chargement = signal(true);
  protected readonly batiments = signal<Batiment[]>([]);
  protected readonly loyers = signal<SyntheseLoyers | null>(null);
  protected readonly voitLoyers = computed(() => this.auth.a(DROITS.locationLire));
  protected readonly voitTravaux = computed(() => this.auth.a(DROITS.travauxLire));
  protected readonly syntheseTravaux = signal<SyntheseTravaux | null>(null);
  /** Le mois en cours : attendu, réglé, encaissé ; et le taux de recouvrement. */
  protected readonly moisLoyers = computed(() => {
    const m = this.loyers()?.synthese.mois ?? [];
    const c = m[m.length - 1];
    return c ? { ...c, taux: c.attendu ? Math.round((c.regle / c.attendu) * 1000) / 10 : 0 } : null;
  });

  protected readonly prenom = computed(() => {
    const nom = this.auth.compte()?.nom ?? '';
    const mots = nom.split(/\s+/).filter(Boolean);
    return mots.length > 1 ? mots[mots.length - 1] : nom;
  });
  protected readonly aujourdhui = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
    .format(new Date());
  protected readonly perimetre = computed(() => {
    switch (this.auth.compte()?.portee) {
      case 'SON_PORTEFEUILLE': return 'Votre portefeuille';
      case 'SES_BIENS': return 'Vos biens';
      default: return "Tout le patrimoine de l'agence";
    }
  });

  protected readonly peutCreer = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  protected readonly direction = computed(() => this.auth.a(DROITS.parametresLire));

  protected readonly chiffres = computed(() => {
    const l = this.batiments();
    const lots = l.reduce((t, b) => t + b.lots, 0);
    const occupes = l.reduce((t, b) => t + b.lotsOccupes, 0);
    const vacants = l.reduce((t, b) => t + b.lotsVacants, 0);
    return {
      batiments: l.length,
      lots,
      occupes,
      vacants,
      autres: Math.max(0, lots - occupes - vacants),
      taux: lots ? Math.round((occupes / lots) * 1000) / 10 : 0,
      loyers: l.reduce((t, b) => t + b.loyersReference, 0),
      communes: new Set(l.map((b) => b.idCommune)).size,
    };
  });

  protected readonly occupation = computed<Part[]>(() => {
    const c = this.chiffres();
    return [
      { libelle: 'Occupés', valeur: c.occupes, ton: 'bon' },
      { libelle: 'Vacants ou à louer', valeur: c.vacants, ton: 'attention' },
      { libelle: 'Réservés ou en travaux', valeur: c.autres, ton: 'info' },
    ].filter((p) => p.valeur > 0) as Part[];
  });

  protected readonly vacantsParCommune = computed<Part[]>(() => {
    const parCommune = new Map<string, number>();
    for (const b of this.batiments()) if (b.lotsVacants) parCommune.set(b.commune, (parCommune.get(b.commune) ?? 0) + b.lotsVacants);
    return [...parCommune.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
      .map(([libelle, valeur]) => ({ libelle, valeur, ton: 'attention' }));
  });

  /** Ce qui attend une action, du plus pressant au moins pressant. */
  protected readonly actions = computed<Action[]>(() => {
    const l = this.batiments();
    const aValider = l.filter((b) => b.validation === 'A_VALIDER').length;
    const aLocaliser = l.filter((b) => b.latitude === null).length;
    const sansGestionnaire = l.filter((b) => b.idGestionnaire === null).length;
    const sansLot = l.filter((b) => !b.lots).length;
    const r: Action[] = [];
    const urgentes = this.syntheseTravaux()?.synthese.urgentes ?? 0;
    if (urgentes) {
      r.push({ nombre: urgentes, texte: urgentes > 1 ? 'interventions urgentes en cours' : 'intervention urgente en cours',
        lien: '/travaux', icone: 'outils', ton: 'critique' });
    }
    const aAffecter = this.syntheseTravaux()?.synthese.parStatut['SIGNALEE'] ?? 0;
    if (aAffecter && (this.auth.a(DROITS.travauxValider) || this.auth.compte()?.profil === 'ADMIN')) {
      r.push({ nombre: aAffecter, texte: aAffecter > 1 ? 'pannes à confier à un prestataire' : 'panne à confier à un prestataire',
        lien: '/travaux', params: { vue: 'affecter' }, icone: 'outils', ton: 'attention' });
    }
    const enRetard = this.loyers()?.synthese.bauxEnRetard ?? 0;
    if (enRetard) {
      r.push({ nombre: enRetard, texte: enRetard > 1 ? 'locataires en retard de loyer' : 'locataire en retard de loyer',
        lien: '/loyers', icone: 'monnaie', ton: 'critique' });
    }
    if (aLocaliser && this.peutCreer()) {
      r.push({ nombre: aLocaliser, texte: aLocaliser > 1 ? 'bâtiments à placer sur la carte' : 'bâtiment à placer sur la carte',
        lien: '/carte', params: { volet: 'localiser' }, icone: 'cible', ton: 'critique' });
    }
    if (aValider && this.peutCreer() && this.auth.compte()?.profil !== 'AGENT') {
      r.push({ nombre: aValider, texte: aValider > 1 ? 'fiches à contrôler et valider' : 'fiche à contrôler et valider',
        lien: '/patrimoine', params: { filtre: 'a-valider' }, icone: 'coche', ton: 'attention' });
    }
    if (sansGestionnaire && this.auth.compte()?.portee === 'TOUT') {
      r.push({ nombre: sansGestionnaire, texte: sansGestionnaire > 1 ? 'bâtiments sans gestionnaire' : 'bâtiment sans gestionnaire',
        lien: '/patrimoine', params: { filtre: 'sans-gestionnaire' }, icone: 'utilisateur', ton: 'attention' });
    }
    if (sansLot && this.peutCreer()) {
      r.push({ nombre: sansLot, texte: sansLot > 1 ? 'bâtiments sans aucun lot saisi' : 'bâtiment sans aucun lot saisi',
        lien: '/patrimoine', icone: 'cle', ton: 'info' });
    }
    return r;
  });

  /** Les bâtiments qui ont le plus de lots libres. */
  protected readonly plusVacants = computed(() =>
    this.batiments().filter((b) => b.lotsVacants > 0).sort((a, b) => b.lotsVacants - a.lotsVacants || a.nom.localeCompare(b.nom, 'fr')).slice(0, 5));

  async ngOnInit(): Promise<void> {
    try {
      const [batiments, loyers, travaux] = await Promise.all([
        this.patrimoine.batiments(),
        this.voitLoyers() ? this.location.synthese().catch(() => null) : Promise.resolve(null),
        this.voitTravaux() ? this.travaux.synthese().catch(() => null) : Promise.resolve(null),
      ]);
      this.batiments.set(batiments);
      this.loyers.set(loyers);
      this.syntheseTravaux.set(travaux);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected tonOccupation(): string {
    const t = this.chiffres().taux;
    return !this.chiffres().lots ? '' : t >= 90 ? 'bon' : t >= 70 ? 'attention' : 'critique';
  }
}
