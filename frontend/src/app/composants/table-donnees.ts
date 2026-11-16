import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import { date, fcfa, nombre, pourcentage, surface } from '../noyau/format';
import { tonStatut } from '../noyau/statuts';
import { Icone } from './icone';

/** Une colonne du tableau : la clé lue dans chaque ligne, son libellé, son format. */
export interface Colonne {
  cle: string;
  libelle: string;
  format?: 'texte' | 'entier' | 'fcfa' | 'm2' | 'pct' | 'date' | 'statut' | 'reference';
  /** Pour un statut : la clé de la ligne qui porte son libellé (« Occupé » pour OCCUPE). */
  cleLibelle?: string;
}

export type Ligne = Record<string, unknown>;

const NUMERIQUES = ['entier', 'fcfa', 'm2', 'pct'];
const sansAccent = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Tableau de données (§ 5.4) : tri d'un clic sur l'en-tête, recherche, pagination, export,
 * ligne cliquable. Les nombres sont alignés à droite, en chasse fixe. Squelettes pendant
 * le chargement (§ 6.3).
 */
@Component({
  selector: 'fact-table-donnees',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (recherche() || exportable()) {
      <div class="outils">
        @if (recherche()) {
          <label class="cherche">
            <fact-icone nom="recherche" [taille]="18" />
            <input type="search" placeholder="Rechercher…" [value]="texte()" (input)="chercher($any($event.target).value)"
                   aria-label="Rechercher dans le tableau">
          </label>
        }
        <span class="compte donnee" aria-live="polite">{{ retenues().length }} sur {{ lignes().length }}</span>
        @if (exportable()) {
          <button type="button" class="btn petit" (click)="exporter()" [disabled]="!retenues().length">
            <fact-icone nom="telecharger" [taille]="16" /> Exporter
          </button>
        }
      </div>
    }
    <div class="defile">
      <table>
        <caption class="sr">{{ titre() }}</caption>
        <thead><tr>
          @for (c of colonnes(); track c.cle) {
            <th scope="col" [class.n]="num(c)" [attr.aria-sort]="tri() === c.cle ? (desc() ? 'descending' : 'ascending') : null">
              <button type="button" (click)="trier(c.cle)">
                {{ c.libelle }}<span class="sens" aria-hidden="true">{{ tri() === c.cle ? (desc() ? '▼' : '▲') : '' }}</span>
              </button>
            </th>
          }
          @if (cliquable()) { <th class="fleche"><span class="sr">Ouvrir</span></th> }
        </tr></thead>
        <tbody>
          @if (chargement()) {
            @for (i of [1, 2, 3, 4, 5]; track i) {
              <tr class="squelette">@for (c of colonnes(); track c.cle) { <td><span></span></td> } @if (cliquable()) { <td></td> }</tr>
            }
          } @else {
            @for (l of visibles(); track cleLigne() ? l[cleLigne()!] : $index) {
              <tr [class.cliquable]="cliquable()" [class.modifiee]="cleLigne() && l[cleLigne()!] === surbrillance()"
                  [attr.tabindex]="cliquable() ? 0 : null" (click)="ouvrir(l)" (keydown.enter)="ouvrir(l)">
                @for (c of colonnes(); track c.cle) {
                  <td [class.n]="num(c)" [class.donnee]="num(c) || c.format === 'reference' || c.format === 'date'">
                    @if (c.format === 'statut' && l[c.cle]) {
                      <span class="pastille" [class]="ton(l[c.cle])">{{ c.cleLibelle ? l[c.cleLibelle] : l[c.cle] }}</span>
                    } @else { {{ f(l[c.cle], c.format) }} }
                  </td>
                }
                @if (cliquable()) { <td class="fleche"><fact-icone nom="droite" [taille]="16" /></td> }
              </tr>
            } @empty {
              <tr><td [attr.colspan]="colonnes().length + (cliquable() ? 1 : 0)" class="vide">
                {{ texte() ? 'Aucune ligne ne correspond à cette recherche.' : vide() }}
              </td></tr>
            }
          }
        </tbody>
      </table>
    </div>
    @if (pages() > 1) {
      <nav class="pagination" aria-label="Pages du tableau">
        <button type="button" class="btn petit icone-seule" (click)="aller(page() - 1)" [disabled]="page() === 1" aria-label="Page précédente">
          <fact-icone nom="gauche" [taille]="16" />
        </button>
        <span class="donnee">Page {{ page() }} / {{ pages() }}</span>
        <button type="button" class="btn petit icone-seule" (click)="aller(page() + 1)" [disabled]="page() === pages()" aria-label="Page suivante">
          <fact-icone nom="droite" [taille]="16" />
        </button>
      </nav>
    }`,
  styles: [`
    /* position: relative : les textes réservés aux lecteurs d'écran (positionnés en absolu) restent dans le tableau,
       sans élargir la page sur téléphone. */
    :host { display: block; position: relative; background: var(--surface); border: 1px solid var(--filet); border-radius: var(--rayon); overflow: hidden; }
    .outils { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--filet); }
    .cherche { flex: 1 1 200px; display: flex; align-items: center; gap: 8px; padding: 0 12px; min-height: 40px;
               border: 1px solid var(--filet-fort); border-radius: var(--rayon-2); color: var(--discret); background: var(--surface); }
    .cherche:focus-within { border-color: var(--marque); }
    .cherche input { flex: 1; min-width: 0; border: 0; background: none; outline: none; color: var(--encre); }
    .compte { font-size: 13px; color: var(--discret); }
    .defile { position: relative; overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 11px 14px; text-align: left; font-size: 14px; border-bottom: 1px solid var(--filet); white-space: nowrap; }
    thead th { background: var(--surface-3); padding: 0; }
    thead button { width: 100%; min-height: 42px; padding: 0 14px; display: flex; align-items: center; gap: 6px;
                   background: none; border: 0; font: 600 13px var(--titre); color: var(--marque); text-align: left; }
    thead th.n button { justify-content: flex-end; }
    thead button:hover { background: color-mix(in srgb, var(--marque) 8%, transparent); }
    .sens { font-size: 9px; width: 9px; }
    td.n { text-align: right; }
    td.donnee { font-size: 13px; }
    tbody tr:last-child td { border-bottom: 0; }
    tr.cliquable { cursor: pointer; transition: background var(--courte) var(--courbe); }
    tr.cliquable:hover, tr.cliquable:focus-visible { background: var(--surface-2); }
    .fleche { width: 36px; color: var(--discret); text-align: right; }
    /* Une ligne modifiée clignote brièvement en surbrillance (§ 6.3). */
    tr.modifiee { animation: surbrillance 1.4s var(--courbe); }
    @keyframes surbrillance { 0%, 40% { background: var(--ocre-clair); } }
    .squelette span { display: block; height: 12px; width: 70%; border-radius: 6px; background: var(--surface-3); animation: pulse 1.2s ease-in-out infinite; }
    .vide { text-align: center; color: var(--discret); padding: 28px 14px; white-space: normal; }
    .pagination { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 10px 14px;
                  border-top: 1px solid var(--filet); font-size: 13px; color: var(--encre-2); }
  `],
})
export class TableDonnees {
  readonly titre = input('Tableau de données');
  readonly colonnes = input.required<Colonne[]>();
  readonly lignes = input.required<Ligne[]>();
  readonly cleLigne = input<string | null>(null);
  readonly parPage = input(10);
  readonly recherche = input(true);
  readonly exportable = input(false);
  readonly cliquable = input(false);
  readonly chargement = input(false);
  readonly vide = input('Aucune donnée pour le moment.');
  /** La clé de la ligne qui vient d'être enregistrée : elle clignote. */
  readonly surbrillance = input<unknown>(null);
  readonly ligne = output<Ligne>();

  protected readonly texte = signal('');
  protected readonly tri = signal<string | null>(null);
  protected readonly desc = signal(false);
  protected readonly page = signal(1);

  protected readonly retenues = computed(() => {
    const cherche = sansAccent(this.texte().trim());
    let l = this.lignes();
    if (cherche) {
      l = l.filter((ligne) => this.colonnes().some((c) =>
        sansAccent(String(ligne[c.cleLibelle ?? c.cle] ?? '')).includes(cherche)));
    }
    const cle = this.tri();
    if (!cle) return l;
    const sens = this.desc() ? -1 : 1;
    return [...l].sort((a, b) => {
      const x = a[cle], y = b[cle];
      if (x === null || x === undefined) return 1;
      if (y === null || y === undefined) return -1;
      if (typeof x === 'number' && typeof y === 'number') return (x - y) * sens;
      return String(x).localeCompare(String(y), 'fr', { numeric: true }) * sens;
    });
  });

  protected readonly pages = computed(() => Math.max(1, Math.ceil(this.retenues().length / this.parPage())));
  protected readonly visibles = computed(() => {
    const debut = (this.page() - 1) * this.parPage();
    return this.retenues().slice(debut, debut + this.parPage());
  });

  constructor() {
    // Une page qui n'existe plus après un filtre ramène à la dernière.
    effect(() => {
      const pages = this.pages();
      untracked(() => { if (this.page() > pages) this.page.set(pages); });
    });
  }

  protected num(c: Colonne): boolean { return NUMERIQUES.includes(c.format ?? ''); }
  protected ton(code: unknown): string { return tonStatut(String(code)); }

  protected f(v: unknown, format: Colonne['format']): string {
    if (v === null || v === undefined || v === '') return '-';
    switch (format) {
      case 'entier': return nombre(Number(v));
      case 'fcfa': return fcfa(Number(v));
      case 'm2': return surface(Number(v));
      case 'pct': return pourcentage(Number(v));
      case 'date': return date(String(v));
      default: return String(v);
    }
  }

  protected chercher(t: string): void { this.texte.set(t); this.page.set(1); }
  protected aller(p: number): void { this.page.set(Math.min(this.pages(), Math.max(1, p))); }
  protected ouvrir(l: Ligne): void { if (this.cliquable()) this.ligne.emit(l); }

  protected trier(cle: string): void {
    if (this.tri() === cle) {
      if (this.desc()) { this.tri.set(null); this.desc.set(false); } else { this.desc.set(true); }
    } else {
      this.tri.set(cle);
      this.desc.set(false);
    }
  }

  /** Export des lignes retenues en CSV lisible par Excel (séparateur point-virgule, BOM UTF-8). */
  protected exporter(): void {
    const cellule = (v: unknown) => {
      const brut = String(v ?? '');
      // Une cellule qui commence par = + - @ serait lue comme une formule par Excel.
      const texte = /^[=+\-@\t\r]/.test(brut) && typeof v !== 'number' ? "'" + brut : brut;
      return '"' + texte.replace(/"/g, '""') + '"';
    };
    const lignes = [
      this.colonnes().map((c) => cellule(c.libelle)).join(';'),
      ...this.retenues().map((l) => this.colonnes().map((c) => cellule(l[c.cleLibelle ?? c.cle])).join(';')),
    ];
    const lien = document.createElement('a');
    lien.href = URL.createObjectURL(new Blob(['﻿' + lignes.join('\r\n')], { type: 'text/csv;charset=utf-8' }));
    lien.download = sansAccent(this.titre()).replace(/[^a-z0-9]+/g, '-') + '.csv';
    lien.click();
    URL.revokeObjectURL(lien.href);
  }
}
