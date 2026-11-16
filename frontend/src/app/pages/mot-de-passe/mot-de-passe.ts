import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Icone } from '../../composants/icone';
import { Logo } from '../../composants/logo';
import { ThemeService } from '../../noyau/theme.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';

/**
 * Changement du mot de passe : imposé à la première connexion (mot de passe provisoire),
 * libre ensuite. Les règles affichées sont celles que l'API applique (CompteService).
 */
@Component({
  selector: 'fact-mot-de-passe',
  imports: [FormsModule, Icone, Logo],
  templateUrl: './mot-de-passe.html',
  styleUrl: '../connexion/connexion.scss',
})
export class MotDePasse {
  private readonly router = inject(Router);
  private readonly avis = inject(AvisService);
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);

  protected readonly actuel = signal('');
  protected readonly nouveau = signal('');
  protected readonly confirmation = signal('');
  protected readonly visible = signal(false);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected readonly regles = computed(() => {
    const n = this.nouveau();
    const identifiant = this.auth.compte()?.identifiant.toLowerCase() ?? '';
    return [
      { texte: 'Au moins 10 caractères', remplie: n.length >= 10 },
      { texte: 'Des lettres et des chiffres', remplie: /\p{L}/u.test(n) && /\d/.test(n) },
      { texte: "Ne contient pas l'identifiant", remplie: n.length > 0 && !n.toLowerCase().includes(identifiant) },
      { texte: 'Les deux saisies sont identiques', remplie: n.length > 0 && n === this.confirmation() },
    ];
  });
  protected readonly pret = computed(() => this.actuel().length > 0 && this.regles().every((r) => r.remplie));

  protected async changer(): Promise<void> {
    if (!this.pret() || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      await this.auth.changerMotDePasse(this.actuel(), this.nouveau());
      this.avis.succes('Mot de passe changé.');
      await this.router.navigateByUrl(this.auth.accueil());
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected retour(): void {
    if (this.auth.compte()?.doitChanger) {
      this.auth.deconnexion();
    } else {
      this.router.navigateByUrl('/');
    }
  }
}
