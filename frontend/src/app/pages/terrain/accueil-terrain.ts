import { Component, OnInit, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../../composants/icone';
import { AuthService } from '../../noyau/auth.service';
import { MOTIFS, TerrainService } from '../../noyau/terrain.service';
import { EnteteTerrain } from './entete-terrain';

/**
 * L'accueil de l'application de terrain (§ 4.6) : la tournée du jour en tête, puis les quatre
 * gestes de l'agent : encaisser un loyer, faire un état des lieux, recenser un bâtiment.
 */
@Component({
  selector: 'fact-accueil-terrain',
  imports: [RouterLink, Icone, EnteteTerrain],
  template: `
    <div class="tr">
      <fact-entete-terrain titre="Terrain" />

      <section class="at-salut">
        <p>{{ jour }}</p>
        <h1>Bonjour {{ prenom() }}</h1>
      </section>

      @if (prep(); as p) {
        @if (p.tournee; as t) {
          <a class="carte at-tournee" routerLink="/terrain/tournee">
            <svg class="at-anneau" viewBox="0 0 64 64" aria-hidden="true">
              <circle cx="32" cy="32" r="27" class="fond" />
              <circle cx="32" cy="32" r="27" class="plein" [style.stroke-dashoffset]="170 - 170 * (t.etapes ? t.pointees / t.etapes : 0)" />
            </svg>
            <span class="at-compte">{{ t.pointees }}<small>/{{ t.etapes }}</small></span>
            <span class="at-texte">
              <strong>Tournée du jour</strong>
              <small>{{ t.statut === 'TERMINEE' ? 'Terminée, compte rendu envoyé' : t.pointees === t.etapes ? 'Toutes les étapes sont faites : écrivez le compte rendu'
                : 'Prochaine étape : ' + prochaine() }}</small>
            </span>
            <fact-icone nom="droite" [taille]="20" />
          </a>
        } @else {
          <a class="carte at-tournee vide" routerLink="/terrain/tournee">
            <span class="at-pic"><fact-icone nom="carte" [taille]="24" /></span>
            <span class="at-texte"><strong>Pas de tournée prévue aujourd'hui</strong><small>Préparez la vôtre à partir des bâtiments à visiter</small></span>
            <fact-icone nom="droite" [taille]="20" />
          </a>
        }

        <nav class="at-gestes" aria-label="Que voulez-vous faire ?">
          @if (p.droits?.encaisser) {
            <a class="carte at-geste" routerLink="/terrain/encaissement" style="--rang: 0">
              <span class="at-pic bon"><fact-icone nom="monnaie" [taille]="26" /></span><strong>Encaisser un loyer</strong><small>Reçu envoyé au locataire</small>
            </a>
          }
          @if (p.droits?.etatDesLieux) {
            <a class="carte at-geste" routerLink="/terrain/etat-des-lieux" style="--rang: 1">
              <span class="at-pic"><fact-icone nom="document" [taille]="26" /></span><strong>État des lieux</strong><small>Pièce par pièce, signé sur place</small>
            </a>
          }
          @if (p.droits?.recenser !== false) {
            <a class="carte at-geste" routerLink="/terrain/recensement" style="--rang: 2">
              <span class="at-pic"><fact-icone nom="batiment" [taille]="26" /></span><strong>Recenser un bâtiment</strong><small>Position, photos, lots</small>
            </a>
          }
          <a class="carte at-geste" routerLink="/terrain/tournee" style="--rang: 3">
            <span class="at-pic"><fact-icone nom="cible" [taille]="26" /></span><strong>Ma tournée</strong><small>Itinéraire et pointage</small>
          </a>
        </nav>
        <!-- À RETIRER À LA LIVRAISON : le guide des essais. -->
        <a class="at-guide" href="guide-essais/index.html#terrain" target="_blank" rel="noopener"><fact-icone nom="document" [taille]="16" /> Guide d'utilisation</a>
        <p class="at-maj">Données du téléphone mises à jour le {{ p.le.slice(8, 10) }}/{{ p.le.slice(5, 7) }} à {{ heure(p.le) }}</p>
      } @else {
        <p class="tr-aide at-maj">Chargement…</p>
      }
    </div>
  `,
  styles: [`
    .at-salut { padding: 18px 2px 4px; animation: montee var(--longue) var(--courbe) both; }
    .at-salut p { margin: 0; font-size: 13px; color: var(--discret); }
    .at-salut p::first-letter { text-transform: uppercase; }
    .at-salut h1 { margin: 2px 0 0; font-size: 26px; color: var(--marque); }
    .at-tournee { display: flex; align-items: center; gap: 14px; margin-top: 14px; color: var(--encre); text-decoration: none; position: relative;
                  border-left: 4px solid var(--ocre); animation: montee var(--standard) var(--courbe) both; }
    .at-tournee.vide { border-left-color: var(--filet-fort); }
    .at-anneau { width: 64px; height: 64px; flex: none; transform: rotate(-90deg); }
    .at-anneau circle { fill: none; stroke-width: 7; }
    .at-anneau .fond { stroke: var(--surface-3); }
    .at-anneau .plein { stroke: var(--ocre); stroke-linecap: round; stroke-dasharray: 170; transition: stroke-dashoffset 900ms var(--courbe);
                        animation: at-remplit 1100ms var(--courbe) both; }
    @keyframes at-remplit { from { stroke-dashoffset: 170; } }
    .at-compte { position: absolute; left: 16px; width: 64px; text-align: center; font: 700 19px var(--titre); color: var(--marque); }
    .at-compte small { font-size: 12px; color: var(--discret); }
    .at-texte { flex: 1; display: grid; gap: 2px; min-width: 0; }
    .at-texte small { color: var(--encre-2); font-size: 13px; }
    .at-gestes { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 14px; }
    .at-geste { display: grid; gap: 4px; align-content: start; min-height: 132px; color: var(--encre); text-decoration: none;
                animation: montee var(--standard) var(--courbe) both; animation-delay: calc(var(--rang) * 70ms + 120ms);
                transition: transform var(--courte) var(--courbe); }
    .at-geste:active { transform: scale(.97); }
    .at-geste strong { font-size: 15px; margin-top: 6px; }
    .at-geste small { color: var(--discret); font-size: 12.5px; }
    .at-pic { width: 48px; height: 48px; flex: none; display: grid; place-items: center; border-radius: 14px; background: var(--nuit-clair); color: var(--marque); }
    .at-pic.bon { background: var(--bon-clair); color: var(--bon-texte); }
    .at-guide { display: flex; justify-content: center; align-items: center; gap: 6px; margin-top: 16px; font-weight: 600; }
    .at-maj { margin: 18px 0 0; text-align: center; font-size: 12px; color: var(--discret); }
    @media (prefers-reduced-motion: reduce) { .at-anneau .plein { animation: none; } }
  `],
})
export class AccueilTerrain implements OnInit {
  protected readonly terrain = inject(TerrainService);
  private readonly auth = inject(AuthService);
  protected readonly prep = this.terrain.preparation;
  protected readonly jour = new Date().toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
  protected readonly prenom = computed(() => {
    const n = (this.auth.compte()?.nom ?? this.terrain.agentRetenu()?.nom ?? '').replace(/^ESSAI\s+/, '');
    return n.split(/\s+/)[0] ?? '';
  });
  protected readonly prochaine = computed(() => {
    const e = this.prep()?.tournee?.etapesDetail.find((x) => !x.pointeeLe);
    return e ? `${e.batiment} (${MOTIFS[e.motif] ?? e.motif})` : '';
  });

  ngOnInit(): void {
    this.terrain.preparer();
  }

  protected heure(iso: string): string {
    return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
}
