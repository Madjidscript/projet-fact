import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Choix, OptionChoix } from '../composants/choix';
import { Accuse, EMAIL, STYLE_PIEGE, TELEPHONE } from '../composants/envoi';
import { Icone } from '../composants/icone';
import { Api, messageErreur } from '../noyau/api';
import { CommunesService } from '../noyau/communes.service';
import { METIERS, Metier, TYPES_BIEN } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

const MAX_OCTETS = 10 * 1024 * 1024;
const MAX_FICHIERS = 8;
const TYPES_ACCEPTES = ['application/pdf', 'image/jpeg', 'image/png'];

/**
 * Demande de devis ou d'estimation (§ 3.5) : le formulaire s'adapte au métier choisi
 * (construction, travaux, estimation d'un bien, gestion) et accepte plans et photos.
 */
@Component({
  selector: 'site-devis',
  imports: [FormsModule, RouterLink, Icone, Choix, Accuse, ...MOUVEMENT],
  templateUrl: './devis.html',
  styleUrl: './devis.scss',
})
export class Devis implements OnInit {
  private readonly api = inject(Api);
  protected readonly communes = inject(CommunesService);
  /** ?metier=CONSTRUCTION : le métier est présélectionné depuis sa page. */
  readonly metierAdresse = input<string | undefined>(undefined, { alias: 'metier' });

  protected readonly metiers = METIERS;
  protected readonly metier = signal<Metier['code']>('CONSTRUCTION');
  protected readonly estimation = computed(() => this.metier() === 'TRANSACTION');
  protected readonly projets = ['Maison individuelle', 'Immeuble ou résidence', 'Extension ou surélévation', 'Clôture, dépendance', 'Autre projet'];
  protected readonly natures = ['Plomberie', 'Électricité', 'Peinture', 'Étanchéité, toiture', 'Menuiserie', 'Carrelage', 'Assainissement', 'Rénovation complète', 'Autre'];
  protected readonly typesBien: OptionChoix[] = TYPES_BIEN.map((t) => ({ valeur: t, libelle: t }));
  protected readonly optionsCommunes = computed<OptionChoix[]>(() => this.communes.communes().map((c) => ({ valeur: c.libelle, libelle: c.libelle })));

  protected s = this.vide();
  protected readonly fichiers = signal<File[]>([]);
  protected readonly poids = computed(() => this.fichiers().reduce((t, f) => t + f.size, 0));
  protected readonly erreurFichiers = signal<string | null>(null);
  protected readonly erreurs = signal<string[]>([]);
  protected readonly erreurApi = signal<string | null>(null);
  protected readonly envoi = signal(false);
  protected readonly accuse = signal<{ reference: string; message: string } | null>(null);
  protected readonly stylePiege = STYLE_PIEGE;

  constructor() {
    effect(() => {
      const m = this.metierAdresse();
      untracked(() => {
        if (m && METIERS.some((x) => x.code === m)) this.metier.set(m as Metier['code']);
      });
    });
  }

  ngOnInit(): void {
    this.communes.assurer();
  }

  protected libelleMetier(m: Metier): string {
    return m.code === 'TRANSACTION' ? 'Estimation d’un bien' : m.titre;
  }

  protected taille(octets: number): string {
    return octets >= 1024 * 1024 ? (octets / 1024 / 1024).toFixed(1).replace('.', ',') + ' Mo' : Math.max(1, Math.round(octets / 1024)) + ' Ko';
  }

  protected ajouter(e: Event): void {
    const champ = e.target as HTMLInputElement;
    const nouveaux = Array.from(champ.files ?? []);
    champ.value = '';
    this.erreurFichiers.set(null);
    const refuses = nouveaux.filter((f) => !TYPES_ACCEPTES.includes(f.type));
    const acceptes = nouveaux.filter((f) => TYPES_ACCEPTES.includes(f.type));
    const liste = [...this.fichiers(), ...acceptes];
    if (refuses.length) this.erreurFichiers.set(`${refuses.map((f) => f.name).join(', ')} : seuls les PDF, JPEG et PNG sont acceptés.`);
    if (liste.length > MAX_FICHIERS) {
      this.erreurFichiers.set(`${MAX_FICHIERS} fichiers au plus.`);
      return;
    }
    if (liste.reduce((t, f) => t + f.size, 0) > MAX_OCTETS) {
      this.erreurFichiers.set('Les pièces jointes dépassent 10 Mo au total : retirez-en ou réduisez-les.');
      return;
    }
    this.fichiers.set(liste);
  }

  protected retirer(f: File): void {
    this.fichiers.update((l) => l.filter((x) => x !== f));
    this.erreurFichiers.set(null);
  }

  protected async envoyer(): Promise<void> {
    const e: string[] = [];
    const m = this.metier();
    if (m === 'CONSTRUCTION' && !this.s.projet) e.push('projet');
    if (m === 'TRAVAUX' && !this.s.nature) e.push('nature');
    if (m === 'TRANSACTION' && !this.s.typeBien) e.push('typeBien');
    if (!this.s.commune) e.push('commune');
    if (this.s.nom.trim().length < 2) e.push('nom');
    if (!TELEPHONE.test(this.s.telephone.trim())) e.push('telephone');
    if (this.s.email.trim() && !EMAIL.test(this.s.email.trim())) e.push('email');
    if (!this.s.accord) e.push('accord');
    this.erreurs.set([]);
    this.erreurApi.set(null);
    if (e.length) {
      setTimeout(() => this.erreurs.set(e));
      return;
    }
    const details: Record<string, string | number | null> = { metier: m, commune: this.s.commune, localisation: this.s.localisation.trim() || null };
    if (m === 'CONSTRUCTION') Object.assign(details, { projet: this.s.projet, terrain: this.s.terrain, surface: this.s.surface, budget: this.s.budget });
    if (m === 'TRAVAUX') Object.assign(details, { natureTravaux: this.s.nature, budget: this.s.budget });
    if (m === 'TRANSACTION') Object.assign(details, { typeBien: this.s.typeBien, surface: this.s.surface, pieces: this.s.pieces });
    if (m === 'GESTION') Object.assign(details, { typeBien: this.s.typeBien, lots: this.s.lots });
    this.envoi.set(true);
    try {
      this.accuse.set(await this.api.demande({
        type: m === 'TRANSACTION' ? 'ESTIMATION' : 'DEVIS', nom: this.s.nom.trim(), telephone: this.s.telephone.trim(),
        email: this.s.email.trim() || null, message: this.s.message.trim() || null, details, consentement: true, site: this.s.site,
      }, this.fichiers()));
    } catch (err) {
      this.erreurApi.set(messageErreur(err));
    } finally {
      this.envoi.set(false);
    }
  }

  protected nouvelle(): void {
    this.accuse.set(null);
    this.fichiers.set([]);
    this.s = this.vide();
  }

  private vide() {
    return { projet: '', terrain: 'Oui', nature: '', typeBien: null as string | null, commune: null as string | null, localisation: '',
      surface: null as number | null, budget: null as number | null, pieces: null as number | null, lots: null as number | null,
      nom: '', telephone: '', email: '', message: '', accord: false, site: '' };
  }
}
