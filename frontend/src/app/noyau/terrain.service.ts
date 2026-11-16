import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';

/** Un lot relevé sur le terrain. */
export interface LotTerrain {
  numero: string;
  etage: number;
  type: string;
  statut: string;
  pieces: number | null;
  surface: number | null;
  description: string;
}

/** Le recensement tel qu'il part au serveur (POST /api/terrain/recensements). */
export interface Recensement {
  cle: string;
  saisiLe: string;
  nom: string;
  type: string;
  adresse: string | null;
  idCommune: number | null;
  idQuartier: number | null;
  latitude: number | null;
  longitude: number | null;
  precisionGps: number | null;
  niveaux: number | null;
  idProprietaire: number | null;
  description: string | null;
  lots: LotTerrain[];
}

/** Ce que l'agent envoie depuis le terrain, gardé dans le téléphone tant que ce n'est pas parti. */
export type Genre = 'recensement' | 'encaissement' | 'etat-des-lieux' | 'pointage' | 'fin-tournee';

export interface Envoi {
  cle: string;
  identifiant: string;
  /** Absent sur les envois gardés avant l'ajout des autres genres : c'est alors un recensement. */
  genre?: Genre;
  /** Ce que l'agent lit dans la liste des envois. */
  libelle?: string;
  detail?: string;
  donnees?: unknown;
  fichiers?: { champ: string; nom: string; blob: Blob }[];
  recensement?: Recensement;
  photos?: Blob[];
  etat: 'attente' | 'envoi' | 'refuse';
  erreur: string | null;
  essais: number;
  creeLe: string;
}

export interface BatimentTerrain { id: number; nom: string; quartier: string | null; commune: string; adresse: string | null; latitude: number; longitude: number; }

export interface BailTerrain {
  idBail: number; reference: string; statut: string; idLot: number; numeroLot: string; etage: number; typeLot: string | null;
  idBatiment: number; batiment: string; occupant: string; telephone: string | null; loyerMensuel: number; impaye: number;
  moisRetard: number; solde: number; dateEffet: string; dateSortie: string | null; edlEntree: boolean; edlSortie: boolean;
}

export interface EtapeTournee {
  id: number; ordre: number; motif: string; consigne: string | null; pointeeLe: string | null; distanceM: number | null;
  resultat: 'FAIT' | 'ABSENT' | 'REPORTE' | null; compteRendu: string | null; idBatiment: number; reference: string; batiment: string;
  adresse: string | null; quartier: string | null; commune: string; latitude: number | null; longitude: number | null;
  gestionnaire: string | null; lots: number; lotsOccupes: number; impaye: number; interventionsOuvertes: number;
  /** Pointée dans le téléphone, pas encore arrivée à l'agence. */
  enAttente?: boolean;
}

export interface Tournee {
  id: number; reference: string; idAgent: number; agent: string; jour: string; statut: 'PREVUE' | 'EN_COURS' | 'TERMINEE';
  consigne: string | null; compteRendu: string | null; demarreeLe: string | null; termineeLe: string | null; creePar: string;
  etapes: number; pointees: number; etapesDetail: EtapeTournee[]; depart: { latitude: number; longitude: number };
}

/** Ce que le téléphone garde pour travailler sans réseau. */
export interface Preparation {
  proprietaires: { id: number; nom: string; reference: string }[];
  proprietaireAPreciser: number;
  batiments?: BatimentTerrain[];
  baux?: BailTerrain[];
  tournee?: Tournee | null;
  droits?: { encaisser: boolean; etatDesLieux: boolean; recenser: boolean };
  le: string;
}

const BASE = 'fact-terrain';
const ENVOIS = 'envois';
const RESERVE = 'reserve';
const DERNIER = 'fact-immobilier.terrain';

export const MOTIFS: Partial<Record<string, string>> = {
  VISITE: 'Visite', RECOUVREMENT: 'Recouvrement', ETAT_DES_LIEUX: 'État des lieux', CONTROLE: 'Contrôle de fiche', TRAVAUX: 'Travaux',
};

/**
 * L'application de terrain sans réseau (§ 4.6) : tout ce que l'agent saisit (recensements,
 * encaissements, états des lieux, pointages de tournée) est rangé dans le téléphone (IndexedDB,
 * photos et signatures comprises) puis envoyé de lui-même au retour du réseau, dès qu'une session
 * est ouverte. Chaque envoi porte une clé tirée ici : un envoi rejoué ne crée pas de doublon.
 */
@Injectable({ providedIn: 'root' })
export class TerrainService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  readonly enLigne = signal(typeof navigator === 'undefined' ? true : navigator.onLine);
  readonly envois = signal<Envoi[]>([]);
  readonly synchronisation = signal(false);
  /** Vrai un instant après un envoi réussi : la coche de confirmation (§ 6.2). */
  readonly confirme = signal(false);
  readonly enAttente = computed(() => this.envois().filter((e) => e.etat !== 'refuse').length);
  readonly refuses = computed(() => this.envois().filter((e) => e.etat === 'refuse').length);
  /** La préparation gardée dans le téléphone, tenue à jour des saisies locales. */
  readonly preparation = signal<Preparation | null>(null);

  private base: Promise<IDBDatabase> | null = null;
  private minuterie: ReturnType<typeof setInterval> | null = null;

  constructor() {
    const enLigne = () => {
      this.enLigne.set(true);
      this.synchroniser();
    };
    const horsLigne = () => this.enLigne.set(false);
    window.addEventListener('online', enLigne);
    window.addEventListener('offline', horsLigne);
    this.minuterie = setInterval(() => this.synchroniser(), 60_000);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', enLigne);
      window.removeEventListener('offline', horsLigne);
      if (this.minuterie) clearInterval(this.minuterie);
    });
    effect(() => {
      const c = this.auth.compte();
      if (c) {
        // L'agent connecté est retenu : il pourra saisir hors connexion même si sa session se ferme.
        try {
          localStorage.setItem(DERNIER, JSON.stringify({ identifiant: c.identifiant, nom: c.nom, profil: c.profil }));
        } catch { /* stockage indisponible */ }
        this.synchroniser();
      }
    });
    this.relire();
  }

  /** L'agent retenu sur ce téléphone, pour saisir hors connexion. */
  agentRetenu(): { identifiant: string; nom: string; profil: string } | null {
    try {
      const v = localStorage.getItem(DERNIER);
      return v ? JSON.parse(v) : null;
    } catch {
      return null;
    }
  }

  /** Une clé unique, tirée sur le téléphone. */
  nouvelleCle(): string {
    return crypto.randomUUID ? crypto.randomUUID()
      : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
      });
  }

  // ------------------------------------------------------------------ préparation
  /**
   * Garde dans le téléphone ce qu'il faut pour travailler sans réseau : propriétaires, bâtiments,
   * baux en cours, tournée du jour (le référentiel est gardé par le service worker). Sans réseau,
   * rend la dernière préparation gardée.
   */
  async preparer(): Promise<Preparation | null> {
    try {
      const p = await firstValueFrom(this.http.get<Omit<Preparation, 'le'>>('/api/terrain/preparation'));
      const prep = this.appliquerLocal({ ...p, le: new Date().toISOString() });
      await this.ecrire(RESERVE, prep, 'preparation');
      this.preparation.set(prep);
      return prep;
    } catch {
      const p = (await this.lire<Preparation>(RESERVE, 'preparation')) ?? null;
      this.preparation.set(p ? this.appliquerLocal(p) : null);
      return this.preparation();
    }
  }

  /** Reporte sur la préparation ce qui est saisi dans le téléphone et pas encore parti. */
  private appliquerLocal(p: Preparation): Preparation {
    const t = p.tournee;
    if (!t) return p;
    const pointages = this.envois().filter((e) => e.genre === 'pointage' && e.etat !== 'refuse');
    const fin = this.envois().find((e) => e.genre === 'fin-tournee' && (e.donnees as { idTournee: number }).idTournee === t.id);
    const etapes = t.etapesDetail.map((e) => {
      const local = pointages.find((x) => (x.donnees as { idEtape: number }).idEtape === e.id);
      if (!local) return e;
      const d = local.donnees as { corps: { pointeeLe: string; resultat: EtapeTournee['resultat']; compteRendu: string | null }; distanceM: number | null };
      return { ...e, pointeeLe: e.pointeeLe ?? d.corps.pointeeLe, resultat: d.corps.resultat, compteRendu: d.corps.compteRendu,
        distanceM: e.distanceM ?? d.distanceM, enAttente: true };
    });
    const pointees = etapes.filter((e) => e.pointeeLe).length;
    return { ...p, tournee: { ...t, etapesDetail: etapes, pointees,
      statut: fin ? 'TERMINEE' : pointees && t.statut === 'PREVUE' ? 'EN_COURS' : t.statut,
      compteRendu: fin ? (fin.donnees as { compteRendu: string }).compteRendu : t.compteRendu } };
  }

  // ------------------------------------------------------------------ file d'envoi
  async garder(recensement: Recensement, photos: Blob[]): Promise<void> {
    await this.mettreEnFile({ cle: recensement.cle, genre: 'recensement', libelle: recensement.nom,
      detail: `${recensement.lots.length} lot(s) · ${photos.length} photo(s)`, recensement, photos });
  }

  /** Range un envoi d'un autre genre (encaissement, état des lieux, pointage, fin de tournée). */
  async mettreEnFile(e: Pick<Envoi, 'cle' | 'genre' | 'libelle' | 'detail' | 'donnees' | 'fichiers' | 'recensement' | 'photos'>): Promise<void> {
    const agent = this.auth.compte()?.identifiant ?? this.agentRetenu()?.identifiant ?? 'inconnu';
    await this.ecrire(ENVOIS, { ...e, identifiant: agent, etat: 'attente', erreur: null, essais: 0, creeLe: new Date().toISOString() } as Envoi);
    await this.relire();
    const p = this.preparation();
    if (p) this.preparation.set(this.appliquerLocal(p));
    this.synchroniser();
  }

  async supprimer(cle: string): Promise<void> {
    await this.effacer(ENVOIS, cle);
    await this.relire();
  }

  async reessayer(cle: string): Promise<void> {
    const e = this.envois().find((x) => x.cle === cle);
    if (!e) return;
    await this.ecrire(ENVOIS, { ...e, etat: 'attente', erreur: null });
    await this.relire();
    this.synchroniser();
  }

  libelle(e: Envoi): string {
    return e.libelle ?? e.recensement?.nom ?? 'Envoi';
  }

  detail(e: Envoi): string {
    return e.detail ?? (e.recensement ? `${e.recensement.lots.length} lot(s) · ${e.photos?.length ?? 0} photo(s)` : '');
  }

  /** Envoie ce qui attend, dans l'ordre de saisie ; s'arrête à la première coupure. */
  async synchroniser(): Promise<void> {
    const compte = this.auth.compte();
    if (this.synchronisation() || !compte || !navigator.onLine) return;
    // Le drapeau est posé avant toute attente : deux réveils simultanés (retour du réseau, minuterie)
    // n'envoient pas deux fois la même saisie.
    this.synchronisation.set(true);
    const aEnvoyer = (await this.tous()).filter((e) => e.etat !== 'refuse' && e.identifiant === compte.identifiant);
    if (!aEnvoyer.length) {
      this.synchronisation.set(false);
      return;
    }
    let reussis = 0;
    try {
      for (const e of aEnvoyer) {
        try {
          await this.envoyer(e);
          await this.effacer(ENVOIS, e.cle);
          reussis++;
        } catch (err) {
          const statut = err instanceof HttpErrorResponse ? err.status : 0;
          if (statut === 400 || statut === 403 || statut === 404 || statut === 409 || statut === 422) {
            // Le serveur refuse le contenu : l'agent doit corriger, renvoyer tel quel ne servirait à rien.
            const message = err instanceof HttpErrorResponse ? (err.error?.message ?? 'Envoi refusé.') : 'Envoi refusé.';
            await this.ecrire(ENVOIS, { ...e, etat: 'refuse', erreur: message, essais: e.essais + 1 });
          } else {
            // Réseau coupé ou serveur indisponible : on réessaiera plus tard.
            await this.ecrire(ENVOIS, { ...e, essais: e.essais + 1 });
            break;
          }
        }
      }
    } finally {
      this.synchronisation.set(false);
      await this.relire();
      if (reussis) {
        this.confirme.set(true);
        setTimeout(() => this.confirme.set(false), 2500);
        // Ce qui est parti est désormais connu de l'agence : la préparation est rafraîchie.
        this.preparer();
      }
    }
  }

  private async envoyer(e: Envoi): Promise<void> {
    const genre = e.genre ?? 'recensement';
    if (genre === 'recensement') {
      const corps = new FormData();
      corps.append('recensement', new Blob([JSON.stringify(e.recensement)], { type: 'application/json' }));
      (e.photos ?? []).forEach((p, i) => corps.append('photos', p, `photo-${i + 1}.jpg`));
      await firstValueFrom(this.http.post('/api/terrain/recensements', corps));
    } else if (genre === 'encaissement') {
      await firstValueFrom(this.http.post('/api/encaissements', e.donnees));
    } else if (genre === 'etat-des-lieux') {
      const corps = new FormData();
      corps.append('etatDesLieux', new Blob([JSON.stringify(e.donnees)], { type: 'application/json' }));
      for (const f of e.fichiers ?? []) corps.append(f.champ, f.blob, f.nom);
      await firstValueFrom(this.http.post('/api/etats-des-lieux', corps));
    } else if (genre === 'pointage') {
      const d = e.donnees as { idTournee: number; idEtape: number; corps: unknown };
      await firstValueFrom(this.http.post(`/api/tournees/${d.idTournee}/etapes/${d.idEtape}/pointage`, d.corps));
    } else if (genre === 'fin-tournee') {
      const d = e.donnees as { idTournee: number; compteRendu: string };
      try {
        await firstValueFrom(this.http.post(`/api/tournees/${d.idTournee}/terminer`, { compteRendu: d.compteRendu }));
      } catch (err) {
        // Déjà terminée (un envoi précédent avait abouti sans réponse) : rien à refaire.
        if (!(err instanceof HttpErrorResponse && err.status === 422 && /déjà terminée/.test(err.error?.message ?? ''))) throw err;
      }
    }
  }

  // ------------------------------------------------------------------ IndexedDB
  private ouvrir(): Promise<IDBDatabase> {
    if (!this.base) {
      this.base = new Promise((ok, ko) => {
        const r = indexedDB.open(BASE, 1);
        r.onupgradeneeded = () => {
          r.result.createObjectStore(ENVOIS, { keyPath: 'cle' });
          r.result.createObjectStore(RESERVE);
        };
        r.onsuccess = () => ok(r.result);
        r.onerror = () => ko(r.error);
      });
    }
    return this.base;
  }

  private async transaction<T>(magasin: string, mode: IDBTransactionMode, faire: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    const db = await this.ouvrir();
    return new Promise((ok, ko) => {
      const t = db.transaction(magasin, mode);
      const r = faire(t.objectStore(magasin));
      t.oncomplete = () => ok(r.result);
      t.onerror = () => ko(t.error);
      t.onabort = () => ko(t.error);
    });
  }

  private ecrire(magasin: string, valeur: unknown, cle?: string): Promise<IDBValidKey> {
    return this.transaction(magasin, 'readwrite', (s) => (cle === undefined ? s.put(valeur) : s.put(valeur, cle)));
  }

  private lire<T>(magasin: string, cle: string): Promise<T | undefined> {
    return this.transaction(magasin, 'readonly', (s) => s.get(cle) as IDBRequest<T | undefined>);
  }

  private effacer(magasin: string, cle: string): Promise<undefined> {
    return this.transaction(magasin, 'readwrite', (s) => s.delete(cle) as IDBRequest<undefined>);
  }

  private tous(): Promise<Envoi[]> {
    return this.transaction(ENVOIS, 'readonly', (s) => s.getAll() as IDBRequest<Envoi[]>).catch(() => []);
  }

  private async relire(): Promise<void> {
    const l = await this.tous();
    this.envois.set(l.sort((a, b) => a.creeLe.localeCompare(b.creeLe)));
  }
}

/** Réduit une photo à 1 600 pixels au plus grand côté, en JPEG : moins de place dans le téléphone et sur le réseau. */
export async function reduirePhoto(fichier: File, cote = 1600): Promise<Blob> {
  try {
    const image = await createImageBitmap(fichier);
    const echelle = Math.min(1, cote / Math.max(image.width, image.height));
    const toile = document.createElement('canvas');
    toile.width = Math.round(image.width * echelle);
    toile.height = Math.round(image.height * echelle);
    toile.getContext('2d')!.drawImage(image, 0, 0, toile.width, toile.height);
    image.close?.();
    return await new Promise<Blob>((ok) => toile.toBlob((b) => ok(b ?? fichier), 'image/jpeg', 0.82));
  } catch {
    return fichier;
  }
}

/** Distance en mètres entre deux points (formule de haversine). */
export function distanceM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6_371_000, f1 = lat1 * Math.PI / 180, f2 = lat2 * Math.PI / 180;
  const df = f2 - f1, dl = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(df / 2) ** 2 + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

/** La position du téléphone, ou une erreur dite simplement. */
export function maPosition(): Promise<GeolocationPosition> {
  return new Promise((ok, ko) => {
    if (!navigator.geolocation) {
      ko(new Error('Ce téléphone ne donne pas sa position.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(ok, (e) => ko(new Error(e.code === 1
      ? 'La position est refusée : autorisez-la dans les réglages du navigateur.'
      : 'La position n\'a pas pu être trouvée : sortez à découvert et réessayez.')), { enableHighAccuracy: true, timeout: 20_000, maximumAge: 30_000 });
  });
}
