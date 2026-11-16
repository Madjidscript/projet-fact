import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Commune {
  id: number;
  libelle: string;
  latitude?: number | null;
  longitude?: number | null;
  quartiers: { id: number; libelle: string }[];
}

export interface Valeur { code: string; libelle: string }

/** Le référentiel public : communes et quartiers du District d'Abidjan, équipements des biens. */
@Injectable({ providedIn: 'root' })
export class CommunesService {
  private readonly http = inject(HttpClient);
  readonly communes = signal<Commune[]>([]);
  readonly equipements = signal<Valeur[]>([]);
  private charge: Promise<void> | null = null;

  assurer(): Promise<void> {
    this.charge ??= firstValueFrom(this.http.get<{ communes: Commune[]; listes: Record<string, Valeur[]> }>('/api/referentiel'))
      .then((r) => {
        this.communes.set(r.communes);
        this.equipements.set(r.listes['EQUIPEMENT'] ?? []);
      })
      .catch(() => {
        this.charge = null;
      });
    return this.charge;
  }

  commune(id: number | null): Commune | undefined {
    return id === null ? undefined : this.communes().find((c) => c.id === id);
  }

  libelleEquipement(code: string): string {
    return this.equipements().find((e) => e.code === code)?.libelle ?? code;
  }
}
