import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Lot } from './modeles';

export type StatutAnnonce = 'BROUILLON' | 'A_VALIDER' | 'PUBLIEE' | 'REFUSEE' | 'RETIREE';

export interface Annonce {
  id: number;
  reference: string;
  slug: string;
  idLot: number;
  operation: 'LOCATION' | 'VENTE';
  titre: string;
  description: string | null;
  prix: number;
  charges: number;
  cautionMois: number;
  avanceMois: number;
  positionExacte: boolean;
  aLaUne: boolean;
  statut: StatutAnnonce;
  libelleStatut: string;
  motif: string | null;
  soumiseLe: string | null;
  soumisePar: string | null;
  publieeLe: string | null;
  publieePar: string | null;
  retireeLe: string | null;
  retireePar: string | null;
  creeLe: string;
  creePar: string | null;
  majLe: string | null;
  numeroLot: string;
  typeLot: string;
  libelleType: string;
  surface: number | null;
  pieces: number | null;
  chambres: number | null;
  idBatiment: number;
  batiment: string;
  commune: string;
  quartier: string | null;
  latitude: number | null;
  photos: number;
  idPhoto: number | null;
}

export interface PhotoAnnonce { id: number; ordre: number; legende: string | null; largeur: number; hauteur: number; }

export interface FicheAnnonce {
  annonce: Annonce;
  photos: PhotoAnnonce[];
  manques: string[];
  actions: { modifier: boolean; soumettre: boolean; valider: boolean; refuser: boolean; retirer: boolean; aLaUne: boolean; photos: boolean };
  lien: string;
}

export interface AnnonceDuLot extends Partial<Omit<FicheAnnonce, 'annonce'>> {
  lot: Lot;
  annonce: Annonce | null;
  photosMinimum: number;
}

export interface AnnonceSaisie {
  id: number | null;
  idLot: number;
  operation: 'LOCATION' | 'VENTE';
  titre: string;
  description: string;
  prix: number | null;
  charges: number | null;
  cautionMois: number | null;
  avanceMois: number | null;
  positionExacte: boolean;
}

export type StatutDemande = 'NOUVELLE' | 'PRISE_EN_CHARGE' | 'RENDEZ_VOUS' | 'CONCLUE' | 'SANS_SUITE';

export interface Demande {
  id: number;
  reference: string;
  type: 'VISITE' | 'MANDAT' | 'DEVIS' | 'ESTIMATION' | 'CONTACT';
  libelleType: string;
  statut: StatutDemande;
  libelleStatut: string;
  idAnnonce: number | null;
  annonce: string | null;
  slug: string | null;
  referenceAnnonce: string | null;
  idBatiment: number | null;
  batiment: string | null;
  idCompte: number | null;
  traitePar: string | null;
  nom: string;
  telephone: string;
  email: string | null;
  dateSouhaitee: string | null;
  creneau: string | null;
  message: string | null;
  details: Record<string, unknown>;
  latitude: number | null;
  longitude: number | null;
  note: string | null;
  rdvLe: string | null;
  creeLe: string;
  majLe: string | null;
  majPar: string | null;
  pieces: number;
  enRetard: boolean;
}

export interface PieceJointe { id: number; nom: string; type: string; taille: number; }

export interface MessageAEnvoyer {
  id: number;
  canal: 'EMAIL' | 'WHATSAPP' | 'SMS';
  destinataire: string;
  sujet: string | null;
  corps: string;
  motif: 'ACCUSE' | 'ALERTE_RECHERCHE' | 'RELEVE' | 'RECU';
  idDemande: number | null;
  referenceDemande: string | null;
  creeLe: string;
}

/** Les annonces du site (§ 4.10) et les demandes venues du site (§ 3.8), côté application. */
@Injectable({ providedIn: 'root' })
export class AnnoncesService {
  private readonly http = inject(HttpClient);

  lister(statut?: StatutAnnonce): Promise<Annonce[]> {
    return firstValueFrom(this.http.get<Annonce[]>('/api/annonces', { params: statut ? { statut } : {} }));
  }

  fiche(id: number): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.get<FicheAnnonce>(`/api/annonces/${id}`));
  }

  duLot(idLot: number): Promise<AnnonceDuLot> {
    return firstValueFrom(this.http.get<AnnonceDuLot>(`/api/lots/${idLot}/annonce`));
  }

  enregistrer(s: AnnonceSaisie): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>('/api/annonces', s));
  }

  action(id: number, action: 'soumettre' | 'valider'): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/${id}/${action}`, {}));
  }

  avecMotif(id: number, action: 'refuser' | 'retirer', motif: string): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/${id}/${action}`, { motif }));
  }

  aLaUne(id: number, valeur: boolean): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/${id}/une`, {}, { params: { valeur } }));
  }

  ajouterPhotos(id: number, fichiers: File[]): Promise<FicheAnnonce> {
    const corps = new FormData();
    for (const f of fichiers) corps.append('fichiers', f, f.name);
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/${id}/photos`, corps));
  }

  supprimerPhoto(idPhoto: number): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/photos/${idPhoto}/supprimer`, {}));
  }

  ordonner(id: number, ordre: number[]): Promise<FicheAnnonce> {
    return firstValueFrom(this.http.post<FicheAnnonce>(`/api/annonces/${id}/photos/ordre`, ordre));
  }

  // ------------------------------------------------------------------ demandes
  demandes(): Promise<{ demandes: Demande[]; messagesEnAttente: number; comptes: { id: number; nom: string; fonction: string | null }[] | null }> {
    return firstValueFrom(this.http.get<{ demandes: Demande[]; messagesEnAttente: number; comptes: { id: number; nom: string; fonction: string | null }[] | null }>('/api/demandes'));
  }

  demande(id: number): Promise<{ demande: Demande; pieces: PieceJointe[]; peutModifier: boolean }> {
    return firstValueFrom(this.http.get<{ demande: Demande; pieces: PieceJointe[]; peutModifier: boolean }>(`/api/demandes/${id}`));
  }

  suivre(id: number, s: { statut: StatutDemande; note: string | null; idCompte: number | null; rdvLe: string | null }) {
    return firstValueFrom(this.http.post<{ demande: Demande; pieces: PieceJointe[]; peutModifier: boolean }>(`/api/demandes/${id}/suivi`, s));
  }

  async ouvrirPiece(p: PieceJointe): Promise<void> {
    const blob = await firstValueFrom(this.http.get(`/api/demandes/pieces/${p.id}`, { responseType: 'blob' }));
    const adresse = URL.createObjectURL(blob);
    if (!window.open(adresse, '_blank')) {
      const a = document.createElement('a');
      a.href = adresse;
      a.download = p.nom;
      a.click();
    }
    setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
  }

  messages(): Promise<MessageAEnvoyer[]> {
    return firstValueFrom(this.http.get<MessageAEnvoyer[]>('/api/demandes/messages'));
  }

  marquerMessage(id: number, envoye: boolean): Promise<{ restants: number }> {
    return firstValueFrom(this.http.post<{ restants: number }>(`/api/demandes/messages/${id}`, {}, { params: { envoye } }));
  }
}

/** « 07 12 34 56 78 » → lien WhatsApp avec l'indicatif ivoirien quand il manque. */
export function lienWhatsApp(numero: string, texte?: string): string {
  let n = numero.replace(/\D/g, '');
  if (n.length === 10) n = '225' + n;
  return `https://wa.me/${n}` + (texte ? `?text=${encodeURIComponent(texte)}` : '');
}
