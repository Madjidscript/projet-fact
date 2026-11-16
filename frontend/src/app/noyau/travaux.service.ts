import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FicheIntervention, Intervention, PhotoIntervention, Prestataire, SyntheseTravaux } from './modeles';

/** Les appels des interventions et des prestataires. */
@Injectable({ providedIn: 'root' })
export class TravauxService {
  private readonly http = inject(HttpClient);
  /** Les photos déjà chargées, par identifiant : elles ne se téléchargent qu'une fois. */
  private readonly images = new Map<string, Promise<string>>();

  interventions(filtre?: 'ouvertes' | 'closes', batiment?: number): Promise<Intervention[]> {
    const params: Record<string, string | number> = {};
    if (filtre) params['filtre'] = filtre;
    if (batiment) params['batiment'] = batiment;
    return firstValueFrom(this.http.get<Intervention[]>('/api/interventions', { params }));
  }

  synthese(): Promise<SyntheseTravaux> {
    return firstValueFrom(this.http.get<SyntheseTravaux>('/api/interventions/synthese'));
  }

  fiche(id: number): Promise<FicheIntervention> {
    return firstValueFrom(this.http.get<FicheIntervention>(`/api/interventions/${id}`));
  }

  signaler(saisie: object): Promise<Intervention> {
    return firstValueFrom(this.http.post<Intervention>('/api/interventions', saisie));
  }

  /** Fait franchir une étape : affecter, demarrer, valider, terminer, cloturer, annuler. */
  etape(id: number, action: string, saisie: object): Promise<FicheIntervention> {
    return firstValueFrom(this.http.post<FicheIntervention>(`/api/interventions/${id}/${action}`, saisie));
  }

  envoyerPhotos(id: number, moment: 'AVANT' | 'APRES', fichiers: File[]): Promise<PhotoIntervention[]> {
    const corps = new FormData();
    for (const f of fichiers) corps.append('fichiers', f);
    return firstValueFrom(this.http.post<PhotoIntervention[]>(`/api/interventions/${id}/photos`, corps, { params: { moment } }));
  }

  /** L'adresse locale d'une photo : la photo passe par l'API, qui contrôle le droit de la voir. */
  /** Une photo protégée (intervention ou annonce), chargée avec la session puis gardée en mémoire. */
  image(idPhoto: number, source: 'interventions' | 'annonces' | 'batiments' = 'interventions'): Promise<string> {
    const cle = source + '/' + idPhoto;
    let p = this.images.get(cle);
    if (!p) {
      p = firstValueFrom(this.http.get(`/api/${source}/photos/${idPhoto}`, { responseType: 'blob' })).then((b) => URL.createObjectURL(b));
      p.catch(() => this.images.delete(cle));
      this.images.set(cle, p);
    }
    return p;
  }

  prestataires(inactifs = false): Promise<Prestataire[]> {
    return firstValueFrom(this.http.get<Prestataire[]>('/api/prestataires', { params: { inactifs } }));
  }

  enregistrerPrestataire(saisie: object): Promise<Prestataire> {
    return firstValueFrom(this.http.post<Prestataire>('/api/prestataires', saisie));
  }
}

/** « il y a 3 jours », « aujourd'hui » : l'âge d'une intervention. */
export function depuis(iso: string): string {
  const jours = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  if (jours <= 0) return "aujourd'hui";
  if (jours === 1) return 'hier';
  if (jours < 30) return `il y a ${jours} jours`;
  const mois = Math.floor(jours / 30);
  return mois === 1 ? 'il y a un mois' : `il y a ${mois} mois`;
}

/** Les étapes du cycle, dans l'ordre, pour la barre de progression. */
export const ETAPES_INTERVENTION = ['Signalée', 'Affectée', 'En cours', 'Validée', 'Terminée', 'Clôturée'];

export function rangEtape(statut: string): number {
  return ['SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE', 'TERMINEE', 'CLOTUREE'].indexOf(statut);
}
