import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Icone } from '../../composants/icone';
import { Logo } from '../../composants/logo';
import { ThemeService } from '../../noyau/theme.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';

/** Connexion par identifiant et mot de passe (§ 4.6). Le propriétaire saisit son e-mail ou son identifiant. */
@Component({
  selector: 'fact-connexion',
  imports: [FormsModule, Icone, Logo],
  templateUrl: './connexion.html',
  styleUrl: './connexion.scss',
})
export class Connexion {
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);

  protected identifiant = '';
  protected motDePasse = '';
  protected readonly visible = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  /** Incrémenté à chaque refus : rejoue la secousse du formulaire. */
  protected readonly secousse = signal(0);

  protected async connecter(): Promise<void> {
    if (this.occupe()) return;
    if (!this.identifiant.trim() || !this.motDePasse) {
      this.refuser('Saisissez votre identifiant et votre mot de passe.');
      return;
    }
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      await this.auth.connexion(this.identifiant, this.motDePasse);
      await this.router.navigateByUrl(this.auth.accueil());
    } catch (e) {
      this.refuser(messageErreur(e));
      this.motDePasse = '';
    } finally {
      this.occupe.set(false);
    }
  }

  private refuser(message: string): void {
    this.erreur.set(message);
    this.secousse.update((n) => n + 1);
  }
}
