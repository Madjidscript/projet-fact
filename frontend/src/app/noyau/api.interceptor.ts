import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';

/**
 * Les appels s'écrivent « /api/… » ; l'adresse réelle de l'API vient de l'environnement
 * (environment.apiUrl). Ajoute le jeton à chaque appel de l'API ; une session que le serveur
 * ne reconnaît plus ramène à la connexion, un mot de passe provisoire à son changement.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);
  const jeton = auth.jeton();
  const demande = req.clone({
    url: environment.apiUrl + req.url.substring('/api'.length),
    ...(jeton ? { setHeaders: { Authorization: `Bearer ${jeton}` } } : {}),
  });
  return next(demande).pipe(
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && !req.url.endsWith('/auth/connexion') && auth.connecte()) {
        auth.deconnexion('Votre session a été fermée. Reconnectez-vous.');
      } else if (e.status === 403 && e.error?.code === 'MOT_DE_PASSE_PROVISOIRE') {
        router.navigateByUrl('/mot-de-passe');
      }
      return throwError(() => e);
    }),
  );
};

/** Le message d'une erreur de l'API, en clair. */
export function messageErreur(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return "Le serveur ne répond pas. Vérifiez qu'il est démarré.";
    if (e.error && typeof e.error === 'object' && 'message' in e.error) return String(e.error.message);
    if (e.status === 413) return 'Le fichier est trop lourd pour le serveur.';
    return `Le serveur a répondu ${e.status}.`;
  }
  return e instanceof Error ? e.message : 'Erreur inattendue.';
}
