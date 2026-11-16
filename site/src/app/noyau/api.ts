import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

const API = '/api/public';

export type Operation = 'LOCATION' | 'VENTE';

/** Un bien en ligne, tel que la liste et la carte le montrent. */
export interface Vignette {
  id: number;
  slug: string;
  reference: string;
  titre: string;
  operation: Operation;
  prix: number;
  charges: number | null;
  categorie: string;
  libelleType: string;
  commune: string;
  idCommune: number;
  quartier: string | null;
  idQuartier: number | null;
  surface: number | null;
  pieces: number | null;
  chambres: number | null;
  sallesEau: number | null;
  equipements: string[];
  latitude: number | null;
  longitude: number | null;
  /** Faux : la position est approchée, on montre une zone et non un point. */
  positionExacte: boolean;
  aLaUne: boolean;
  publieeLe: string;
  photos: number;
  idPhoto: number | null;
}

export interface Photo { id: number; legende: string | null; largeur: number; hauteur: number }

export interface FicheBien extends Vignette {
  description: string | null;
  cautionMois: number | null;
  avanceMois: number | null;
  etage: number | null;
  galerie: Photo[];
  libellesEquipements: string[];
}

export interface Chiffres { batiments?: number; lots?: number; communes?: number; enLigne?: number; annees?: number }

/** Les critères de recherche du § 3.2, tels qu'ils sont dans l'adresse de la page. */
export interface Criteres {
  operation: Operation;
  types: string[];
  commune: number | null;
  quartier: number | null;
  prixMin: number | null;
  prixMax: number | null;
  pieces: number | null;
  surface: number | null;
  options: string[];
  tri: 'recent' | 'prix-croissant' | 'prix-decroissant' | 'surface';
}

export interface Zone { ouest: number; sud: number; est: number; nord: number }

export type TypeDemande = 'VISITE' | 'MANDAT' | 'DEVIS' | 'ESTIMATION' | 'CONTACT';

export interface Demande {
  type: TypeDemande;
  slug?: string;
  nom: string;
  telephone: string;
  email?: string | null;
  dateSouhaitee?: string;
  creneau?: string;
  message?: string | null;
  details?: Record<string, string | number | boolean | null>;
  latitude?: number | null;
  longitude?: number | null;
  consentement: boolean;
  /** Champ piège contre les robots : toujours vide. */
  site: string;
}

export interface Accuse { reference: string; message: string }

/** L'adresse d'une photo publique. */
export const photo = (id: number) => `${API}/photos/${id}`;

/** Le message à montrer au visiteur pour une erreur de l'API (rédigé en français par l'API). */
export function messageErreur(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    const m = (e.error as { message?: string } | null)?.message;
    if (m && (e.status === 422 || e.status === 429 || e.status === 400 || e.status === 404)) return m;
    if (e.status === 0) return 'Connexion impossible. Vérifiez votre accès à Internet et réessayez.';
  }
  return 'Une erreur est survenue. Réessayez dans un instant, ou appelez-nous.';
}

/** L'API publique du site : biens en ligne, chiffres, demandes et alertes. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  biens(c: Criteres, zone: Zone | null): Promise<Vignette[]> {
    let p = new HttpParams().set('operation', c.operation).set('tri', c.tri);
    for (const t of c.types) p = p.append('types', t);
    for (const o of c.options) p = p.append('options', o);
    const nombres: [string, number | null][] = [['commune', c.commune], ['quartier', c.quartier], ['prixMin', c.prixMin],
      ['prixMax', c.prixMax], ['pieces', c.pieces], ['surface', c.surface]];
    for (const [k, v] of nombres) if (v !== null && v !== undefined) p = p.set(k, v);
    if (zone) p = p.set('ouest', zone.ouest).set('sud', zone.sud).set('est', zone.est).set('nord', zone.nord);
    return firstValueFrom(this.http.get<Vignette[]>(`${API}/biens`, { params: p }));
  }

  aLaUne(): Promise<Vignette[]> {
    return firstValueFrom(this.http.get<Vignette[]>(`${API}/biens/une`));
  }

  bien(slug: string): Promise<{ bien: FicheBien; similaires: Vignette[] }> {
    return firstValueFrom(this.http.get<{ bien: FicheBien; similaires: Vignette[] }>(`${API}/biens/${encodeURIComponent(slug)}`));
  }

  chiffres(): Promise<Chiffres> {
    return firstValueFrom(this.http.get<Chiffres>(`${API}/chiffres`));
  }

  /** Une demande, avec ses pièces jointes s'il y en a (envoi en plusieurs parties). */
  demande(d: Demande, fichiers: File[] = []): Promise<Accuse> {
    if (!fichiers.length) return firstValueFrom(this.http.post<Accuse>(`${API}/demandes`, d));
    const corps = new FormData();
    corps.append('demande', new Blob([JSON.stringify(d)], { type: 'application/json' }));
    for (const f of fichiers) corps.append('fichiers', f, f.name);
    return firstValueFrom(this.http.post<Accuse>(`${API}/demandes`, corps));
  }

  alerte(contact: string, criteres: Record<string, unknown>): Promise<{ message: string }> {
    return firstValueFrom(this.http.post<{ message: string }>(`${API}/alertes`, { contact, criteres, consentement: true, site: '' }));
  }

  arreterAlerte(jeton: string): Promise<{ arrete: boolean; message: string }> {
    return firstValueFrom(this.http.post<{ arrete: boolean; message: string }>(`${API}/alertes/arreter`, null, { params: { jeton } }));
  }
}
