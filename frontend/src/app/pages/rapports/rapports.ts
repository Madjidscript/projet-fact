import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EtatVide } from '../../composants/etat-vide';
import { Barres, Part } from '../../composants/graphiques';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { fcfa } from '../../noyau/format';
import { Indicateurs, LigneDelai, LigneM2, LigneRecouvrement, LigneTaux, LigneVacance, RapportsService, moisIso } from '../../noyau/rapports.service';
import { Ton } from '../../noyau/statuts';

type Raccourci = '3' | '6' | '12' | 'annee' | 'libre';
type AxeOccupation = 'parCommune' | 'parGestionnaire' | 'parProprietaire' | 'parBatiment';
type AxeRecouvrement = 'parGestionnaire' | 'parBatiment';

const MOIS_COURTS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/**
 * Tableaux de bord et rapports (§ 4.8) : taux d'occupation, recouvrement, impayés par ancienneté,
 * vacance, loyer au m², délai des interventions, sur une période choisie ; exports PDF et Excel.
 * Chaque compte ne voit que son périmètre (le gestionnaire, son portefeuille).
 */
@Component({
  selector: 'fact-rapports',
  imports: [FormsModule, RouterLink, Icone, EtatVide, Barres, Compteur, Apparition],
  templateUrl: './rapports.html',
  styleUrl: './rapports.scss',
})
export class Rapports implements OnInit {
  private readonly rapports = inject(RapportsService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);

  protected readonly raccourci = signal<Raccourci>('12');
  protected readonly du = signal(moisIso(this.decale(-11)));
  protected readonly au = signal(moisIso(new Date()));
  protected readonly donnees = signal<Indicateurs | null>(null);
  protected readonly chargement = signal(true);
  protected readonly export = signal<'pdf' | 'excel' | null>(null);
  protected readonly axeOccupation = signal<AxeOccupation>('parCommune');
  protected readonly axeRecouvrement = signal<AxeRecouvrement>('parGestionnaire');
  protected readonly axeVacance = signal<'parType' | 'parQuartier'>('parType');
  protected readonly axeM2 = signal<'parQuartier' | 'parType'>('parQuartier');
  protected readonly axeDelai = signal<'parCategorie' | 'parPrestataire'>('parCategorie');
  protected readonly maintenant = moisIso(new Date());
  protected readonly f = { fcfa };

  /** Le gestionnaire voit son portefeuille ; on le lui rappelle. */
  protected readonly portee = computed(() => {
    const c = this.auth.compte();
    if (!c || c.portee === 'TOUT') return 'Tout le patrimoine géré';
    return c.portee === 'SON_PORTEFEUILLE' ? 'Votre portefeuille' : 'Vos biens';
  });

  protected readonly barresMois = computed(() => {
    const d = this.donnees();
    if (!d) return [];
    const max = Math.max(1, ...d.recouvrement.parMois.map((m) => Math.max(m.attendu, m.encaisse)));
    return d.recouvrement.parMois.map((m) => {
      const [a, mo] = m.mois.split('-').map(Number);
      return {
        ...m,
        court: MOIS_COURTS[mo - 1] + (mo === 1 || d.recouvrement.parMois.length <= 6 ? ' ' + String(a).slice(2) : ''),
        long: MOIS_COURTS[mo - 1] + ' ' + a,
        hAttendu: (m.attendu / max) * 100,
        hRegle: (m.regle / max) * 100,
        ton: this.tonTaux(m.taux),
        etiquette: m.taux === null ? '' : Math.round(m.taux) + '%',
      };
    });
  });

  protected readonly partsOccupation = computed<Part[]>(() =>
    (this.donnees()?.occupation[this.axeOccupation()] ?? []).map((l: LigneTaux) => ({
      libelle: l.libelle, valeur: l.taux ?? 0, ton: this.tonTaux(l.taux, 85, 60),
      texte: `${this.pct(l.taux)} · ${l.occupes}/${l.lots}`,
    })));

  protected readonly partsRecouvrement = computed<Part[]>(() =>
    (this.donnees()?.recouvrement[this.axeRecouvrement()] ?? []).map((l: LigneRecouvrement) => ({
      libelle: l.libelle, valeur: l.taux ?? 0, ton: this.tonTaux(l.taux), texte: this.pct(l.taux),
    })));

  protected readonly partsVacance = computed<Part[]>(() => {
    const d = this.donnees();
    if (!d) return [];
    const relocations = d.vacance[this.axeVacance()];
    const lignes: LigneVacance[] = relocations.length ? relocations : d.vacance.enCours[this.axeVacance()];
    return lignes.map((l) => ({ libelle: l.libelle, valeur: l.jours, ton: l.jours > 60 ? 'critique' : l.jours > 30 ? 'attention' : 'info',
      texte: `${l.jours} j · ${l.cas} lot${l.cas > 1 ? 's' : ''}` }));
  });

  protected readonly partsM2 = computed<Part[]>(() =>
    (this.donnees()?.loyerM2[this.axeM2()] ?? []).slice(0, 12).map((l: LigneM2) => ({
      libelle: l.libelle, valeur: l.prix, ton: 'info' as Ton, texte: fcfa(l.prix) + '/m²',
    })));

  protected readonly partsDelai = computed<Part[]>(() =>
    (this.donnees()?.interventions[this.axeDelai()] ?? []).map((l: LigneDelai) => ({
      libelle: l.libelle, valeur: l.jours, ton: l.jours > 15 ? 'critique' : l.jours > 7 ? 'attention' : 'bon',
      texte: `${String(l.jours).replace('.', ',')} j · ${l.interventions}`,
    })));

  protected readonly maxAnciennete = computed(() => Math.max(1, ...(this.donnees()?.impayes.parAnciennete ?? []).map((a) => a.montant)));

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected choisir(r: Raccourci): void {
    this.raccourci.set(r);
    if (r === 'libre') return;
    const fin = new Date();
    this.au.set(moisIso(fin));
    this.du.set(r === 'annee' ? `${fin.getFullYear()}-01` : moisIso(this.decale(-(Number(r) - 1))));
    this.charger();
  }

  protected changerMois(champ: 'du' | 'au', valeur: string): void {
    if (!/^\d{4}-\d{2}$/.test(valeur)) return;
    this.raccourci.set('libre');
    (champ === 'du' ? this.du : this.au).set(valeur);
    if (this.du() > this.au()) (champ === 'du' ? this.au : this.du).set(valeur);
    this.charger();
  }

  protected async exporter(format: 'pdf' | 'excel'): Promise<void> {
    if (this.export()) return;
    this.export.set(format);
    try {
      await this.rapports.exporter(format, this.du(), this.au());
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.export.set(null);
    }
  }

  protected pct(t: number | null | undefined): string {
    return t === null || t === undefined ? '-' : String(t).replace('.', ',') + ' %';
  }

  protected tonTaux(t: number | null, bon = 95, moyen = 85): Ton {
    return t === null ? 'neutre' : t >= bon ? 'bon' : t >= moyen ? 'attention' : 'critique';
  }

  private async charger(): Promise<void> {
    this.chargement.set(true);
    try {
      this.donnees.set(await this.rapports.indicateurs(this.du(), this.au()));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private decale(mois: number): Date {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() + mois);
    return d;
  }
}
