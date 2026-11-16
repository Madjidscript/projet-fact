import { Injectable, inject, signal } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { filter } from 'rxjs';

interface InvitationInstallation extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/**
 * L'application installée sur le téléphone (§ 4.6) :
 *  - « Installer l'application » : l'invitation du navigateur est gardée pour être proposée au bon moment ;
 *    sur iPhone, où elle n'existe pas, on explique le geste (Partager, puis « Sur l'écran d'accueil ») ;
 *  - mise à jour automatique : une nouvelle version téléchargée s'applique au changement de page suivant,
 *    sans que l'agent ait rien à faire et sans couper une saisie en cours.
 */
@Injectable({ providedIn: 'root' })
export class InstallationService {
  private readonly maj = inject(SwUpdate);
  private readonly router = inject(Router);

  private invitation: InvitationInstallation | null = null;
  readonly installable = signal(false);
  readonly installee = signal(this.modeApplication());
  readonly iphone = signal(/iphone|ipad|ipod/i.test(navigator.userAgent) && !this.modeApplication());
  private versionPrete = false;

  constructor() {
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      this.invitation = e as InvitationInstallation;
      this.installable.set(true);
    });
    window.addEventListener('appinstalled', () => {
      this.installee.set(true);
      this.installable.set(false);
      this.invitation = null;
    });
    if (this.maj.isEnabled) {
      this.maj.versionUpdates.pipe(filter((v) => v.type === 'VERSION_READY')).subscribe(() => (this.versionPrete = true));
      this.maj.unrecoverable.subscribe(() => document.location.reload());
      // On vérifie au démarrage, puis toutes les trente minutes.
      setInterval(() => this.maj.checkForUpdate().catch(() => undefined), 30 * 60_000);
      this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
        if (this.versionPrete) document.location.reload();
      });
    }
  }

  async installer(): Promise<boolean> {
    if (!this.invitation) return false;
    await this.invitation.prompt();
    const choix = await this.invitation.userChoice;
    this.invitation = null;
    this.installable.set(false);
    return choix.outcome === 'accepted';
  }

  private modeApplication(): boolean {
    return window.matchMedia?.('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  }
}
