import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Compte, Session } from './modeles';

const CLE = 'fact-immobilier.session';
/** « La session expire après une période d'inactivité » (§ 8) : trente minutes sans geste. */
const INACTIVITE_MS = 30 * 60 * 1000;

/**
 * La session : le jeton est gardé le temps de l'onglet (sessionStorage), jamais au-delà.
 * Elle se ferme d'elle-même après trente minutes sans activité, ou à l'échéance du jeton.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly compte = signal<Compte | null>(null);
  readonly connecte = computed(() => this.compte() !== null);
  /** Le motif de la dernière fermeture de session, affiché sur la page de connexion. */
  readonly motifFermeture = signal<string | null>(null);

  private jetonCourant: string | null = null;
  private expiration = 0;
  private dernierGeste = Date.now();
  private veille: ReturnType<typeof setInterval> | null = null;

  constructor() {
    try {
      const brut = sessionStorage.getItem(CLE);
      if (brut) {
        const s = JSON.parse(brut) as Session;
        if (new Date(s.expiration) > new Date()) {
          this.jetonCourant = s.jeton;
          this.expiration = new Date(s.expiration).getTime();
          this.compte.set(s.compte);
          this.veiller();
        }
      }
    } catch {
      /* stockage indisponible : la session reste en mémoire */
    }
    for (const geste of ['pointerdown', 'keydown', 'scroll']) {
      window.addEventListener(geste, () => (this.dernierGeste = Date.now()), { passive: true, capture: true });
    }
  }

  jeton(): string | null {
    return this.jetonCourant;
  }

  /** Vrai si le compte porte ce droit (celui de son profil, ou accordé en plus). */
  a(droit: string): boolean {
    return this.compte()?.droits.includes(droit) ?? false;
  }

  async connexion(identifiant: string, motDePasse: string): Promise<Compte> {
    const s = await firstValueFrom(this.http.post<Session>('/api/auth/connexion', { identifiant, motDePasse }));
    this.ouvrir(s);
    return s.compte;
  }

  async changerMotDePasse(actuel: string, nouveau: string): Promise<void> {
    const s = await firstValueFrom(this.http.post<Session>('/api/auth/mot-de-passe', { actuel, nouveau }));
    this.ouvrir(s);
  }

  deconnexion(motif: string | null = null): void {
    this.jetonCourant = null;
    this.compte.set(null);
    this.motifFermeture.set(motif);
    if (this.veille) clearInterval(this.veille);
    this.veille = null;
    try {
      sessionStorage.removeItem(CLE);
    } catch {
      /* rien */
    }
    this.router.navigateByUrl('/connexion');
  }

  /** Première page d'un compte. Le tableau de bord de chaque profil la remplacera à l'étape 11. */
  /** Vrai pour un propriétaire (mandant) : il n'a que son espace et la carte de ses biens. */
  estProprietaire(): boolean {
    return this.compte()?.profil === 'PROPRIETAIRE';
  }

  accueil(): string {
    const c = this.compte();
    if (!c) return '/connexion';
    if (c.doitChanger) return '/mot-de-passe';
    if (c.profil === 'AGENT') return '/terrain';
    return c.profil === 'PROPRIETAIRE' ? '/espace' : '/';
  }

  private ouvrir(s: Session): void {
    this.jetonCourant = s.jeton;
    this.expiration = new Date(s.expiration).getTime();
    this.dernierGeste = Date.now();
    this.motifFermeture.set(null);
    this.compte.set(s.compte);
    try {
      sessionStorage.setItem(CLE, JSON.stringify(s));
    } catch {
      /* rien */
    }
    this.veiller();
  }

  /** Vérifie chaque demi-minute que la session n'est ni échue ni laissée sans activité. */
  private veiller(): void {
    if (this.veille) clearInterval(this.veille);
    this.veille = setInterval(() => {
      if (!this.jetonCourant) return;
      if (Date.now() >= this.expiration) {
        this.deconnexion('Votre session est arrivée à son terme. Reconnectez-vous.');
      } else if (Date.now() - this.dernierGeste >= INACTIVITE_MS) {
        this.deconnexion("Session fermée après trente minutes d'inactivité.");
      }
    }, 30_000);
  }
}
