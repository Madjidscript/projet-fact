import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Assistant } from '../composants/assistant';
import { Icone } from '../composants/icone';
import { Logo } from '../composants/logo';
import { ListeAlertes } from '../composants/liste-alertes';
import { PanneauLateral } from '../composants/panneau-lateral';
import { AlertesService } from '../noyau/alertes.service';
import { InstallationService } from '../noyau/installation.service';
import { TerrainService } from '../noyau/terrain.service';
import { AuthService } from '../noyau/auth.service';
import { DROITS } from '../noyau/modeles';
import { ThemeService } from '../noyau/theme.service';

interface Rubrique {
  chemin: string;
  libelle: string;
  /** Libellé court de la barre basse du téléphone, si le libellé ne tient pas. */
  court?: string;
  icone: string;
  exact?: boolean;
  /** Le droit qu'il faut porter pour voir la rubrique ; absent, tout compte connecté la voit. */
  droit?: string;
  /** Ou l'un de ces droits. */
  droits?: string[];
  /** Montrée au propriétaire, qui ne voit que ces rubriques ; « seul » : à lui seulement. */
  proprietaire?: 'aussi' | 'seul';
}

/**
 * La coque de l'application (§ 5.3 et § 5.4, « Menu latéral et barre basse ») :
 *  - ordinateur (1200 px et plus) : menu latéral fixe ;
 *  - tablette (768 à 1199 px) : menu réduit à ses pictogrammes, dépliable ;
 *  - téléphone (moins de 768 px) : barre basse de quatre rubriques ; « Plus » ouvre le reste,
 *    le compte, le thème et la déconnexion dans un panneau qui glisse depuis le bas.
 * Chaque profil ne voit que ses rubriques (§ 2.2) ; elles s'ajoutent au fil des étapes.
 */
@Component({
  selector: 'fact-coque',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icone, Logo, PanneauLateral, ListeAlertes, Assistant],
  template: `
    <a class="evitement" href="#contenu">Aller au contenu</a>

    <nav class="menu" [class.deplie]="deplie()" aria-label="Navigation principale">
      <div class="tete">
        <a [routerLink]="auth.accueil()" class="marque" aria-label="FACT Immobilier, accueil">
          <fact-logo variante="sombre" [largeur]="150" [anime]="false" />
        </a>
        <button type="button" class="replier" (click)="deplie.set(!deplie())"
                [attr.aria-label]="deplie() ? 'Réduire le menu' : 'Déplier le menu'" [attr.aria-expanded]="deplie()">
          <fact-icone [nom]="deplie() ? 'gauche' : 'menu'" />
        </button>
      </div>

      <ul>
        @for (r of rubriques(); track r.chemin; let i = $index) {
          <li [class.secondaire]="i >= 4">
            <a class="lien" [routerLink]="r.chemin" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: !!r.exact }"
               ariaCurrentWhenActive="page" [attr.title]="r.libelle">
              <fact-icone [nom]="r.icone" [taille]="22" />
              <span class="libelle long">{{ r.libelle }}</span>
              <span class="libelle court">{{ r.court ?? r.libelle }}</span>
            </a>
          </li>
        }
      </ul>

      <button type="button" class="theme plus" (click)="feuille.set(!feuille())" [attr.aria-expanded]="feuille()"
              aria-controls="feuille-plus" aria-label="Plus de rubriques et votre compte">
        <span class="avatar petit" aria-hidden="true">{{ initiales() }}</span>
        @if (alertes.compte().nonLues) { <span class="compteur sur-plus" [class.critique]="alertes.compte().critiques">{{ badge() }}</span> }
        <span class="libelle">Plus</span>
      </button>

      <div class="moi">
        <button type="button" class="theme cloche" [class.sonne]="alertes.nouvelle()" (click)="alertes.ouvrir()"
                [attr.aria-label]="'Alertes' + (alertes.compte().nonLues ? ', ' + alertes.compte().nonLues + ' non lue(s)' : '')" title="Alertes">
          <span class="porte-cloche"><fact-icone nom="cloche" [taille]="22" />
            @if (alertes.compte().nonLues) { <span class="compteur" [class.critique]="alertes.compte().critiques">{{ badge() }}</span> }
          </span>
          <span class="libelle">Alertes</span>
        </button>
        <a class="lien compte" routerLink="/mot-de-passe" [attr.title]="'Changer de mot de passe'">
          <span class="avatar" aria-hidden="true">{{ initiales() }}</span>
          <span class="libelle identite"><strong>{{ auth.compte()?.nom }}</strong><small>{{ auth.compte()?.libelleProfil }}</small></span>
        </a>
        <button type="button" class="theme sortie" (click)="auth.deconnexion()" aria-label="Se déconnecter" title="Se déconnecter">
          <fact-icone nom="sortir" [taille]="22" />
          <span class="libelle">Se déconnecter</span>
        </button>
      </div>

      <button type="button" class="theme" (click)="theme.basculer()"
              [attr.aria-label]="theme.theme() === 'clair' ? 'Passer au thème sombre' : 'Passer au thème clair'">
        <fact-icone [nom]="theme.theme() === 'clair' ? 'lune' : 'soleil'" [taille]="22" />
        <span class="libelle">{{ theme.theme() === 'clair' ? 'Thème sombre' : 'Thème clair' }}</span>
      </button>
    </nav>

    @if (feuille()) {
      <div class="voile-feuille" (click)="feuille.set(false)" aria-hidden="true"></div>
      <div class="feuille" id="feuille-plus" role="dialog" aria-label="Plus de rubriques">
        <span class="poignee" aria-hidden="true"></span>
        <div class="qui-feuille">
          <span class="avatar" aria-hidden="true">{{ initiales() }}</span>
          <div><strong>{{ auth.compte()?.nom }}</strong><small>{{ auth.compte()?.libelleProfil }}</small></div>
        </div>
        <ul class="liste-feuille">
          <li><button type="button" (click)="feuille.set(false); alertes.ouvrir()"><fact-icone nom="cloche" /> Alertes
            @if (alertes.compte().nonLues) { <span class="compteur en-ligne" [class.critique]="alertes.compte().critiques">{{ badge() }}</span> }</button></li>
          @for (r of rubriques().slice(4); track r.chemin) {
            <li><a [routerLink]="r.chemin" routerLinkActive="actif" (click)="feuille.set(false)"><fact-icone [nom]="r.icone" /> {{ r.libelle }}</a></li>
          }
          @if (installation.installable() && !installation.installee()) {
            <li><button type="button" (click)="feuille.set(false); installation.installer()"><fact-icone nom="telecharger" /> Installer l'application</button></li>
          }
          <li><a routerLink="/mot-de-passe" (click)="feuille.set(false)"><fact-icone nom="cle" /> Changer de mot de passe</a></li>
          <li><button type="button" (click)="theme.basculer()"><fact-icone [nom]="theme.theme() === 'clair' ? 'lune' : 'soleil'" />
            {{ theme.theme() === 'clair' ? 'Thème sombre' : 'Thème clair' }}</button></li>
          <li><button type="button" (click)="feuille.set(false); auth.deconnexion()"><fact-icone nom="sortir" /> Se déconnecter</button></li>
        </ul>
      </div>
    }

    @if (!terrain.enLigne()) {
      <p class="hors-ligne" role="status"><fact-icone nom="hors_ligne" [taille]="18" /> Hors connexion : les écrans affichent ce qui était déjà chargé.</p>
    } @else if (terrain.enAttente()) {
      <a class="a-envoyer" routerLink="/terrain">
        @if (terrain.synchronisation()) { <span class="tourne-petit" aria-hidden="true"></span> } @else { <fact-icone nom="synchro" [taille]="16" /> }
        {{ terrain.enAttente() }} saisie(s) à envoyer
      </a>
    }

    <div class="page" id="contenu" tabindex="-1">
      <router-outlet />
    </div>

    <fact-panneau-lateral [page]="true" titre="Alertes" [ouvert]="alertes.ouvert()" (fermer)="alertes.ouvert.set(false)">
      @if (alertes.ouvert()) { <fact-liste-alertes (suivi)="alertes.ouvert.set(false)" /> }
    </fact-panneau-lateral>

    <!-- L'assistant IA : le robot flottant, sur toutes les pages. -->
    <fact-assistant />`,
  styles: [`
    :host { display: block; min-height: 100dvh; }
    .evitement { position: fixed; left: 12px; top: -60px; z-index: 3000; padding: 10px 16px; border-radius: var(--rayon-2);
                 background: var(--ocre); color: #fff; font-weight: 600; transition: top var(--courte) var(--courbe); }
    .evitement:focus { top: 12px; }

    /* ------------------------------------------------ téléphone : barre basse */
    .menu { position: fixed; z-index: 1000; left: 0; right: 0; bottom: 0; height: calc(var(--barre-basse) + env(safe-area-inset-bottom));
            padding-bottom: env(safe-area-inset-bottom); display: flex; background: var(--nuit-3); color: #c5d0de;
            border-top: 1px solid rgba(255, 255, 255, .08); }
    .tete { display: none; }
    ul { list-style: none; margin: 0; padding: 0; display: flex; flex: 1; }
    li { flex: 1; }
    a.lien, .theme { position: relative; width: 100%; height: var(--barre-basse); display: grid; place-content: center; justify-items: center; gap: 3px;
            background: none; border: 0; color: inherit; font-size: 11.5px; font-weight: 500; text-decoration: none;
            transition: color var(--courte) var(--courbe), background var(--courte) var(--courbe); }
    a.actif { color: #fff; }
    /* Le repère de la rubrique active : un trait ocre qui s'étire. */
    a.lien::before { content: ''; position: absolute; top: 0; left: 50%; width: 34px; height: 3px; margin-left: -17px; border-radius: 0 0 3px 3px;
            background: var(--ocre); transform: scaleX(0); transition: transform var(--standard) var(--courbe); }
    a.actif::before { transform: scaleX(1); }
    a.actif fact-icone { color: var(--ocre); }
    /* Téléphone : quatre rubriques, puis « Plus » ; le reste passe dans la feuille. */
    li.secondaire, .moi, .menu > .theme:not(.plus), .libelle.long { display: none; }
    .plus { flex: 0 0 64px; width: auto; }
    .avatar.petit { width: 24px; height: 24px; font-size: 10px; }
    .voile-feuille { position: fixed; inset: 0; z-index: 1100; background: var(--voile); animation: fondu var(--standard) var(--courbe); }
    .feuille { position: fixed; z-index: 1200; left: 0; right: 0; bottom: 0; padding: 14px 16px calc(16px + env(safe-area-inset-bottom));
               border-radius: 20px 20px 0 0; background: var(--surface); color: var(--encre);
               animation: depuis-bas var(--standard) var(--courbe); }
    @keyframes depuis-bas { from { transform: translateY(100%); } }
    .feuille .poignee { display: block; width: 40px; height: 4px; margin: 0 auto 14px; border-radius: 2px; background: var(--filet-fort); }
    .qui-feuille { display: flex; align-items: center; gap: 12px; padding: 4px 4px 14px; border-bottom: 1px solid var(--filet); }
    .qui-feuille div { display: grid; }
    .qui-feuille small { color: var(--discret); }
    .liste-feuille { display: grid; gap: 2px; margin-top: 8px; }
    .liste-feuille li { flex: none; }
    .liste-feuille a, .liste-feuille button { display: flex; align-items: center; gap: 14px; width: 100%; min-height: 48px; padding: 0 10px;
               border: 0; border-radius: var(--rayon-2); background: none; color: var(--encre); font-size: 15px; text-align: left; text-decoration: none; }
    .liste-feuille a:hover, .liste-feuille button:hover { background: var(--surface-3); }
    .liste-feuille a.actif { color: var(--marque); font-weight: 600; background: var(--nuit-clair); }
    .moi { flex: none; }
    .moi .compte { width: 56px; }
    .moi .sortie, .menu > .theme { flex: 0 0 52px; }
    .moi .libelle, .menu > .theme .libelle { display: none; }
    .avatar { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; flex: none;
              background: var(--ocre); color: #fff; font: 700 12px var(--titre); }
    .page { padding-bottom: calc(var(--barre-basse) + env(safe-area-inset-bottom)); outline: none; }
    /* Hors connexion : un bandeau glisse depuis le haut (§ 6.2, « Synchronisation ») */
    .hors-ligne { position: sticky; top: 0; z-index: 950; display: flex; gap: 8px; align-items: center; margin: 0; padding: 9px 16px;
                  background: var(--attention-clair); color: var(--attention-texte); font-size: 13.5px; animation: depuis-haut var(--standard) var(--courbe) both; }
    .a-envoyer { position: fixed; z-index: 950; right: 14px; top: 12px; display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px;
                 border-radius: 18px; background: var(--ocre); color: #fff; font-size: 13px; font-weight: 600; text-decoration: none;
                 box-shadow: 0 8px 20px -10px rgba(0,0,0,.5); animation: depuis-haut var(--standard) var(--courbe) both; }
    .tourne-petit { width: 14px; height: 14px; border-radius: 50%; border: 2px solid #fff; border-color: #fff transparent #fff transparent; animation: tourne .8s linear infinite; }
    @keyframes depuis-haut { from { transform: translateY(-100%); opacity: 0; } }
    @media (min-width: 768px) { .hors-ligne { margin-left: 72px; } }
    @media (min-width: 1200px) { .hors-ligne { margin-left: var(--menu-largeur); } }
    /* Nombre d'alertes non lues : pastille ocre, rouge s'il y a une urgence */
    .compteur { min-width: 18px; height: 18px; padding: 0 5px; display: inline-grid; place-items: center; border-radius: 9px;
                background: var(--ocre); color: #fff; font: 700 10.5px/1 var(--texte); box-shadow: 0 0 0 2px var(--nuit-3); }
    .compteur.critique { background: var(--critique); }
    .compteur.sur-plus { position: absolute; top: 6px; left: calc(50% + 4px); }
    .compteur.en-ligne { margin-left: auto; box-shadow: none; }
    .porte-cloche { position: relative; display: inline-grid; }
    .porte-cloche .compteur { position: absolute; top: -7px; right: -10px; }
    .cloche.sonne fact-icone { animation: oscille .8s var(--courbe) 2; transform-origin: 50% 10%; }
    @keyframes oscille { 0%, 100% { transform: rotate(0); } 20% { transform: rotate(16deg); } 40% { transform: rotate(-13deg); }
                         60% { transform: rotate(8deg); } 80% { transform: rotate(-4deg); } }
    @media (prefers-reduced-motion: reduce) { .cloche.sonne fact-icone { animation: none; } }

    /* --------------------------------- tablette : menu réduit à ses pictogrammes */
    @media (min-width: 768px) {
      .menu { top: 0; right: auto; bottom: 0; height: auto; width: 72px; padding: 12px 10px; flex-direction: column; gap: 8px;
              border-top: 0; border-right: 1px solid rgba(255, 255, 255, .08); overflow: hidden;
              transition: width var(--standard) var(--courbe); }
      .menu.deplie { width: var(--menu-largeur); box-shadow: 12px 0 32px rgba(0, 0, 0, .25); }
      .tete { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 52px; margin-bottom: 8px; }
      .marque { display: none; align-items: center; gap: 10px; color: #fff; text-decoration: none; white-space: nowrap; }
      .menu.deplie .marque { display: flex; }
      .marque { padding: 10px 4px 6px; }
      .replier { width: 52px; height: 44px; flex: none; display: grid; place-items: center; border: 0; border-radius: var(--rayon-2);
                 background: none; color: inherit; }
      .replier:hover { background: rgba(255, 255, 255, .08); color: #fff; }
      /* Les rubriques défilent seules quand elles ne tiennent pas en hauteur ; le logo en haut,
         les alertes et le compte en bas restent visibles. */
      ul { flex-direction: column; gap: 4px; flex: 1 1 auto; min-height: 0; overflow-y: auto; overflow-x: hidden;
           overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: rgba(255, 255, 255, .28) transparent; }
      ul::-webkit-scrollbar { width: 6px; }
      ul::-webkit-scrollbar-thumb { border-radius: 3px; background: rgba(255, 255, 255, .28); }
      ul::-webkit-scrollbar-track { background: transparent; }
      li { flex: none; }
      a.lien, .theme { height: 46px; display: flex; align-items: center; justify-content: flex-start; gap: 14px; padding: 0 15px;
              border-radius: var(--rayon-2); font-size: 14.5px; white-space: nowrap; flex: none; width: 100%; }
      a.lien:hover, .theme:hover { background: rgba(255, 255, 255, .07); color: #fff; }
      a.actif { background: rgba(255, 255, 255, .1); }
      a.lien::before { top: 50%; left: 0; width: 3px; height: 22px; margin: -11px 0 0; border-radius: 0 3px 3px 0; transform: scaleY(0); }
      a.actif::before { transform: scaleY(1); }
      .libelle { opacity: 0; transition: opacity var(--courte) var(--courbe); }
      .menu.deplie .libelle { opacity: 1; }
      .plus, .voile-feuille, .feuille { display: none; }
      li.secondaire { display: block; }
      .libelle.court { display: none; }
      .libelle.long { display: inline; }
      .menu > .theme:not(.plus) { display: flex; }
      .moi { display: flex; flex-direction: column; gap: 4px; margin-top: auto; padding-top: 8px; border-top: 1px solid rgba(255, 255, 255, .08); }
      .moi .compte { width: 100%; padding: 0 11px; height: 54px; }
      .moi .sortie, .menu > .theme { flex: none; }
      .moi .libelle, .menu > .theme .libelle { display: block; }
      .identite { display: grid !important; line-height: 1.25; overflow: hidden; }
      .identite strong { color: #fff; font-weight: 600; overflow: hidden; text-overflow: ellipsis; }
      .identite small { font-size: 12px; color: #9fb0c6; }
      .page { padding-bottom: 0; margin-left: 72px; }
    }

    /* ------------------------------------------- ordinateur : menu latéral fixe */
    @media (min-width: 1200px) {
      .menu, .menu.deplie { width: var(--menu-largeur); box-shadow: none; }
      .marque { display: flex; }
      .replier { display: none; }
      .libelle { opacity: 1; }
      .page { margin-left: var(--menu-largeur); }
    }
  `],
})
export class Coque {
  protected readonly theme = inject(ThemeService);
  /** Sur tablette, le menu se déplie par-dessus la page. */
  protected readonly deplie = signal(false);
  /** Sur téléphone, la feuille « Plus ». */
  protected readonly feuille = signal(false);

  protected readonly auth = inject(AuthService);
  protected readonly alertes = inject(AlertesService);
  protected readonly terrain = inject(TerrainService);
  protected readonly installation = inject(InstallationService);
  /** « 9+ » au-delà de neuf : la pastille reste lisible. */
  protected readonly badge = computed(() => (this.alertes.compte().nonLues > 9 ? '9+' : String(this.alertes.compte().nonLues)));

  private readonly toutes: Rubrique[] = [
    { chemin: '/espace', libelle: 'Mon espace', court: 'Espace', icone: 'accueil', proprietaire: 'seul' },
    { chemin: '/', libelle: 'Tableau de bord', court: 'Accueil', icone: 'tableau', exact: true },
    { chemin: '/carte', libelle: 'Carte', icone: 'carte', droit: DROITS.patrimoineLire, proprietaire: 'aussi' },
    { chemin: '/patrimoine', libelle: 'Bâtiments', icone: 'batiment', droit: DROITS.patrimoineLire },
    { chemin: '/loyers', libelle: 'Loyers', icone: 'monnaie', droit: DROITS.locationLire },
    { chemin: '/travaux', libelle: 'Travaux', icone: 'outils', droit: DROITS.travauxLire },
    { chemin: '/terrain', libelle: 'Terrain', icone: 'cible', droit: DROITS.patrimoineEcrire },
    { chemin: '/tournees', libelle: 'Tournées', icone: 'carte', droits: [DROITS.locationEcrire, DROITS.affectationLire] },
    { chemin: '/portefeuilles', libelle: 'Portefeuilles', icone: 'comptes', droit: DROITS.affectationLire },
    { chemin: '/demandes', libelle: 'Demandes', icone: 'courrier', droits: [DROITS.demandeLire, DROITS.demandeEcrire, DROITS.annonceEcrire] },
    { chemin: '/annonces', libelle: 'Annonces', icone: 'annonce', droits: [DROITS.annonceEcrire, DROITS.annonceValider] },
    { chemin: '/rapports', libelle: 'Rapports', icone: 'rapport', droit: DROITS.tableauBord },
    { chemin: '/proprietaires', libelle: 'Propriétaires', court: 'Mandants', icone: 'utilisateur', droit: DROITS.patrimoineLire },
    { chemin: '/import', libelle: 'Import', icone: 'telecharger', droit: DROITS.parametresLire },
    { chemin: '/comptes', libelle: 'Comptes', icone: 'comptes', droit: DROITS.parametresLire },
    { chemin: '/journal', libelle: 'Journal', icone: 'journal', droit: DROITS.parametresLire },
    // À RETIRER À LA LIVRAISON : le guide des essais.
    { chemin: '/guide', libelle: 'Guide', icone: 'document', proprietaire: 'aussi' },
  ];

  /** Les rubriques que le compte connecté a le droit de voir. */
  protected readonly rubriques = computed(() => {
    const proprietaire = this.auth.estProprietaire();
    return this.toutes.filter((r) => (proprietaire ? !!r.proprietaire : r.proprietaire !== 'seul')
      && (!r.droit || this.auth.a(r.droit)) && (!r.droits || r.droits.some((d) => this.auth.a(d))));
  });

  protected readonly initiales = computed(() =>
    (this.auth.compte()?.nom ?? '').split(/\s+/).filter(Boolean).slice(0, 2).map((m) => m[0]!.toUpperCase()).join(''));
}
