import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Bail, Echeance, Encaissement, LocationLot, Occupant, PeriodeReglee, SyntheseLoyers } from './modeles';

/** Les appels de la gestion locative : occupants, baux, encaissements, quittances. */
@Injectable({ providedIn: 'root' })
export class LocationService {
  private readonly http = inject(HttpClient);

  synthese(): Promise<SyntheseLoyers> {
    return firstValueFrom(this.http.get<SyntheseLoyers>('/api/loyers/synthese'));
  }

  baux(filtre?: 'actifs' | 'retard', batiment?: number): Promise<Bail[]> {
    const params: Record<string, string | number> = {};
    if (filtre) params['filtre'] = filtre;
    if (batiment) params['batiment'] = batiment;
    return firstValueFrom(this.http.get<Bail[]>('/api/baux', { params }));
  }

  bail(id: number): Promise<{ bail: Bail; echeances: Echeance[]; encaissements: Encaissement[] }> {
    return firstValueFrom(this.http.get<{ bail: Bail; echeances: Echeance[]; encaissements: Encaissement[] }>(`/api/baux/${id}`));
  }

  locationDuLot(idLot: number): Promise<LocationLot> {
    return firstValueFrom(this.http.get<LocationLot>(`/api/lots/${idLot}/location`));
  }

  occupants(): Promise<Occupant[]> {
    return firstValueFrom(this.http.get<Occupant[]>('/api/occupants'));
  }

  creerBail(saisie: object): Promise<{ bail: Bail; encaissement?: { encaissement: Encaissement } }> {
    return firstValueFrom(this.http.post<{ bail: Bail; encaissement?: { encaissement: Encaissement } }>('/api/baux', saisie));
  }

  preavis(id: number, dateSortie: string, motif: string): Promise<Bail> {
    return firstValueFrom(this.http.post<Bail>(`/api/baux/${id}/preavis`, { dateSortie, motif }));
  }

  terminer(id: number, dateSortie: string, motif: string): Promise<Bail> {
    return firstValueFrom(this.http.post<Bail>(`/api/baux/${id}/terminer`, { dateSortie, motif }));
  }

  encaisser(saisie: object): Promise<{ encaissement: Encaissement; periodes: PeriodeReglee[]; bail: Bail }> {
    return firstValueFrom(this.http.post<{ encaissement: Encaissement; periodes: PeriodeReglee[]; bail: Bail }>('/api/encaissements', saisie));
  }

  annuler(id: number, motif: string): Promise<Encaissement> {
    return firstValueFrom(this.http.post<Encaissement>(`/api/encaissements/${id}/annuler`, { motif }));
  }

  /** Ouvre la quittance PDF dans un nouvel onglet ; la télécharge si le navigateur bloque l'ouverture. */
  etatsDesLieux(idBail: number): Promise<{ id: number; reference: string; type: string; faitLe: string; signataire: string; creePar: string }[]> {
    return firstValueFrom(this.http.get<{ id: number; reference: string; type: string; faitLe: string; signataire: string; creePar: string }[]>(
      `/api/baux/${idBail}/etats-des-lieux`));
  }

  /** Ouvre un PDF protégé dans un onglet (ou le télécharge si l'onglet est bloqué). */
  async ouvrirPdf(url: string, nom: string): Promise<void> {
    const blob = await firstValueFrom(this.http.get(url, { responseType: 'blob' }));
    const adresse = URL.createObjectURL(blob);
    if (!window.open(adresse, '_blank')) {
      const lien = document.createElement('a');
      lien.href = adresse;
      lien.download = nom;
      lien.click();
    }
    setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
  }

  async ouvrirQuittance(id: number, numero: string): Promise<void> {
    const blob = await firstValueFrom(this.http.get(`/api/encaissements/${id}/quittance`, { responseType: 'blob' }));
    const adresse = URL.createObjectURL(blob);
    const onglet = window.open(adresse, '_blank');
    if (!onglet) {
      const lien = document.createElement('a');
      lien.href = adresse;
      lien.download = `quittance_${numero}.pdf`;
      lien.click();
    }
    setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
  }
}

/** Le mois d'une date ISO, en clair : « novembre 2026 ». */
export function moisEnClair(iso: string): string {
  const [a, m] = iso.split('-').map(Number);
  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(new Date(a, m - 1, 1));
}

/** La date du jour en ISO (AAAA-MM-JJ), à l'heure de l'appareil. */
export function aujourdhui(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
