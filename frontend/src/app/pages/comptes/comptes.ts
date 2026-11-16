import { HttpClient } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Confirmation } from '../../composants/confirmation';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { dateHeure } from '../../noyau/format';
import { CompteAdmin, DROITS, DroitLibelle, Profil } from '../../noyau/modeles';

interface Reponse {
  comptes: CompteAdmin[];
  profils: Profil[];
  droits: DroitLibelle[];
}

interface Saisie {
  identifiant: string;
  nom: string;
  fonction: string;
  email: string;
  telephone: string;
  profil: string;
  droits: string[];
  actif: boolean;
}

const VIDE: Saisie = { identifiant: '', nom: '', fonction: '', email: '', telephone: '', profil: '', droits: [], actif: true };
const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Gestion des comptes et des droits par profil (SIG-23). La Direction consulte ; seul
 * l'administrateur crée, modifie, désactive et réinitialise (§ 2.2). Un compte ne se
 * supprime pas : il se désactive et reste consultable (§ 8).
 */
@Component({
  selector: 'fact-comptes',
  imports: [FormsModule, Icone, PanneauLateral, Confirmation, EtatVide, Apparition],
  templateUrl: './comptes.html',
  styleUrl: './comptes.scss',
})
export class Comptes implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);

  protected readonly chargement = signal(true);
  protected readonly comptes = signal<CompteAdmin[]>([]);
  protected readonly profils = signal<Profil[]>([]);
  protected readonly droits = signal<DroitLibelle[]>([]);

  protected readonly recherche = signal('');
  protected readonly filtreProfil = signal('');
  protected readonly peutEcrire = computed(() => this.auth.a(DROITS.comptes));

  protected readonly visibles = computed(() => {
    const texte = sansAccent(this.recherche().trim());
    const profil = this.filtreProfil();
    return this.comptes().filter((c) =>
      (!profil || c.profil === profil) &&
      (!texte || sansAccent(`${c.nom} ${c.identifiant} ${c.fonction ?? ''} ${c.email ?? ''}`).includes(texte)));
  });
  protected readonly actifs = computed(() => this.comptes().filter((c) => c.actif).length);

  // ----------------------------------------------------------------- formulaire
  protected readonly ouvert = signal(false);
  protected readonly creation = signal(true);
  protected readonly saisie = signal<Saisie>({ ...VIDE });
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  /** L'identifiant du compte qui vient d'être enregistré : sa carte clignote. */
  protected readonly modifie = signal<string | null>(null);

  protected readonly profilChoisi = computed(() => this.profils().find((p) => p.code === this.saisie().profil));
  /** Les droits que le profil ne porte pas déjà : seuls ceux-là peuvent être accordés en plus. */
  protected readonly droitsEnPlus = computed(() => {
    const portes = this.profilChoisi()?.droits ?? [];
    return this.droits().filter((d) => !portes.includes(d.code));
  });

  // -------------------------------------------------- confirmations et provisoire
  protected readonly aConfirmer = signal<{ genre: 'reinitialiser' | 'basculer'; compte: CompteAdmin } | null>(null);
  protected readonly provisoire = signal<{ identifiant: string; nom: string; motDePasse: string } | null>(null);
  protected readonly copie = signal(false);

  protected readonly f = dateHeure;

  async ngOnInit(): Promise<void> {
    try {
      const r = await firstValueFrom(this.http.get<Reponse>('/api/comptes'));
      this.comptes.set(r.comptes);
      this.profils.set(r.profils);
      this.droits.set(r.droits);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected libelleDroit(code: string): string {
    return this.droits().find((d) => d.code === code)?.libelle ?? code;
  }

  protected libellePortee(portee: string | undefined): string {
    return portee === 'TOUT' ? 'Tout le patrimoine' : portee === 'SON_PORTEFEUILLE' ? 'Son portefeuille' : 'Ses biens';
  }

  protected initiales(nom: string): string {
    return nom.split(/\s+/).filter(Boolean).slice(0, 2).map((m) => m[0]!.toUpperCase()).join('');
  }

  // ------------------------------------------------------------------- formulaire
  protected nouveau(): void {
    this.saisie.set({ ...VIDE, droits: [] });
    this.creation.set(true);
    this.erreur.set(null);
    this.ouvert.set(true);
  }

  protected modifier(c: CompteAdmin): void {
    if (!this.peutEcrire()) return;
    this.saisie.set({
      identifiant: c.identifiant, nom: c.nom, fonction: c.fonction ?? '', email: c.email ?? '',
      telephone: c.telephone ?? '', profil: c.profil, droits: [...c.droits], actif: c.actif,
    });
    this.creation.set(false);
    this.erreur.set(null);
    this.ouvert.set(true);
  }

  protected champ<K extends keyof Saisie>(cle: K, valeur: Saisie[K]): void {
    this.saisie.update((s) => ({ ...s, [cle]: valeur }));
  }

  /** Changer de profil retire les droits en plus que le nouveau profil porte déjà. */
  protected choisirProfil(code: string): void {
    const portes = this.profils().find((p) => p.code === code)?.droits ?? [];
    this.saisie.update((s) => ({ ...s, profil: code, droits: s.droits.filter((d) => !portes.includes(d)) }));
  }

  protected basculerDroit(code: string): void {
    this.saisie.update((s) => ({
      ...s, droits: s.droits.includes(code) ? s.droits.filter((d) => d !== code) : [...s.droits, code],
    }));
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const r = await firstValueFrom(this.http.post<{ compte: CompteAdmin; motDePasseProvisoire?: string }>('/api/comptes', this.saisie()));
      this.poser(r.compte);
      this.ouvert.set(false);
      if (r.motDePasseProvisoire) {
        this.montrerProvisoire(r.compte, r.motDePasseProvisoire);
      } else {
        this.avis.succes('Compte enregistré.');
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  // ---------------------------------------------------------------- confirmations
  protected async confirmer(): Promise<void> {
    const demande = this.aConfirmer();
    if (!demande) return;
    this.aConfirmer.set(null);
    const c = demande.compte;
    try {
      if (demande.genre === 'reinitialiser') {
        const r = await firstValueFrom(this.http.post<{ motDePasseProvisoire: string }>(`/api/comptes/${encodeURIComponent(c.identifiant)}/reinitialiser`, {}));
        this.poser({ ...c, doitChanger: true });
        this.montrerProvisoire(c, r.motDePasseProvisoire);
      } else {
        const r = await firstValueFrom(this.http.post<{ compte: CompteAdmin }>('/api/comptes', {
          identifiant: c.identifiant, nom: c.nom, fonction: c.fonction, email: c.email, telephone: c.telephone,
          profil: c.profil, droits: c.droits, actif: !c.actif,
        }));
        this.poser(r.compte);
        this.avis.succes(r.compte.actif ? 'Compte réactivé.' : 'Compte désactivé : ses sessions sont fermées.');
      }
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected async copier(): Promise<void> {
    const p = this.provisoire();
    if (!p) return;
    try {
      await navigator.clipboard.writeText(p.motDePasse);
      this.copie.set(true);
    } catch {
      this.avis.info('Copie impossible : notez le mot de passe à la main.');
    }
  }

  private montrerProvisoire(c: CompteAdmin, motDePasse: string): void {
    this.copie.set(false);
    this.provisoire.set({ identifiant: c.identifiant, nom: c.nom, motDePasse });
  }

  /** Pose le compte enregistré dans la liste, et le fait clignoter. */
  private poser(c: CompteAdmin): void {
    this.comptes.update((l) => (l.some((x) => x.identifiant === c.identifiant)
      ? l.map((x) => (x.identifiant === c.identifiant ? c : x)) : [...l, c]));
    this.modifie.set(null);
    setTimeout(() => this.modifie.set(c.identifiant));
  }
}
