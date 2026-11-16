import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';

export interface Alerte {
  id: number;
  type: 'BAIL_ECHEANCE' | 'LOYER_RETARD' | 'LOT_VACANT' | 'MANDAT_ECHEANCE' | 'INTERVENTION_URGENTE' | 'SANS_GESTIONNAIRE'
    | 'DEMANDE_NOUVELLE' | 'DEMANDE_EN_ATTENTE' | 'ANNONCE_A_VALIDER' | 'AFFECTATION'
    | 'DEVIS_A_APPROUVER' | 'ACCORD_PROPRIETAIRE' | 'RELEVE_DISPONIBLE' | 'TOURNEE';
  gravite: 'INFO' | 'ATTENTION' | 'CRITIQUE';
  titre: string;
  message: string | null;
  lien: string | null;
  idBatiment: number | null;
  creeLe: string;
  lueLe: string | null;
  resolueLe: string | null;
}

export interface CompteAlertes {
  nonLues: number;
  critiques: number;
  derniere: string | null;
}

/**
 * Les alertes du compte connecté (§ 4.7). Le nombre de non lues est relu chaque minute tant
 * que la session est ouverte ; quand il augmente, `nouvelle` passe à vrai un instant et la
 * cloche oscille (§ 6.2, « Notifications »).
 */
@Injectable({ providedIn: 'root' })
export class AlertesService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  readonly compte = signal<CompteAlertes>({ nonLues: 0, critiques: 0, derniere: null });
  readonly liste = signal<Alerte[] | null>(null);
  /** Vrai pendant l'oscillation de la cloche, à l'arrivée d'une alerte. */
  readonly nouvelle = signal(false);
  /** Le panneau des alertes est ouvert. */
  readonly ouvert = signal(false);

  private minuterie: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      if (this.auth.connecte()) this.demarrer();
      else this.arreter();
    });
    inject(DestroyRef).onDestroy(() => this.arreter());
  }

  async rafraichir(): Promise<void> {
    try {
      const c = await firstValueFrom(this.http.get<CompteAlertes>('/api/alertes/compte'));
      const avant = this.compte();
      this.compte.set(c);
      if (c.derniere && c.derniere !== avant.derniere && c.nonLues > avant.nonLues && avant.derniere !== null) this.sonner();
      if (this.ouvert()) await this.charger();
    } catch {
      /* hors ligne ou session close : on réessaiera à la minute suivante */
    }
  }

  async charger(): Promise<void> {
    this.liste.set(await firstValueFrom(this.http.get<Alerte[]>('/api/alertes')));
  }

  async ouvrir(): Promise<void> {
    this.ouvert.set(true);
    this.liste.set(null);
    await this.charger();
  }

  async marquerLue(a: Alerte): Promise<void> {
    if (a.lueLe) return;
    await firstValueFrom(this.http.post(`/api/alertes/${a.id}/lue`, {}));
    const maintenant = new Date().toISOString();
    this.liste.update((l) => l?.map((x) => (x.id === a.id ? { ...x, lueLe: maintenant } : x)) ?? null);
    this.compte.update((c) => ({ ...c, nonLues: Math.max(0, c.nonLues - (a.resolueLe ? 0 : 1)),
      critiques: Math.max(0, c.critiques - (a.gravite === 'CRITIQUE' && !a.resolueLe ? 1 : 0)) }));
  }

  async toutMarquerLu(): Promise<void> {
    await firstValueFrom(this.http.post('/api/alertes/lues', {}));
    const maintenant = new Date().toISOString();
    this.liste.update((l) => l?.map((x) => ({ ...x, lueLe: x.lueLe ?? maintenant })) ?? null);
    this.compte.update((c) => ({ ...c, nonLues: 0, critiques: 0 }));
  }

  private sonner(): void {
    this.nouvelle.set(true);
    setTimeout(() => this.nouvelle.set(false), 1600);
  }

  private demarrer(): void {
    if (this.minuterie) return;
    this.rafraichir();
    this.minuterie = setInterval(() => this.rafraichir(), 60_000);
  }

  private arreter(): void {
    if (this.minuterie) clearInterval(this.minuterie);
    this.minuterie = null;
    this.compte.set({ nonLues: 0, critiques: 0, derniere: null });
    this.liste.set(null);
    this.ouvert.set(false);
  }
}
