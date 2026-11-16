import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Commune, Quartier, Referentiel, Sante, Valeur } from './modeles';

/**
 * Le référentiel (communes, quartiers, listes de valeurs, seuils) et l'état de la
 * plateforme. Chargé une fois au démarrage et gardé en mémoire : ces données changent
 * rarement et servent partout — moteur de recherche du site, formulaires, légendes.
 */
@Injectable({ providedIn: 'root' })
export class ReferentielService {
  private readonly http = inject(HttpClient);

  readonly referentiel = signal<Referentiel | null>(null);
  readonly sante = signal<Sante | null>(null);
  readonly erreur = signal<string | null>(null);

  readonly charge = computed(() => this.referentiel() !== null);
  readonly communes = computed<Commune[]>(() => this.referentiel()?.communes ?? []);

  private enCours: Promise<void> | null = null;

  /** Charge le référentiel s'il ne l'est pas encore ; un seul appel même si plusieurs écrans le demandent. */
  assurer(): Promise<void> {
    if (this.referentiel()) return Promise.resolve();
    this.enCours ??= this.charger().catch((e) => {
      this.enCours = null;
      throw e;
    });
    return this.enCours;
  }

  /** Le point de centrage d'une commune, pour cadrer la carte avant que le bâtiment ne soit placé. */
  centreCommune(idCommune: number | null | undefined): { latitude: number; longitude: number } | null {
    const c = this.communes().find((x) => x.id === idCommune);
    return c ? { latitude: c.latitude, longitude: c.longitude } : null;
  }

  async charger(): Promise<void> {
    this.erreur.set(null);
    const referentiel = await firstValueFrom(this.http.get<Referentiel>('/api/referentiel'));
    this.referentiel.set(referentiel);
  }

  async chargerSante(): Promise<void> {
    this.sante.set(await firstValueFrom(this.http.get<Sante>('/api/sante')));
  }

  /** Une liste de référence par son domaine : liste('TYPE_LOT'), liste('EQUIPEMENT')… */
  liste(domaine: string): Valeur[] {
    return this.referentiel()?.listes[domaine] ?? [];
  }

  /** Le libellé d'un code dans son domaine ; le code lui-même s'il est inconnu. */
  libelle(domaine: string, code: string | null | undefined): string {
    if (!code) return '-';
    return this.liste(domaine).find((v) => v.code === code)?.libelle ?? code;
  }

  /** Les quartiers d'une commune : liste dépendante du moteur de recherche (§ 3.2). */
  quartiers(idCommune: number | null | undefined): Quartier[] {
    if (!idCommune) return [];
    return this.communes().find((c) => c.id === idCommune)?.quartiers ?? [];
  }

  /** Un seuil réglable, en nombre ; la valeur de repli si la clé est absente. */
  reglage(cle: string, repli: number): number {
    const brut = this.referentiel()?.reglages[cle];
    const n = brut === undefined ? NaN : Number(brut);
    return Number.isFinite(n) ? n : repli;
  }

  /** Le nombre total de quartiers connus, tous communes confondues. */
  nombreQuartiers(): number {
    return this.communes().reduce((total, c) => total + (c.quartiers?.length ?? 0), 0);
  }
}
