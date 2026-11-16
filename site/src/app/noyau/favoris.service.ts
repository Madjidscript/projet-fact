import { Injectable, computed, signal } from '@angular/core';
import { Vignette } from './api';

const CLE = 'fact-site.favoris';
const MAX_COMPARAISON = 3;

/**
 * Les favoris du visiteur (§ 3.7), gardés sur son appareil sans compte. On garde la vignette
 * entière pour afficher la page Favoris sans réseau ; la fiche, elle, est relue en ligne.
 * Le stockage peut être refusé (navigation privée) : le site fonctionne alors sans mémoire.
 */
@Injectable({ providedIn: 'root' })
export class FavorisService {
  readonly liste = signal<Vignette[]>(this.lire());
  /** Les biens cochés pour la comparaison côte à côte (trois au plus). */
  readonly compares = signal<string[]>([]);
  readonly nombre = computed(() => this.liste().length);

  estFavori(slug: string): boolean {
    return this.liste().some((v) => v.slug === slug);
  }

  basculer(v: Vignette): boolean {
    const deja = this.estFavori(v.slug);
    this.liste.update((l) => (deja ? l.filter((x) => x.slug !== v.slug) : [v, ...l]));
    if (deja) this.compares.update((c) => c.filter((s) => s !== v.slug));
    this.ecrire();
    return !deja;
  }

  /** Rend faux si trois biens sont déjà comparés. */
  comparer(slug: string): boolean {
    const c = this.compares();
    if (c.includes(slug)) {
      this.compares.set(c.filter((s) => s !== slug));
      return true;
    }
    if (c.length >= MAX_COMPARAISON) return false;
    this.compares.set([...c, slug]);
    return true;
  }

  private lire(): Vignette[] {
    try {
      const brut = localStorage.getItem(CLE);
      const l = brut ? (JSON.parse(brut) as Vignette[]) : [];
      return Array.isArray(l) ? l.filter((v) => v && typeof v.slug === 'string') : [];
    } catch {
      return [];
    }
  }

  private ecrire(): void {
    try {
      localStorage.setItem(CLE, JSON.stringify(this.liste()));
    } catch {
      /* stockage refusé : les favoris durent le temps de la visite */
    }
  }
}
