import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Réservé aux personnes connectées ; un mot de passe provisoire mène d'abord à son changement. */
export const connecteGarde: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.connecte()) return router.parseUrl('/connexion');
  if (auth.compte()!.doitChanger) return router.parseUrl('/mot-de-passe');
  return true;
};

/**
 * Le recensement de terrain (§ 4.6) : un compte qui saisit le patrimoine ; hors connexion, l'agent
 * déjà connecté sur ce téléphone peut saisir même si sa session s'est fermée (l'envoi attendra).
 */
export const terrainGarde: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.connecte()) {
    if (auth.compte()!.doitChanger) return router.parseUrl('/mot-de-passe');
    return auth.a('patrimoine.ecrire') || router.parseUrl(auth.accueil());
  }
  let retenu = false;
  try {
    retenu = !!localStorage.getItem('fact-immobilier.terrain');
  } catch { /* stockage indisponible */ }
  return (!navigator.onLine && retenu) || router.parseUrl('/connexion');
};

/** La page de connexion n'a rien à montrer à qui est déjà connecté. */
export const inviteGarde: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.connecte() ? inject(Router).parseUrl(auth.accueil()) : true;
};

/** Le changement de mot de passe demande une session, même provisoire. */
export const sessionGarde: CanActivateFn = () => {
  return inject(AuthService).connecte() || inject(Router).parseUrl('/connexion');
};

/**
 * Réservé aux comptes qui portent ce droit. Ce contrôle règle l'affichage ; la vraie barrière
 * est dans l'API, qui revérifie chaque droit (SecurityUtils.exigerDroit).
 */
export const droitGarde = (droit: string): CanActivateFn => () => {
  const auth = inject(AuthService);
  return auth.a(droit) || inject(Router).parseUrl(auth.accueil());
};

/**
 * Réservé au personnel de l'agence : un propriétaire est ramené à son espace (§ 4.9), qui lui montre
 * ses biens sans les données personnelles des locataires. L'API refuse de son côté ces écrans.
 */
export const personnelGarde: CanActivateFn = () => {
  const auth = inject(AuthService);
  return !auth.estProprietaire() || inject(Router).parseUrl('/espace');
};
