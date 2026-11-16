import type { Mandat } from './rapports.service';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Batiment, LigneJournal, Lot, Proprietaire, RapportImport } from './modeles';

/** Un compte à qui l'on peut confier un bâtiment. */
export interface Gestionnaire {
  id: number;
  nom: string;
  fonction: string | null;
}

/** Les appels de l'API du patrimoine : propriétaires, bâtiments, lots, import. */
@Injectable({ providedIn: 'root' })
export class PatrimoineService {
  private readonly http = inject(HttpClient);

  // ------------------------------------------------------------------ bâtiments
  batiments(archives = false): Promise<Batiment[]> {
    return firstValueFrom(this.http.get<Batiment[]>('/api/batiments', { params: { archives } }));
  }

  batiment(id: number): Promise<{ batiment: Batiment; lots: Lot[]; proprietaire: Proprietaire | null; photos?: { id: number; priseLe: string | null; creePar: string | null }[] }> {
    return firstValueFrom(this.http.get<{ batiment: Batiment; lots: Lot[]; proprietaire: Proprietaire | null; photos?: { id: number; priseLe: string | null; creePar: string | null }[] }>(`/api/batiments/${id}`));
  }

  historique(id: number): Promise<LigneJournal[]> {
    return firstValueFrom(this.http.get<LigneJournal[]>(`/api/batiments/${id}/historique`));
  }

  enregistrerBatiment(saisie: object): Promise<Batiment> {
    return firstValueFrom(this.http.post<Batiment>('/api/batiments', saisie));
  }

  validerBatiment(id: number): Promise<Batiment> {
    return firstValueFrom(this.http.post<Batiment>(`/api/batiments/${id}/valider`, {}));
  }

  archiverBatiment(id: number, archive: boolean): Promise<Batiment> {
    return firstValueFrom(this.http.post<Batiment>(`/api/batiments/${id}/archiver`, {}, { params: { archive } }));
  }

  gestionnaires(): Promise<Gestionnaire[]> {
    return firstValueFrom(this.http.get<Gestionnaire[]>('/api/gestionnaires'));
  }

  // ----------------------------------------------------------------------- lots
  enregistrerLot(saisie: object): Promise<Lot> {
    return firstValueFrom(this.http.post<Lot>('/api/lots', saisie));
  }

  archiverLot(id: number, archive: boolean): Promise<Lot> {
    return firstValueFrom(this.http.post<Lot>(`/api/lots/${id}/archiver`, {}, { params: { archive } }));
  }

  // --------------------------------------------------------------- propriétaires
  proprietaires(archives = false): Promise<Proprietaire[]> {
    return firstValueFrom(this.http.get<Proprietaire[]>('/api/proprietaires', { params: { archives } }));
  }

  proprietaire(id: number): Promise<{ proprietaire: Proprietaire; batiments: Batiment[]; mandats: Mandat[] }> {
    return firstValueFrom(this.http.get<{ proprietaire: Proprietaire; batiments: Batiment[]; mandats: Mandat[] }>(`/api/proprietaires/${id}`));
  }

  enregistrerProprietaire(saisie: object): Promise<Proprietaire> {
    return firstValueFrom(this.http.post<Proprietaire>('/api/proprietaires', saisie));
  }

  archiverProprietaire(id: number, archive: boolean): Promise<Proprietaire> {
    return firstValueFrom(this.http.post<Proprietaire>(`/api/proprietaires/${id}/archiver`, {}, { params: { archive } }));
  }

  // --------------------------------------------------------------------- import
  modeleImport(): Promise<Blob> {
    return firstValueFrom(this.http.get('/api/import/modele', { responseType: 'blob' }));
  }

  importer(fichier: File, simuler: boolean): Promise<RapportImport> {
    const corps = new FormData();
    corps.append('fichier', fichier);
    return firstValueFrom(this.http.post<RapportImport>('/api/import/patrimoine', corps, { params: { simuler } }));
  }
}
