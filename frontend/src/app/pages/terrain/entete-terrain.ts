import { Component, ViewEncapsulation, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../../composants/icone';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { InstallationService } from '../../noyau/installation.service';
import { TerrainService } from '../../noyau/terrain.service';

/**
 * L'en-tête commun des écrans de terrain : retour, titre, agent, file d'envoi (avec la liste des
 * saisies en attente), bandeau hors connexion et proposition d'installer l'application.
 * Encapsulation désactivée : il porte les styles « tr- » partagés par tous les écrans de terrain.
 */
@Component({
  selector: 'fact-entete-terrain',
  imports: [RouterLink, Icone],
  encapsulation: ViewEncapsulation.None,
  styleUrl: './terrain.scss',
  template: `
    <header class="tr-tete">
      @if (retour()) {
        <a class="tr-retour" [routerLink]="retour()" aria-label="Retour"><fact-icone nom="gauche" [taille]="20" /></a>
      } @else if (auth.connecte()) {
        <a class="tr-retour" routerLink="/" aria-label="Retour à l'application"><fact-icone nom="gauche" [taille]="20" /></a>
      }
      <div class="tr-titre">
        <img src="icones/icone-192.png" alt="" width="34" height="34">
        <div><strong>{{ titre() }}</strong><small>{{ agent() || 'Agent de terrain' }}</small></div>
      </div>
      <button type="button" class="tr-file" [class.vide]="!terrain.envois().length" (click)="voir.set(!voir())"
              [attr.aria-label]="terrain.enAttente() + ' saisie(s) en attente d\\'envoi'" [attr.aria-expanded]="voir()">
        @if (terrain.synchronisation()) { <span class="tr-tourne" aria-hidden="true"></span> }
        @else if (terrain.confirme()) { <fact-icone nom="coche" [taille]="18" /> }
        @else { <fact-icone nom="synchro" [taille]="18" /> }
        <span>{{ terrain.enAttente() }}</span>
      </button>
    </header>

    @if (!terrain.enLigne()) {
      <p class="tr-hors-ligne" role="status"><fact-icone nom="hors_ligne" [taille]="18" /> Hors connexion : vos saisies restent dans le téléphone et partiront au retour du réseau.</p>
    } @else if (!auth.connecte() && terrain.enAttente()) {
      <p class="tr-hors-ligne info" role="status"><fact-icone nom="info" [taille]="18" /> Réseau revenu : <a routerLink="/connexion">reconnectez-vous</a> pour envoyer vos saisies.</p>
    }

    @if (installation.installable() && !installation.installee()) {
      <div class="tr-installer">
        <fact-icone nom="telecharger" [taille]="20" />
        <span>Installez l'application : elle s'ouvrira en plein écran, même sans réseau.</span>
        <button type="button" class="btn petit principal" (click)="installer()">Installer</button>
      </div>
    } @else if (installation.iphone() && !installation.installee()) {
      <p class="tr-astuce">Sur iPhone : touchez <strong>Partager</strong> puis <strong>« Sur l'écran d'accueil »</strong> pour installer l'application.</p>
    }

    @if (voir()) {
      <section class="tr-envois carte" aria-label="Envois">
        <h2>Envois</h2>
        @for (e of terrain.envois(); track e.cle) {
          <div class="tr-envoi" [class.refuse]="e.etat === 'refuse'">
            <div>
              <strong>{{ terrain.libelle(e) }}</strong>
              <small>{{ terrain.detail(e) }} · saisi le {{ e.creeLe.slice(8, 10) }}/{{ e.creeLe.slice(5, 7) }} à {{ e.creeLe.slice(11, 16) }}</small>
              @if (e.etat === 'refuse') { <small class="tr-motif">Refusé : {{ e.erreur }}</small> }
              @else if (auth.compte() && e.identifiant !== auth.compte()!.identifiant) { <small>Attend la connexion de {{ e.identifiant }}</small> }
              @else { <small>En attente d'envoi{{ e.essais ? ' · ' + e.essais + ' essai(s)' : '' }}</small> }
            </div>
            <div class="tr-envoi-actions">
              @if (e.etat === 'refuse') { <button type="button" class="btn petit" (click)="terrain.reessayer(e.cle)">Réessayer</button> }
              <button type="button" class="btn discret petit" (click)="terrain.supprimer(e.cle)" [attr.aria-label]="'Supprimer ' + terrain.libelle(e)">Supprimer</button>
            </div>
          </div>
        } @empty {
          <p class="tr-vide"><fact-icone nom="coche" [taille]="18" /> Tout est envoyé.</p>
        }
        @if (terrain.enAttente() && terrain.enLigne() && auth.connecte()) {
          <button type="button" class="btn petit" (click)="terrain.synchroniser()" [disabled]="terrain.synchronisation()">Envoyer maintenant</button>
        }
      </section>
    }
  `,
})
export class EnteteTerrain {
  protected readonly terrain = inject(TerrainService);
  protected readonly auth = inject(AuthService);
  protected readonly installation = inject(InstallationService);
  private readonly avis = inject(AvisService);

  readonly titre = input('Terrain');
  readonly retour = input<string | null>(null);
  protected readonly voir = signal(false);
  protected readonly agent = computed(() => this.auth.compte()?.nom ?? this.terrain.agentRetenu()?.nom ?? '');

  protected async installer(): Promise<void> {
    if (await this.installation.installer()) this.avis.succes('Application installée : retrouvez l\'icône FACT sur votre écran d\'accueil.');
  }
}
