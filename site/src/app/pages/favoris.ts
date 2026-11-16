import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../composants/icone';
import { VignetteBien } from '../composants/vignette-bien';
import { Vignette } from '../noyau/api';
import { CommunesService } from '../noyau/communes.service';
import { montant, prix } from '../noyau/donnees';
import { FavorisService } from '../noyau/favoris.service';
import { MOUVEMENT } from '../noyau/mouvement';

/**
 * Les favoris du visiteur (§ 3.7), gardés sur son appareil, et la comparaison côte à côte de
 * trois biens au plus.
 */
@Component({
  selector: 'site-favoris',
  imports: [RouterLink, Icone, VignetteBien, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>Mes favoris</span></nav>
        <h1>Mes favoris</h1>
        <p>Les biens que vous avez gardés, sur cet appareil. Cochez-en jusqu’à trois pour les comparer côte à côte.</p>
      </div>
    </section>

    <section class="conteneur zone">
      @if (favoris.liste().length) {
        @if (comparaison().length) {
          <div class="comparer" siteRevele aria-labelledby="titre-comparaison">
            <div class="tete">
              <h2 id="titre-comparaison">Comparaison ({{ comparaison().length }} / 3)</h2>
              <button type="button" class="btn petit discret" (click)="favoris.compares.set([])">Vider la comparaison</button>
            </div>
            <div class="table-defile">
              <table>
                <thead><tr><th scope="col"><span class="sr">Critère</span></th>
                  @for (b of comparaison(); track b.slug) { <th scope="col"><a [routerLink]="['/biens', b.slug]">{{ b.titre }}</a></th> }</tr></thead>
                <tbody>
                  @for (l of lignes; track l.cle) {
                    <tr><th scope="row">{{ l.libelle }}</th>
                      @for (b of comparaison(); track b.slug) { <td [class.meilleur]="meilleur(l.cle, b)">{{ valeur(l.cle, b) }}</td> }</tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
        @if (avis()) { <p class="avis" role="status">{{ avis() }}</p> }
        <ul class="grille-biens" siteLot>
          @for (b of favoris.liste(); track b.slug) {
            <li class="carte-favori" siteInclinaison>
              <site-vignette-bien [bien]="b" [transition]="false" />
              <label class="case comparer-case"><input type="checkbox" [checked]="favoris.compares().includes(b.slug)" (change)="comparer(b.slug, $event)"> Comparer</label>
            </li>
          }
        </ul>
      } @else {
        <div class="vide" siteRevele>
          <span class="rond"><fact-icone nom="coeur" [taille]="34" /></span>
          <h2>Aucun favori pour le moment</h2>
          <p>Touchez le cœur d’un bien pour le garder ici. Vos favoris restent sur cet appareil, sans créer de compte.</p>
          <a class="btn accent grand" routerLink="/louer">Voir les biens à louer</a>
        </div>
      }
    </section>`,
  styles: [`
    :host { display: block; }
    .zone { padding-top: 32px; padding-bottom: 24px; }
    .carte-favori { display: grid; gap: 8px; }
    .comparer-case { padding-left: 6px; }
    .avis { margin: 0 0 12px; color: var(--attention-texte); }
    .comparer { margin-bottom: 28px; padding: 18px; border: 1px solid var(--filet); border-radius: 20px; background: var(--surface); }
    .tete { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 12px; }
    h2 { margin: 0; font: 700 21px var(--titre); color: var(--nuit); }
    .table-defile { overflow-x: auto; }
    table { width: 100%; min-width: 520px; border-collapse: collapse; font-size: 14.5px; }
    th, td { padding: 10px 12px; border-bottom: 1px solid var(--filet); text-align: left; vertical-align: top; }
    thead th { font-weight: 600; }
    thead a { color: var(--nuit); }
    tbody th { color: var(--discret); font-weight: 500; white-space: nowrap; }
    td.meilleur { color: var(--bon-texte); font-weight: 600; }
    .vide { display: grid; justify-items: center; gap: 10px; padding: 48px 20px; text-align: center; border: 1px dashed var(--filet-fort);
            border-radius: 20px; background: var(--surface-2); }
    .vide h2 { margin-top: 6px; }
    .vide p { max-width: 52ch; margin: 0; color: var(--encre-2); }
    .rond { width: 72px; height: 72px; display: grid; place-items: center; border-radius: 50%; background: var(--critique-clair); color: var(--critique); }
  `],
})
export class Favoris {
  protected readonly favoris = inject(FavorisService);
  private readonly ref = inject(CommunesService);
  protected readonly avis = signal<string | null>(null);
  protected readonly comparaison = computed(() =>
    this.favoris.compares().map((s) => this.favoris.liste().find((v) => v.slug === s)).filter((v): v is Vignette => !!v));
  protected readonly lignes = [
    { cle: 'prix', libelle: 'Prix' }, { cle: 'charges', libelle: 'Charges' }, { cle: 'type', libelle: 'Type' },
    { cle: 'lieu', libelle: 'Quartier' }, { cle: 'surface', libelle: 'Surface' }, { cle: 'pieces', libelle: 'Pièces' },
    { cle: 'chambres', libelle: 'Chambres' }, { cle: 'sallesEau', libelle: 'Salles d’eau' }, { cle: 'prixM2', libelle: 'Prix au m²' },
    { cle: 'equipements', libelle: 'Équipements' },
  ];

  constructor() {
    this.ref.assurer();
  }

  protected comparer(slug: string, e: Event): void {
    if (!this.favoris.comparer(slug)) {
      (e.target as HTMLInputElement).checked = false;
      this.avis.set('Vous comparez déjà trois biens : décochez-en un pour en ajouter un autre.');
      setTimeout(() => this.avis.set(null), 4000);
    }
  }

  protected valeur(cle: string, b: Vignette): string {
    switch (cle) {
      case 'prix': return prix(b);
      case 'charges': return b.charges ? montant(b.charges) + ' FCFA' : '-';
      case 'type': return b.libelleType;
      case 'lieu': return (b.quartier ? b.quartier + ', ' : '') + b.commune;
      case 'surface': return b.surface ? b.surface + ' m²' : '-';
      case 'pieces': return b.pieces ? String(b.pieces) : '-';
      case 'chambres': return b.chambres ? String(b.chambres) : '-';
      case 'sallesEau': return b.sallesEau ? String(b.sallesEau) : '-';
      case 'prixM2': return b.surface ? montant(b.prix / b.surface) + ' FCFA' : '-';
      case 'equipements': return b.equipements.length ? b.equipements.map((c) => this.ref.libelleEquipement(c)).join(', ') : '-';
      default: return '-';
    }
  }

  /** Met en valeur le plus avantageux : prix le plus bas, surface et pièces les plus grandes. */
  protected meilleur(cle: string, b: Vignette): boolean {
    const l = this.comparaison();
    if (l.length < 2) return false;
    const n = (v: Vignette): number | null => {
      switch (cle) {
        case 'prix': return -v.prix;
        case 'prixM2': return v.surface ? -v.prix / v.surface : null;
        case 'surface': return v.surface;
        case 'pieces': return v.pieces;
        case 'chambres': return v.chambres;
        default: return null;
      }
    };
    const valeurs = l.map(n).filter((x): x is number => x !== null);
    const mien = n(b);
    return mien !== null && valeurs.length > 1 && mien === Math.max(...valeurs) && valeurs.filter((x) => x === mien).length === 1;
  }
}
