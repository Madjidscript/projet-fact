import { Component, ElementRef, OnDestroy, ViewEncapsulation, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import * as L from 'leaflet';
import { firstValueFrom } from 'rxjs';
import { Icone } from '../../composants/icone';
import { Apparition, Compteur, animationsReduites } from '../../composants/mouvement';
import { lienWhatsApp } from '../../noyau/annonces.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { date, fcfa } from '../../noyau/format';
import { Mandat, RapportsService, moisIso } from '../../noyau/rapports.service';

interface LotEspace { numero: string; etage: number; type: string; statut: string; libelleStatut: string; loyer: number | null;
  depuis: string | null; moisRetard: number; impaye: number; }
interface BatimentEspace { id: number; reference: string; nom: string; commune: string; quartier: string | null; latitude: number | null;
  longitude: number | null; lots: number; occupes: number; vacants: number; impaye: number; validation: string; interventions: number;
  lotsDetail: LotEspace[]; }
interface InterventionEspace { id: number; reference: string; titre: string; batiment: string; numeroLot: string | null; statut: string;
  urgence: string; categorie: string; signaleLe: string; clotureLe: string | null; constat: string | null; devis: number | null;
  cout: number | null; imputation: string | null; seuil: number; accordDecision: string | null; accordMotif: string | null;
  prestataire: string | null; ouverte: boolean; aRepondre: boolean; }
interface Espace {
  proprietaire: { id: number; reference: string; nomComplet: string; email: string | null };
  batiments: BatimentEspace[];
  gestionnaires: { id: number; nom: string; fonction: string | null; telephone: string | null; email: string | null; role: string; batiments: string }[];
  loyers: { attendu: number; regle: number; impaye: number; parMois: { mois: string; attendu: number; regle: number; encaisse: number }[] };
  interventions: InterventionEspace[];
  mandats: Mandat[];
  apercu: boolean;
}

const ETAPES: Record<string, string> = {
  SIGNALEE: 'Signalée', AFFECTEE: 'Prestataire choisi', EN_COURS: 'En cours', VALIDEE: 'Devis validé', TERMINEE: 'Travaux terminés', CLOTUREE: 'Clôturée',
};
const MOIS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];

/**
 * L'espace du propriétaire (§ 4.9) : ses biens sur une carte et lot par lot, ses loyers et impayés
 * (sans données personnelles des locataires), ses travaux avec l'accord en ligne sur les devis qui
 * dépassent son seuil, ses relevés et ses documents de mandat, et le contact de son gestionnaire.
 */
@Component({
  selector: 'fact-espace',
  imports: [FormsModule, Icone, Apparition, Compteur],
  encapsulation: ViewEncapsulation.None,
  templateUrl: './espace.html',
  styleUrl: './espace.scss',
})
export class EspaceProprietaire implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);
  private readonly rapports = inject(RapportsService);
  protected readonly auth = inject(AuthService);
  private readonly hoteCarte = viewChild<ElementRef<HTMLElement>>('carte');

  /** ?proprietaire=12 : la Direction ouvre l'espace d'un propriétaire ; ?devis=34 : un devis à regarder. */
  readonly proprietaireAdresse = input<string | undefined>(undefined, { alias: 'proprietaire' });
  readonly devisAdresse = input<string | undefined>(undefined, { alias: 'devis' });

  protected readonly espace = signal<Espace | null>(null);
  protected readonly chargement = signal(true);
  protected readonly ouvert = signal<number | null>(null);
  protected readonly reponse = signal<{ id: number; accord: boolean } | null>(null);
  protected motif = '';
  protected readonly envoi = signal(false);
  protected readonly du = signal(moisIso(this.moisPrecedent()));
  protected readonly au = signal(moisIso(this.moisPrecedent()));
  protected readonly pdf = signal(false);
  protected readonly maintenant = moisIso(new Date());
  protected readonly f = { fcfa, date };
  protected readonly etapes = ETAPES;

  private carte: L.Map | null = null;
  private observateur: ResizeObserver | null = null;

  protected readonly aRepondre = computed(() => (this.espace()?.interventions ?? []).filter((i) => i.aRepondre));
  protected readonly enCours = computed(() => (this.espace()?.interventions ?? []).filter((i) => i.ouverte && !i.aRepondre));
  protected readonly terminees = computed(() => (this.espace()?.interventions ?? []).filter((i) => !i.ouverte).slice(0, 6));
  protected readonly totaux = computed(() => {
    const b = this.espace()?.batiments ?? [];
    const lots = b.reduce((n, x) => n + x.lots, 0), occupes = b.reduce((n, x) => n + x.occupes, 0);
    return { batiments: b.length, lots, occupes, taux: lots ? Math.round((occupes / lots) * 1000) / 10 : 0 };
  });
  protected readonly barres = computed(() => {
    const m = this.espace()?.loyers.parMois ?? [];
    const max = Math.max(1, ...m.map((x) => Math.max(x.attendu, x.encaisse)));
    return m.map((x) => ({ ...x, court: MOIS[Number(x.mois.slice(5, 7)) - 1], hA: (x.attendu / max) * 100, hR: (x.regle / max) * 100 }));
  });
  protected readonly prenom = computed(() => this.espace()?.proprietaire.nomComplet.replace(/^ESSAI\s+/, '') ?? '');

  constructor() {
    effect(() => {
      const p = this.proprietaireAdresse();
      untracked(() => this.charger(p ? Number(p) : null));
    });
  }

  ngOnDestroy(): void {
    this.observateur?.disconnect();
    this.carte?.remove();
  }

  protected repondre(id: number, accord: boolean): void {
    this.motif = '';
    this.reponse.set({ id, accord });
  }

  protected async confirmer(): Promise<void> {
    const r = this.reponse();
    if (!r || this.envoi()) return;
    this.envoi.set(true);
    try {
      await firstValueFrom(this.http.post(`/api/espace/devis/${r.id}`, { accord: r.accord, motif: this.motif || null }));
      this.avis.succes(r.accord ? 'Merci : votre accord est transmis à l\'agence, les travaux peuvent commencer.'
        : 'Votre refus est transmis : l\'agence vous proposera une autre solution.');
      this.reponse.set(null);
      await this.charger(this.proprietaireAdresse() ? Number(this.proprietaireAdresse()) : null);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.envoi.set(false);
    }
  }

  protected async relevePdf(): Promise<void> {
    const e = this.espace();
    if (!e || this.pdf()) return;
    this.pdf.set(true);
    try {
      await this.rapports.relevePdf(e.proprietaire.id, e.proprietaire.reference, this.du(), this.au());
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    } finally {
      this.pdf.set(false);
    }
  }

  protected async documentMandat(m: Mandat): Promise<void> {
    try {
      const blob = await firstValueFrom(this.http.get(`/api/mandats/${m.id}/document`, { responseType: 'blob' }));
      const adresse = URL.createObjectURL(blob);
      if (!window.open(adresse, '_blank')) {
        const a = document.createElement('a');
        a.href = adresse;
        a.download = (m as Mandat & { documentNom?: string }).documentNom ?? 'mandat.pdf';
        a.click();
      }
      setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected tel(t: string): string {
    return 'tel:' + t.replace(/[^\d+]/g, '');
  }

  protected whatsapp(t: string): string {
    return lienWhatsApp(t, `Bonjour, je suis ${this.espace()?.proprietaire.nomComplet}, propriétaire suivi par FACT Immobilier.`);
  }

  protected tonLot(l: LotEspace): string {
    if (l.moisRetard >= 2) return 'critique';
    if (l.moisRetard === 1) return 'attention';
    if (['OCCUPE', 'EN_PREAVIS'].includes(l.statut)) return 'bon';
    if (['VACANT', 'A_LOUER', 'A_VENDRE'].includes(l.statut)) return 'info';
    return 'neutre';
  }

  protected document(m: Mandat): string | null {
    return (m as Mandat & { documentNom?: string | null }).documentNom ?? null;
  }

  private async charger(proprietaire: number | null): Promise<void> {
    try {
      const e = await firstValueFrom(this.http.get<Espace>('/api/espace', { params: proprietaire ? { proprietaire } : {} }));
      this.espace.set(e);
      const devis = Number(this.devisAdresse());
      if (devis) setTimeout(() => document.getElementById('devis-' + devis)?.scrollIntoView({ behavior: 'smooth', block: 'center' }), 400);
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    } finally {
      this.chargement.set(false);
      setTimeout(() => this.dessinerCarte());
    }
  }

  private dessinerCarte(): void {
    const hote = this.hoteCarte()?.nativeElement;
    const b = (this.espace()?.batiments ?? []).filter((x) => x.latitude !== null && x.longitude !== null);
    if (!hote || !b.length) return;
    if (!this.carte) {
      this.carte = L.map(hote, { zoomControl: true, scrollWheelZoom: false, attributionControl: true });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap' }).addTo(this.carte);
      this.observateur = new ResizeObserver(() => this.carte?.invalidateSize());
      this.observateur.observe(hote);
    }
    const points: L.LatLngExpression[] = [];
    b.forEach((x, i) => {
      points.push([x.latitude!, x.longitude!]);
      const ton = x.impaye > 0 ? 'critique' : x.vacants > 0 ? 'attention' : 'bon';
      L.marker([x.latitude!, x.longitude!], {
        title: x.nom,
        icon: L.divIcon({ className: '', html: `<span class="es-marqueur ${ton}" style="animation-delay:${i * 80}ms"></span>`, iconSize: [26, 26], iconAnchor: [13, 13] }),
      }).on('click', () => {
        this.ouvert.set(x.id);
        document.getElementById('bat-' + x.id)?.scrollIntoView({ behavior: animationsReduites() ? 'auto' : 'smooth', block: 'center' });
      }).addTo(this.carte!);
    });
    this.carte.fitBounds(L.latLngBounds(points), { padding: [30, 30], maxZoom: 15 });
  }

  private moisPrecedent(): Date {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    return d;
  }
}
