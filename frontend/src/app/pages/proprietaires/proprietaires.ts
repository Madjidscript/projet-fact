import { HttpClient } from '@angular/common/http';
import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Confirmation } from '../../composants/confirmation';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { Batiment, DROITS, Proprietaire } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';
import type { Mandat } from '../../noyau/rapports.service';
import { Mandats } from './mandats';

interface CompteLibre {
  id: number;
  nom: string;
  identifiant: string;
  idProprietaire: number | null;
}

interface Saisie {
  id: number | null;
  typePersonne: string;
  nom: string;
  prenoms: string;
  telephone: string;
  telephone2: string;
  email: string;
  adresse: string;
  pieceType: string;
  pieceNumero: string;
  reversementMode: string | null;
  reversementCoordonnees: string;
  idCompte: number | null;
  notes: string;
}

const VIDE: Saisie = {
  id: null, typePersonne: 'PHYSIQUE', nom: '', prenoms: '', telephone: '', telephone2: '', email: '', adresse: '', pieceType: '',
  pieceNumero: '', reversementMode: null, reversementCoordonnees: '', idCompte: null, notes: '',
};
const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Les propriétaires mandants (§ 7.1). Les coordonnées ne sont visibles que des profils qui
 * gèrent le patrimoine (§ 8) ; un compte propriétaire ne voit que sa propre fiche. Rattacher
 * un compte à une fiche ouvre l'espace propriétaire (étape 12) : réservé à la Direction.
 */
@Component({
  selector: 'fact-proprietaires',
  imports: [FormsModule, RouterLink, Icone, EtatVide, PanneauLateral, Confirmation, Apparition, Choix, OptionsPipe, Mandats],
  templateUrl: './proprietaires.html',
  styleUrl: '../patrimoine/patrimoine.scss',
  styles: [`
    .pro { display: grid; grid-template-columns: auto 1fr; gap: 6px 14px; align-items: center; }
    .avatar { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 50%; background: var(--ocre-clair);
              color: var(--ocre-texte); font: 700 15px var(--titre); }
    .pro .bas { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 13.5px; color: var(--encre-2); }
    .pro .bas span { display: inline-flex; align-items: center; gap: 5px; }
    .espace-en-ligne { display: grid; gap: 10px; margin-top: 16px; padding: 14px; border-radius: var(--rayon-2);
                       background: linear-gradient(135deg, var(--nuit-clair), var(--surface) 80%); border: 1px solid var(--filet); }
    .espace-en-ligne > div:first-child { display: grid; gap: 2px; }
    .espace-en-ligne small { color: var(--encre-2); font-size: 13px; }
    .espace-en-ligne .boutons { display: flex; flex-wrap: wrap; gap: 6px; }
    .acces { display: grid; gap: 8px; padding: 12px; border-radius: var(--rayon-2); background: var(--bon-clair); color: var(--bon-texte);
             animation: montee var(--standard) var(--courbe) both; }
    .acces p { margin: 0; font-size: 13.5px; }
    .acces dl { margin: 0; display: grid; gap: 4px; }
    .acces dl div { display: flex; flex-wrap: wrap; gap: 4px 10px; }
    .acces dt { font-size: 12.5px; min-width: 150px; }
    .acces dd { margin: 0; font-weight: 700; user-select: all; }
    .liste-bat { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .liste-bat a { display: flex; justify-content: space-between; gap: 10px; padding: 10px 12px; border-radius: var(--rayon-2);
                   border: 1px solid var(--filet); color: var(--encre); transition: border-color var(--courte) var(--courbe); }
    .liste-bat a:hover { border-color: var(--marque); text-decoration: none; }
    .liste-bat small { color: var(--discret); }
    .formulaire { display: grid; gap: 14px; }
    .duo { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    fieldset { margin: 0; padding: 0; border: 0; display: grid; gap: 12px; }
    legend { margin-bottom: 10px; font: 600 13px var(--titre); letter-spacing: .04em; text-transform: uppercase; color: var(--discret); }
    .message { display: flex; gap: 9px; margin: 0; padding: 11px 13px; border-radius: var(--rayon-2); background: var(--critique-clair);
               color: var(--critique-texte); font-size: 14px; }
    .pied { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
    .pied .gauche { margin-right: auto; }
    .modifie { animation: surbrillance 1.4s var(--courbe); }
    @keyframes surbrillance { 0%, 40% { background: var(--ocre-clair); border-color: var(--ocre); } }
  `],
})
export class Proprietaires implements OnInit {
  protected readonly opt = OPTIONS;
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly patrimoine = inject(PatrimoineService);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly ref = inject(ReferentielService);

  /** Paramètres de l'adresse : ?nouveau=1 ouvre la création, ?fiche=12 ouvre une fiche. */
  readonly nouveau = input<string | undefined>();
  readonly fiche = input<string | undefined>();

  protected readonly chargement = signal(true);
  protected readonly proprietaires = signal<Proprietaire[]>([]);
  protected readonly recherche = signal('');
  protected readonly archives = signal(false);

  protected readonly peutEcrire = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  protected readonly direction = computed(() => ['DIRECTION', 'ADMIN'].includes(this.auth.compte()?.profil ?? ''));

  protected readonly visibles = computed(() => {
    const t = sansAccent(this.recherche().trim());
    return this.proprietaires().filter((p) => !t || sansAccent(`${p.nomComplet} ${p.reference} ${p.telephone ?? ''} ${p.email ?? ''}`).includes(t));
  });

  // ------------------------------------------------------------------ panneau
  protected readonly ouvert = signal<'fiche' | 'formulaire' | null>(null);
  protected readonly courant = signal<Proprietaire | null>(null);
  protected readonly batimentsCourant = signal<Batiment[]>([]);
  protected readonly mandatsCourant = signal<Mandat[]>([]);
  protected s: Saisie = { ...VIDE };
  protected readonly comptes = signal<CompteLibre[]>([]);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly modifie = signal<number | null>(null);
  protected readonly aArchiver = signal<Proprietaire | null>(null);
  protected readonly acces = signal<{ email: string; identifiant: string; motDePasseProvisoire: string } | null>(null);

  constructor() {
    effect(() => {
      const nouveau = this.nouveau(), fiche = this.fiche();
      untracked(() => {
        if (nouveau && this.peutEcrire()) this.creer();
        else if (fiche) this.ouvrirFiche(Number(fiche));
      });
    });
  }

  async ngOnInit(): Promise<void> {
    await Promise.all([this.charger(), this.ref.assurer().catch(() => undefined)]);
  }

  protected async basculerArchives(): Promise<void> {
    this.archives.update((a) => !a);
    await this.charger();
  }

  protected initiales(p: Proprietaire): string {
    return p.nomComplet.split(/\s+/).filter(Boolean).slice(0, 2).map((m) => m[0]!.toUpperCase()).join('');
  }

  protected libelle(domaine: string, code: string | null | undefined): string {
    return this.ref.libelle(domaine, code);
  }

  // ------------------------------------------------------------------ fiche
  /** Ouvre l'espace en ligne du propriétaire : son compte, à son e-mail, au mot de passe provisoire. */
  protected async ouvrirEspace(p: Proprietaire): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    try {
      const a = await firstValueFrom(this.http.post<{ email: string; identifiant: string; motDePasseProvisoire: string }>(
        `/api/proprietaires/${p.id}/espace`, {}));
      await this.ouvrirFiche(p.id);
      this.acces.set(a);
      this.avis.succes(`L'espace de ${p.nomComplet} est ouvert.`);
      await this.charger();
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async copierAcces(a: { email: string; motDePasseProvisoire: string }): Promise<void> {
    const texte = `Bonjour, votre espace propriétaire FACT Immobilier est ouvert : ${location.origin}/connexion\n`
      + `Identifiant : ${a.email}\nMot de passe provisoire : ${a.motDePasseProvisoire} (à changer à la première connexion).`;
    try {
      await navigator.clipboard.writeText(texte);
      this.avis.succes('Message copié : collez-le dans WhatsApp ou un e-mail.');
    } catch {
      this.avis.erreur('La copie a échoué : recopiez les informations affichées.');
    }
  }

  protected async ouvrirFiche(id: number): Promise<void> {
    if (this.courant()?.id !== id) this.acces.set(null);
    this.erreur.set(null);
    try {
      const r = await this.patrimoine.proprietaire(id);
      this.courant.set(r.proprietaire);
      this.batimentsCourant.set(r.batiments);
      this.mandatsCourant.set(r.mandats ?? []);
      this.ouvert.set('fiche');
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected fermer(): void {
    this.ouvert.set(null);
    // Une fiche ouverte depuis l'adresse ne se rouvre pas au retour sur la page.
    if (this.nouveau() || this.fiche()) this.router.navigate([], { queryParams: {} });
  }

  // ------------------------------------------------------------- formulaire
  protected creer(): void {
    this.s = { ...VIDE };
    this.preparerFormulaire();
  }

  protected modifier(): void {
    const p = this.courant();
    if (!p) return;
    this.s = {
      id: p.id, typePersonne: p.typePersonne, nom: p.nom, prenoms: p.prenoms ?? '', telephone: p.telephone ?? '',
      telephone2: p.telephone2 ?? '', email: p.email ?? '', adresse: p.adresse ?? '', pieceType: p.pieceType ?? '',
      pieceNumero: p.pieceNumero ?? '', reversementMode: p.reversementMode ?? null,
      reversementCoordonnees: p.reversementCoordonnees ?? '', idCompte: p.idCompte, notes: p.notes ?? '',
    };
    this.preparerFormulaire();
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const p = await this.patrimoine.enregistrerProprietaire(this.s);
      const nouveau = this.s.id === null;
      this.proprietaires.update((l) => (nouveau ? [...l, p] : l.map((x) => (x.id === p.id ? p : x)))
        .sort((a, b) => a.nomComplet.localeCompare(b.nomComplet, 'fr')));
      this.courant.set(p);
      this.ouvert.set('fiche');
      this.marquer(p.id);
      this.avis.succes(nouveau ? 'Propriétaire créé.' : 'Propriétaire enregistré.');
      if (nouveau) {
        this.batimentsCourant.set([]);
        this.mandatsCourant.set([]);
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async archiver(): Promise<void> {
    const p = this.aArchiver();
    this.aArchiver.set(null);
    if (!p) return;
    try {
      const r = await this.patrimoine.archiverProprietaire(p.id, !p.archiveLe);
      this.avis.succes(r.archiveLe ? 'Propriétaire archivé : sa fiche reste consultable.' : 'Propriétaire restauré.');
      this.ouvert.set(null);
      await this.charger();
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  private async preparerFormulaire(): Promise<void> {
    this.erreur.set(null);
    this.ouvert.set('formulaire');
    if (this.direction() && !this.comptes().length) {
      try {
        this.comptes.set(await firstValueFrom(this.http.get<CompteLibre[]>('/api/proprietaires/comptes')));
      } catch {
        /* la liste des comptes n'est qu'une commodité */
      }
    }
  }

  private async charger(): Promise<void> {
    this.chargement.set(true);
    try {
      this.proprietaires.set(await this.patrimoine.proprietaires(this.archives()));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private marquer(id: number): void {
    this.modifie.set(null);
    setTimeout(() => this.modifie.set(id));
  }
}
