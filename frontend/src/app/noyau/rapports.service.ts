import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface LigneTaux { libelle: string; lots: number; occupes: number; taux: number | null; }
export interface LigneRecouvrement { libelle: string; attendu: number; regle: number; taux: number | null; }
export interface MoisRecouvrement { mois: string; attendu: number; regle: number; taux: number | null; encaisse: number; }
export interface LigneVacance { libelle: string; cas: number; jours: number; }
export interface LigneM2 { libelle: string; lots: number; prix: number; loyerMoyen: number; surfaceMoyenne: number; }
export interface LigneDelai { libelle: string; interventions: number; jours: number; cout: number; }
export interface DossierImpaye {
  occupant: string; telephone: string | null; batiment: string; lot: string; gestionnaire: string;
  montant: number; moisRetard: number; retardDepuis: string; idBatiment: number; idLot: number;
}

export interface Indicateurs {
  periode: { du: string; au: string; libelle: string };
  occupation: { lots: number; occupes: number; vacants: number; taux: number | null;
    parCommune: LigneTaux[]; parGestionnaire: LigneTaux[]; parProprietaire: LigneTaux[]; parBatiment: LigneTaux[] };
  recouvrement: { attendu: number; regle: number; taux: number | null; encaisse: number;
    parMois: MoisRecouvrement[]; parGestionnaire: LigneRecouvrement[]; parBatiment: LigneRecouvrement[] };
  impayes: { total: number; baux: number; parAnciennete: { ordre: number; libelle: string; baux: number; montant: number }[];
    dossiers: DossierImpaye[] };
  vacance: { moyenne: number | null; relocations: number; parType: LigneVacance[]; parQuartier: LigneVacance[];
    enCours: { lots: number; moyenne: number | null; parType: LigneVacance[]; parQuartier: LigneVacance[] } };
  loyerM2: { moyenne: number | null; lots: number; parQuartier: LigneM2[]; parType: LigneM2[] };
  interventions: { closes: number; delaiMoyen: number | null; cout: number; parCategorie: LigneDelai[]; parPrestataire: LigneDelai[] };
}

export interface Mandat {
  id: number; reference: string; idProprietaire: number; type: string; libelleType: string;
  dateDebut: string; dureeMois: number; reconductionTacite: boolean; tauxHonoraires: number; seuilTravaux: number | null;
  tousLesBiens: boolean; notes: string | null; resilieLe: string | null; motifResiliation: string | null;
  dateFin: string; joursRestants: number; statut: 'ACTIF' | 'A_VENIR' | 'ECHU' | 'RESILIE';
  batiments: { id: number; nom: string }[];
  /** Le mandat signé, déposé en PDF ou en photo. */
  documentNom?: string | null; documentLe?: string | null;
}

export interface MandatSaisie {
  id: number | null; idProprietaire: number; type: string; dateDebut: string; dureeMois: number;
  reconductionTacite: boolean; tauxHonoraires: number | null; seuilTravaux: number | null;
  tousLesBiens: boolean; batiments: number[]; notes: string;
}

export interface Releve {
  periode: { du: string; au: string; libelle: string };
  totaux: { encaisse: number; honoraires: number; travaux: number; aReverser: number };
  batiments: { id: number; nom: string; lots: number; occupes: number; mandat: string | null; taux: number | null;
    encaisse: number; honoraires: number; travaux: number; impaye: number }[];
}

/** « 2026-11 » : le mois tel que l'API l'attend. */
export function moisIso(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/** Les indicateurs du § 4.8, leurs exports, les mandats et le relevé de gestion d'un propriétaire. */
@Injectable({ providedIn: 'root' })
export class RapportsService {
  private readonly http = inject(HttpClient);

  indicateurs(du: string, au: string): Promise<Indicateurs> {
    return firstValueFrom(this.http.get<Indicateurs>('/api/rapports/indicateurs', { params: { du, au } }));
  }

  /** Le PDF s'ouvre dans un onglet ; l'Excel se télécharge. */
  async exporter(format: 'pdf' | 'excel', du: string, au: string): Promise<void> {
    const blob = await firstValueFrom(this.http.get(`/api/rapports/export/${format}`, { params: { du, au }, responseType: 'blob' }));
    livrer(blob, `tableau-de-bord_${du}_${au}.${format === 'pdf' ? 'pdf' : 'xlsx'}`, format === 'pdf');
  }

  releve(idProprietaire: number, du: string, au: string): Promise<Releve> {
    return firstValueFrom(this.http.get<Releve>(`/api/proprietaires/${idProprietaire}/releve`, { params: new HttpParams({ fromObject: { du, au } }) }));
  }

  async relevePdf(idProprietaire: number, reference: string, du: string, au: string): Promise<void> {
    const blob = await firstValueFrom(this.http.get(`/api/proprietaires/${idProprietaire}/releve/pdf`, { params: { du, au }, responseType: 'blob' }));
    livrer(blob, `releve_${reference}_${du}.pdf`, true);
  }

  enregistrerMandat(m: MandatSaisie): Promise<Mandat> {
    return firstValueFrom(this.http.post<Mandat>('/api/mandats', m));
  }

  resilierMandat(id: number, date: string, motif: string): Promise<Mandat> {
    return firstValueFrom(this.http.post<Mandat>(`/api/mandats/${id}/resilier`, { date, motif }));
  }
}

/** Ouvre le fichier dans un onglet (PDF) ou le télécharge. */
function livrer(blob: Blob, nom: string, ouvrir: boolean): void {
  const adresse = URL.createObjectURL(blob);
  const onglet = ouvrir ? window.open(adresse, '_blank') : null;
  if (!onglet) {
    const lien = document.createElement('a');
    lien.href = adresse;
    lien.download = nom;
    lien.click();
  }
  setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
}
