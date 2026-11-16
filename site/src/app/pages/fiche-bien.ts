import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, TitleStrategy } from '@angular/router';
import { CarteBiens } from '../composants/carte-biens';
import { Coeur } from '../composants/coeur';
import { Accuse, EMAIL, STYLE_PIEGE, TELEPHONE } from '../composants/envoi';
import { Galerie } from '../composants/galerie';
import { Icone } from '../composants/icone';
import { VignetteBien } from '../composants/vignette-bien';
import { Api, FicheBien as Fiche, Vignette, messageErreur } from '../noyau/api';
import { CommunesService } from '../noyau/communes.service';
import { AGENCE, montant, prix } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';
import { partager } from '../noyau/partage';
import { Referencement } from '../noyau/referencement';

const JOURS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'];
const MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];

/** Les dix prochains jours ouvrés à partir de demain (§ 3.3, demande de visite). */
function joursOuvres(): { valeur: string; libelle: string }[] {
  const r: { valeur: string; libelle: string }[] = [];
  const d = new Date();
  while (r.length < 10) {
    d.setDate(d.getDate() + 1);
    if (d.getDay() === 0 || d.getDay() === 6) continue;
    const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    r.push({ valeur: iso, libelle: `${JOURS[d.getDay()]} ${d.getDate()} ${MOIS[d.getMonth()]}` });
  }
  return r;
}

/**
 * Fiche d'un bien (§ 3.3) : galerie, bloc prix (charges, caution et avance en mois et en FCFA),
 * caractéristiques, équipements, description, carte, demande de visite, biens similaires,
 * favori et partage.
 */
@Component({
  selector: 'site-fiche-bien',
  imports: [FormsModule, RouterLink, Icone, Galerie, CarteBiens, Coeur, VignetteBien, Accuse, ...MOUVEMENT],
  templateUrl: './fiche-bien.html',
  styleUrl: './fiche-bien.scss',
})
export class FicheBien {
  private readonly api = inject(Api);
  protected readonly ref = inject(CommunesService);
  private static readonly ICONES: Record<string, string> = {
    MEUBLE: 'meuble', PARKING: 'parking', GARDIENNAGE: 'gardien', GROUPE: 'groupe', PISCINE: 'piscine', ASCENSEUR: 'ascenseur',
    CLIMATISATION: 'clim', EAU_CHAUDE: 'eau-chaude', BALCON: 'balcon', COUR: 'cour',
  };

  protected iconeEquipement(code: string): string {
    return FicheBien.ICONES[code] ?? 'coche';
  }
  private readonly referencement = inject(TitleStrategy) as Referencement;
  readonly slug = input.required<string>();
  protected readonly agence = AGENCE;
  protected readonly etat = signal<'chargement' | 'pret' | 'absent' | 'erreur'>('chargement');
  protected readonly bien = signal<Fiche | null>(null);
  protected readonly similaires = signal<Vignette[]>([]);
  protected readonly avis = signal<string | null>(null);
  protected readonly f = { montant };

  protected readonly texte = computed(() => (this.bien() ? prix(this.bien()!) : ''));
  protected readonly unBien = computed(() => (this.bien() ? [this.bien()!] : []));
  protected readonly placee = computed(() => this.bien()?.latitude !== null && this.bien()?.latitude !== undefined);
  protected readonly messageWhatsapp = computed(() =>
    encodeURIComponent(`Bonjour, je suis intéressé(e) par « ${this.bien()?.titre ?? ''} » (${this.bien()?.reference ?? ''}). Est-il possible de le visiter ?`));

  // Demande de visite
  protected readonly jours = joursOuvres();
  protected readonly creneaux = ['Matin (8 h – 12 h)', 'Après-midi (14 h – 17 h)', 'Fin de journée (17 h – 19 h)'];
  protected v = this.visiteVide();
  protected readonly erreurs = signal<string[]>([]);
  protected readonly envoi = signal(false);
  protected readonly erreurApi = signal<string | null>(null);
  protected readonly accuse = signal<{ reference: string; message: string } | null>(null);
  protected readonly stylePiege = STYLE_PIEGE;

  constructor() {
    this.ref.assurer();
    effect(() => {
      const slug = this.slug();
      untracked(() => this.charger(slug));
    });
  }

  protected async partager(): Promise<void> {
    const b = this.bien();
    if (!b) return;
    this.avis.set(await partager(b.titre, `${b.titre} — ${prix(b)}`));
    if (this.avis()) setTimeout(() => this.avis.set(null), 5000);
  }

  protected async demanderVisite(): Promise<void> {
    const e: string[] = [];
    if (this.v.nom.trim().length < 2) e.push('nom');
    if (!TELEPHONE.test(this.v.telephone.trim())) e.push('telephone');
    if (this.v.email.trim() && !EMAIL.test(this.v.email.trim())) e.push('email');
    if (!this.v.date) e.push('date');
    if (!this.v.creneau) e.push('creneau');
    if (!this.v.accord) e.push('accord');
    this.erreurs.set([]);
    this.erreurApi.set(null);
    if (e.length) {
      setTimeout(() => this.erreurs.set(e));
      return;
    }
    this.envoi.set(true);
    try {
      const r = await this.api.demande({
        type: 'VISITE', slug: this.slug(), nom: this.v.nom.trim(), telephone: this.v.telephone.trim(),
        email: this.v.email.trim() || null, dateSouhaitee: this.v.date, creneau: this.v.creneau,
        message: this.v.message.trim() || null, consentement: true, site: this.v.site,
      });
      this.accuse.set(r);
    } catch (err) {
      this.erreurApi.set(messageErreur(err));
    } finally {
      this.envoi.set(false);
    }
  }

  protected nouvelleDemande(): void {
    this.accuse.set(null);
    this.v = this.visiteVide();
  }

  private visiteVide() {
    return { nom: '', telephone: '', email: '', date: '', creneau: '', message: '', accord: false, site: '' };
  }

  private async charger(slug: string): Promise<void> {
    this.etat.set('chargement');
    this.accuse.set(null);
    try {
      const r = await this.api.bien(slug);
      this.bien.set(r.bien);
      this.similaires.set(r.similaires.slice(0, 3));
      this.etat.set('pret');
      const b = r.bien;
      this.referencement.poser(`${b.titre} — ${prix(b)} — FACT Immobilier`,
        `${b.libelleType} ${b.operation === 'LOCATION' ? 'à louer' : 'à vendre'} à ${b.quartier ? b.quartier + ', ' : ''}${b.commune} : ${prix(b)}. ${(b.description ?? '').slice(0, 140)}`);
    } catch (e) {
      const absent = (e as { status?: number }).status === 404;
      this.etat.set(absent ? 'absent' : 'erreur');
      this.bien.set(null);
      this.referencement.poser(absent ? 'Bien plus disponible — FACT Immobilier' : 'FACT Immobilier');
    }
  }
}
