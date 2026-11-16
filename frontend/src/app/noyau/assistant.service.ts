import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Un message de la conversation, tel que l'API le garde en base. */
export interface MessageAssistant {
  role: 'user' | 'assistant';
  texte: string;
  creeLe: string;
}

/** Consigne « ouvre cette page » laissée par un outil du serveur (le serveur ne peut pas cliquer). */
export interface ActionNaviguer {
  type: 'naviguer';
  chemin: string;
  libelle: string;
}

/** Consigne « affiche une carte Valider / Annuler » : une action proposée qui attend le clic. */
export interface ActionValidation {
  type: 'validation';
  id: number;
  resume: string;
  expireLe: string;
}

export type ActionAssistant = ActionNaviguer | ActionValidation;

export interface ReponseAssistant {
  reponse: string;
  actions: ActionAssistant[];
}

/**
 * L'assistant IA. Angular ne parle JAMAIS à OpenAI : il parle à notre API, qui seule
 * connaît la clé. L'intercepteur ajoute le jeton de session, comme pour tout appel /api.
 */
@Injectable({ providedIn: 'root' })
export class AssistantService {
  private readonly http = inject(HttpClient);

  /** {@code page} : l'adresse de la page ouverte, pour que l'assistant comprenne « ici ». */
  demander(texte: string, page: string): Observable<ReponseAssistant> {
    return this.http.post<ReponseAssistant>('/api/assistant', { texte, page });
  }

  /** La conversation gardée en base : elle survit au rechargement de la page. */
  historique(): Observable<MessageAssistant[]> {
    return this.http.get<MessageAssistant[]>('/api/assistant/historique');
  }

  effacer(): Observable<void> {
    return this.http.delete<void>('/api/assistant/historique');
  }

  /** Le clic « Valider » : c'est seulement ici que l'action proposée s'exécute, côté serveur. */
  valider(id: number): Observable<ReponseAssistant> {
    return this.http.post<ReponseAssistant>(`/api/assistant/validations/${id}/valider`, {});
  }

  annuler(id: number): Observable<ReponseAssistant> {
    return this.http.post<ReponseAssistant>(`/api/assistant/validations/${id}/annuler`, {});
  }
}
