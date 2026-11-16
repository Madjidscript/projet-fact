import { Component, OnDestroy, OnInit, computed, inject, input, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Choix, OptionChoix, OptionsPipe } from '../../composants/choix';
import { Icone } from '../../composants/icone';
import { Signature } from '../../composants/signature';
import { BailTerrain, BatimentTerrain, TerrainService, maPosition, reduirePhoto } from '../../noyau/terrain.service';
import { EnteteTerrain } from './entete-terrain';

type Etat = 'BON' | 'USAGE' | 'MAUVAIS' | 'HORS_SERVICE';
interface Element { nom: string; etat: Etat | null; note: string; }
interface Piece { piece: string; elements: Element[]; note: string; photos: { blob: Blob; url: string }[]; ouverte: boolean; }
type Etape = 'logement' | 'pieces' | 'compteurs' | 'signatures';

const ETAPES: { cle: Etape; libelle: string }[] = [
  { cle: 'logement', libelle: 'Logement' },
  { cle: 'pieces', libelle: 'Pièces' },
  { cle: 'compteurs', libelle: 'Compteurs' },
  { cle: 'signatures', libelle: 'Signatures' },
];
const ETATS: { code: Etat; court: string; ton: string }[] = [
  { code: 'BON', court: 'Bon', ton: 'bon' },
  { code: 'USAGE', court: 'Usé', ton: 'info' },
  { code: 'MAUVAIS', court: 'Mauvais', ton: 'attention' },
  { code: 'HORS_SERVICE', court: 'H.S.', ton: 'critique' },
];
const COMMUNS = ['Murs', 'Sol', 'Plafond', 'Porte', 'Fenêtres', 'Prises et interrupteurs', 'Éclairage'];
const PROPRES: Record<string, string[]> = {
  Cuisine: ['Évier', 'Robinetterie', 'Placards', 'Plan de travail'],
  'Salle de bain': ['Lavabo', 'Douche ou baignoire', 'Robinetterie', 'WC', 'Carrelage'],
  "Salle d'eau": ['Lavabo', 'Douche', 'Robinetterie', 'WC'],
  WC: ['Cuvette', 'Chasse d\'eau', 'Lave-mains'],
  Séjour: ['Climatiseur'],
  'Chambre 1': ['Placard', 'Climatiseur'],
  'Chambre 2': ['Placard', 'Climatiseur'],
  'Chambre 3': ['Placard', 'Climatiseur'],
  Terrasse: ['Garde-corps', 'Évacuation'],
};

/** Les pièces proposées selon le type du lot (« F3 », « Studio »…) ; l'agent ajuste. */
function piecesPour(type: string | null): string[] {
  const t = (type ?? '').toLowerCase();
  if (t.includes('studio')) return ['Pièce principale', 'Cuisine', "Salle d'eau"];
  if (t.includes('bureau') || t.includes('magasin') || t.includes('commerce')) return ['Local', 'Sanitaires'];
  const n = Number(/f\s?(\d)/.exec(t)?.[1] ?? (t.includes('villa') ? 5 : 3));
  const chambres = Math.max(1, Math.min(6, n - 1));
  return ['Séjour', ...Array.from({ length: chambres }, (_, i) => `Chambre ${i + 1}`), 'Cuisine', 'Salle de bain', 'WC', ...(t.includes('villa') ? ['Terrasse'] : [])];
}

function nouvellePiece(nom: string): Piece {
  const propres = PROPRES[nom] ?? [];
  return { piece: nom, elements: [...COMMUNS, ...propres].map((e) => ({ nom: e, etat: null, note: '' })), note: '', photos: [], ouverte: false };
}

/**
 * L'état des lieux sur place (§ 4.6) : pièce par pièce, l'état de chaque élément d'un geste,
 * des photos, les compteurs et les clés, puis la signature au doigt de l'occupant et de l'agent.
 * Tout est gardé dans le téléphone et part à l'agence, qui produit le PDF.
 */
@Component({
  selector: 'fact-etat-des-lieux-terrain',
  imports: [FormsModule, RouterLink, Icone, Choix, OptionsPipe, Signature, EnteteTerrain],
  template: `
    <div class="tr">
      <fact-entete-terrain titre="État des lieux" retour="/terrain" />

      @if (fini(); as f) {
        <section class="tr-fini carte" role="status">
          <svg class="tr-coche" viewBox="0 0 52 52" aria-hidden="true"><circle cx="26" cy="26" r="23" /><path d="m15 27 7.500 7.500L37 19" /></svg>
          <h2>État des lieux {{ f.type === 'ENTREE' ? 'd\\'entrée' : 'de sortie' }} signé</h2>
          <p>{{ f.occupant }} · lot {{ f.lot }}, {{ f.batiment }}</p>
          <p>{{ terrain.enLigne() ? 'Il part maintenant vers l\\'agence, qui en produit le PDF.' : 'Il est gardé dans le téléphone et partira au retour du réseau.' }}</p>
          <div class="ed-actions">
            <button type="button" class="btn principal grand" (click)="recommencer()"><fact-icone nom="plus" [taille]="18" /> Autre état des lieux</button>
            <a class="btn grand" routerLink="/terrain">Retour à l'accueil</a>
          </div>
        </section>
      } @else {
        <nav class="tr-etapes" aria-label="Étapes">
          @for (e of etapes; track e.cle; let i = $index) {
            <button type="button" [class.actif]="etape() === e.cle" [class.fait]="i < rang()" (click)="aller(e.cle)" [disabled]="i > 0 && !bail()"
                    [attr.aria-current]="etape() === e.cle ? 'step' : null"><span>{{ i + 1 }}</span>{{ e.libelle }}</button>
          }
        </nav>

        @switch (etape()) {
          @case ('logement') {
            <section class="tr-bloc">
              <h2>Quel logement ?</h2>
              <fact-choix name="batiment" libelle="Bâtiment" [options]="batiments() | options : optionBatiment" [(ngModel)]="idBatiment"
                          (ngModelChange)="bail.set(null)" />
              @if (idBatiment) {
                <ul class="ed-lots">
                  @for (b of baux(); track b.idBail) {
                    <li>
                      <button type="button" class="carte ed-lot" [class.choisi]="bail()?.idBail === b.idBail" (click)="choisir(b)"
                              [disabled]="b.edlEntree && b.edlSortie">
                        <span class="ed-porte donnee">{{ b.numeroLot }}</span>
                        <span class="ed-qui"><strong>{{ b.occupant }}</strong><small>{{ b.typeLot || 'Lot' }} · {{ b.statut === 'PREAVIS' ? 'en préavis' : 'bail en cours' }}</small></span>
                        <span class="ed-faits">
                          <span class="pastille" [class.bon]="b.edlEntree">{{ b.edlEntree ? 'Entrée faite' : 'Entrée à faire' }}</span>
                          @if (b.statut === 'PREAVIS' || b.edlEntree) { <span class="pastille" [class.bon]="b.edlSortie" [class.attention]="!b.edlSortie && b.statut === 'PREAVIS'">{{ b.edlSortie ? 'Sortie faite' : 'Sortie à faire' }}</span> }
                        </span>
                      </button>
                    </li>
                  } @empty {
                    <li class="tr-aide">Aucun bail en cours dans ce bâtiment.</li>
                  }
                </ul>
              }
              @if (bail(); as b) {
                <div class="ed-type" role="radiogroup" aria-label="Type d'état des lieux">
                  <button type="button" role="radio" [attr.aria-checked]="type() === 'ENTREE'" [class.actif]="type() === 'ENTREE'" [disabled]="b.edlEntree" (click)="type.set('ENTREE')">
                    <fact-icone nom="cle" [taille]="20" /> Entrée</button>
                  <button type="button" role="radio" [attr.aria-checked]="type() === 'SORTIE'" [class.actif]="type() === 'SORTIE'" [disabled]="b.edlSortie" (click)="type.set('SORTIE')">
                    <fact-icone nom="sortir" [taille]="20" /> Sortie</button>
                </div>
              }
            </section>
          }

          @case ('pieces') {
            <section class="tr-bloc">
              <h2>Les pièces <small>{{ faites() }}/{{ pieces().length }}</small></h2>
              <p class="tr-aide">Touchez une pièce, puis l'état de chaque élément. « Tout est bon » remplit d'un coup ce qui reste.</p>
              <ul class="ed-pieces">
                @for (p of pieces(); track $index; let i = $index) {
                  <li class="carte" [class.ouverte]="p.ouverte" [class.complete]="complete(p)">
                    <button type="button" class="ed-tete" (click)="basculer(i)" [attr.aria-expanded]="p.ouverte">
                      <span class="ed-rond">@if (complete(p)) { <fact-icone nom="coche" [taille]="16" /> } @else { {{ i + 1 }} }</span>
                      <strong>{{ p.piece }}</strong>
                      <small>{{ notes(p) }}/{{ p.elements.length }}{{ p.photos.length ? ' · ' + p.photos.length + ' photo(s)' : '' }}</small>
                      <fact-icone [nom]="p.ouverte ? 'haut' : 'bas'" [taille]="18" />
                    </button>
                    @if (p.ouverte) {
                      <div class="ed-corps">
                        <button type="button" class="btn petit" (click)="toutBon(p)"><fact-icone nom="coche" [taille]="16" /> Tout est bon</button>
                        @for (e of p.elements; track $index; let j = $index) {
                          <div class="ed-element">
                            <span class="ed-nom">{{ e.nom }}</span>
                            <div class="ed-etats" role="radiogroup" [attr.aria-label]="'État : ' + e.nom">
                              @for (x of etats; track x.code) {
                                <button type="button" role="radio" [attr.aria-checked]="e.etat === x.code" [class]="e.etat === x.code ? 'actif ' + x.ton : ''"
                                        (click)="e.etat = x.code; change()">{{ x.court }}</button>
                              }
                            </div>
                            @if (e.etat === 'MAUVAIS' || e.etat === 'HORS_SERVICE' || e.note) {
                              <input class="ed-note" type="text" [name]="'n' + i + '-' + j" placeholder="Ce qui ne va pas…" [(ngModel)]="e.note" (ngModelChange)="change()">
                            }
                          </div>
                        }
                        <div class="ed-ajout">
                          <input type="text" [name]="'ajout' + i" placeholder="Autre élément (ex. : chauffe-eau)" [(ngModel)]="ajout" (keydown.enter)="ajouterElement(p)">
                          <button type="button" class="btn petit" (click)="ajouterElement(p)" aria-label="Ajouter l'élément"><fact-icone nom="plus" [taille]="16" /></button>
                        </div>
                        <ul class="tr-photos">
                          @for (ph of p.photos; track ph.url; let k = $index) {
                            <li><img [src]="ph.url" [alt]="'Photo ' + (k + 1) + ' : ' + p.piece"><button type="button" (click)="retirerPhoto(p, k)" aria-label="Retirer la photo"><fact-icone nom="fermer" [taille]="16" /></button></li>
                          }
                          <li class="tr-prendre"><label><input type="file" accept="image/*" capture="environment" multiple (change)="ajouterPhotos(p, $event)">
                            <fact-icone nom="photo" [taille]="26" /><span>Photo</span></label></li>
                        </ul>
                        <label class="champ"><textarea [name]="'pn' + i" placeholder=" " rows="2" [(ngModel)]="p.note" (ngModelChange)="change()"></textarea><span>Remarque sur la pièce</span></label>
                        <button type="button" class="btn discret petit" (click)="retirerPiece(i)">Retirer cette pièce</button>
                      </div>
                    }
                  </li>
                }
              </ul>
              <div class="ed-ajout">
                <input type="text" name="nouvellePiece" placeholder="Ajouter une pièce (ex. : Buanderie)" [(ngModel)]="ajoutPiece" (keydown.enter)="ajouterPiece()">
                <button type="button" class="btn petit" (click)="ajouterPiece()" aria-label="Ajouter la pièce"><fact-icone nom="plus" [taille]="16" /></button>
              </div>
            </section>
          }

          @case ('compteurs') {
            <section class="tr-bloc">
              <h2>Compteurs et clés</h2>
              <div class="tr-form">
                <label class="champ"><input type="text" inputmode="numeric" name="eau" placeholder=" " [(ngModel)]="compteurs.eau" (ngModelChange)="change()"><span>Index du compteur d'eau (SODECI)</span></label>
                <label class="champ"><input type="text" inputmode="numeric" name="electricite" placeholder=" " [(ngModel)]="compteurs.electricite" (ngModelChange)="change()"><span>Index du compteur électrique (CIE)</span></label>
                <label class="champ"><input type="number" inputmode="numeric" name="cles" placeholder=" " min="0" max="50" [(ngModel)]="clesRemises" (ngModelChange)="change()">
                  <span>{{ type() === 'SORTIE' ? 'Clés rendues' : 'Clés remises' }}</span></label>
                <label class="champ"><textarea name="observations" placeholder=" " rows="4" [(ngModel)]="observations" (ngModelChange)="change()"></textarea><span>Observations générales</span></label>
              </div>
            </section>
          }

          @case ('signatures') {
            <section class="tr-bloc ed-signer">
              <h2>Signatures</h2>
              <p class="tr-aide">Relisez ensemble les constats, puis faites signer l'occupant ; signez ensuite pour l'agence.</p>
              <div class="carte ed-resume">
                <strong>{{ type() === 'ENTREE' ? 'Entrée' : 'Sortie' }} · lot {{ bail()?.numeroLot }}, {{ bail()?.batiment }}</strong>
                <small>{{ pieces().length }} pièce(s) · {{ defauts() }} défaut(s) noté(s) · {{ nbPhotos() }} photo(s)</small>
              </div>
              <label class="champ"><input type="text" name="signataire" placeholder=" " [(ngModel)]="signataire" autocomplete="off"><span>Nom de la personne qui signe pour l'occupant</span></label>
              <p class="ed-qui-signe">L'occupant</p>
              <fact-signature #sigOccupant libelle="l'occupant" (change)="signeOccupant.set($event)" />
              <p class="ed-qui-signe">L'agent de FACT Immobilier</p>
              <fact-signature #sigAgent libelle="l'agent" (change)="signeAgent.set($event)" />
            </section>
          }
        }

        @if (erreur()) { <p class="tr-erreur" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }

        <footer class="tr-pied">
          @if (etape() !== 'signatures') {
            <button type="button" class="btn principal grand" [disabled]="!bail()" (click)="suivante()">Suivant <fact-icone nom="droite" [taille]="18" /></button>
          } @else {
            <button type="button" class="btn principal grand" [class.occupe]="envoi()" [disabled]="envoi()" (click)="enregistrer()">
              <fact-icone nom="coche" [taille]="18" /> Enregistrer l'état des lieux</button>
          }
        </footer>
      }
    </div>
  `,
  styles: [`
    .ed-lots { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 8px; }
    .ed-lot { width: 100%; display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; text-align: left; font: inherit; color: inherit; cursor: pointer; }
    .ed-lot:disabled { opacity: .55; cursor: default; }
    .ed-lot.choisi { border-color: var(--ocre); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ocre) 22%, transparent); }
    .ed-porte { min-width: 50px; padding: 4px 6px; border-radius: 8px; background: var(--surface-3); text-align: center; font-size: 13px; }
    .ed-qui { flex: 1 1 160px; display: grid; min-width: 0; }
    .ed-qui small { color: var(--discret); font-size: 12.5px; }
    .ed-faits { display: flex; flex-wrap: wrap; gap: 4px; margin-left: auto; }
    .ed-type { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 14px; animation: montee var(--standard) var(--courbe) both; }
    .ed-type button, .ed-etats button { border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre); cursor: pointer;
                                        font: 600 14px var(--texte); transition: all var(--courte) var(--courbe); }
    .ed-type button { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 54px; border-radius: var(--rayon-2); }
    .ed-type button.actif { background: var(--ocre); border-color: var(--ocre); color: #fff; }
    .ed-type button:disabled { opacity: .45; cursor: default; }
    .ed-pieces { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .ed-pieces > li { padding: 0; overflow: hidden; transition: border-color var(--courte) var(--courbe); }
    .ed-pieces > li.complete { border-color: color-mix(in srgb, var(--bon) 45%, var(--filet)); }
    .ed-tete { width: 100%; display: flex; align-items: center; gap: 10px; padding: 14px; border: 0; background: none; font: inherit; color: inherit; cursor: pointer; text-align: left; }
    .ed-tete strong { flex: 1; }
    .ed-tete small { color: var(--discret); }
    .ed-rond { width: 28px; height: 28px; flex: none; display: grid; place-items: center; border-radius: 50%; background: var(--surface-3); font: 700 13px var(--titre); }
    .complete .ed-rond { background: var(--bon); color: #fff; animation: ed-pop 360ms var(--courbe); }
    @keyframes ed-pop { 50% { transform: scale(1.25); } }
    .ed-corps { display: grid; gap: 10px; padding: 0 14px 14px; animation: montee var(--standard) var(--courbe) both; }
    .ed-element { display: grid; gap: 6px; padding: 8px 0; border-top: 1px solid var(--filet); }
    .ed-nom { font-weight: 600; font-size: 14px; }
    .ed-etats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px; }
    .ed-etats button { min-height: 40px; border-radius: 10px; font-size: 13px; }
    .ed-etats button.actif.bon { background: var(--bon); border-color: var(--bon); color: #fff; }
    .ed-etats button.actif.info { background: var(--marque); border-color: var(--marque); color: #fff; }
    .ed-etats button.actif.attention { background: var(--attention); border-color: var(--attention); color: #fff; }
    .ed-etats button.actif.critique { background: var(--critique); border-color: var(--critique); color: #fff; }
    .ed-note, .ed-ajout input { width: 100%; min-height: 42px; padding: 0 12px; border-radius: 10px; border: 1px solid var(--filet-fort); background: var(--surface);
                                color: var(--encre); font: 14px var(--texte); }
    .ed-ajout { display: flex; gap: 6px; margin-top: 4px; }
    .ed-resume { display: grid; gap: 2px; margin-bottom: 12px; }
    .ed-resume small { color: var(--discret); }
    .ed-signer { display: grid; gap: 8px; }
    .ed-qui-signe { margin: 8px 0 0; font: 600 13px var(--texte); color: var(--encre-2); }
    .ed-actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; margin-top: 10px; }
    .ed-actions .btn { min-width: 0; white-space: normal; }
  `],
})
export class EtatDesLieuxTerrain implements OnInit, OnDestroy {
  protected readonly terrain = inject(TerrainService);
  readonly batimentAdresse = input<string | undefined>(undefined, { alias: 'batiment' });
  private readonly sigOccupant = viewChild<Signature>('sigOccupant');
  private readonly sigAgent = viewChild<Signature>('sigAgent');

  protected readonly etapes = ETAPES;
  protected readonly etats = ETATS;
  protected readonly etape = signal<Etape>('logement');
  protected idBatiment: number | null = null;
  protected readonly bail = signal<BailTerrain | null>(null);
  protected readonly type = signal<'ENTREE' | 'SORTIE'>('ENTREE');
  protected readonly pieces = signal<Piece[]>([]);
  protected compteurs = { eau: '', electricite: '' };
  protected clesRemises: number | null = null;
  protected observations = '';
  protected signataire = '';
  protected ajout = '';
  protected ajoutPiece = '';
  protected readonly signeOccupant = signal(false);
  protected readonly signeAgent = signal(false);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly fini = signal<{ type: string; occupant: string; lot: string; batiment: string } | null>(null);
  private readonly selection = signal(0);

  protected readonly rang = computed(() => ETAPES.findIndex((e) => e.cle === this.etape()));
  protected readonly batiments = computed(() => {
    const ids = new Set((this.terrain.preparation()?.baux ?? []).map((b) => b.idBatiment));
    return (this.terrain.preparation()?.batiments ?? []).filter((b) => ids.has(b.id));
  });
  protected readonly baux = computed(() => {
    this.selection();
    return (this.terrain.preparation()?.baux ?? []).filter((b) => b.idBatiment === this.idBatiment)
      .sort((a, b) => a.numeroLot.localeCompare(b.numeroLot, 'fr', { numeric: true }));
  });
  protected readonly optionBatiment = (b: BatimentTerrain): OptionChoix => {
    const aFaire = (this.terrain.preparation()?.baux ?? []).filter((x) => x.idBatiment === b.id && (!x.edlEntree || (x.statut === 'PREAVIS' && !x.edlSortie))).length;
    return { valeur: b.id, libelle: b.nom, detail: (b.quartier ? b.quartier + ', ' : '') + b.commune + (aFaire ? ` · ${aFaire} à faire` : '') };
  };
  protected readonly faites = computed(() => this.pieces().filter((p) => this.complete(p)).length);
  protected readonly defauts = computed(() => this.pieces().reduce((n, p) => n + p.elements.filter((e) => e.etat === 'MAUVAIS' || e.etat === 'HORS_SERVICE').length, 0));
  protected readonly nbPhotos = computed(() => this.pieces().reduce((n, p) => n + p.photos.length, 0));

  async ngOnInit(): Promise<void> {
    if (!this.terrain.preparation()) await this.terrain.preparer();
    const b = Number(this.batimentAdresse());
    if (b) {
      this.idBatiment = b;
      this.selection.update((n) => n + 1);
    }
  }

  ngOnDestroy(): void {
    this.pieces().forEach((p) => p.photos.forEach((x) => URL.revokeObjectURL(x.url)));
  }

  protected choisir(b: BailTerrain): void {
    this.bail.set(b);
    this.type.set(!b.edlEntree ? 'ENTREE' : 'SORTIE');
    this.signataire = b.occupant;
    if (!this.pieces().length || this.pieces().every((p) => !this.notes(p))) this.pieces.set(piecesPour(b.typeLot).map(nouvellePiece));
    this.erreur.set(null);
  }

  protected aller(e: Etape): void {
    if (e !== 'logement' && !this.bail()) return;
    this.etape.set(e);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  protected suivante(): void {
    this.erreur.set(null);
    if (this.etape() === 'logement' && !this.bail()) return;
    if (this.etape() === 'pieces') {
      const p = this.pieces().find((x) => !this.complete(x));
      if (!this.pieces().length) {
        this.erreur.set('Ajoutez au moins une pièce.');
        return;
      }
      if (p) {
        this.erreur.set(`Il reste des éléments sans état dans « ${p.piece} ».`);
        this.pieces.update((l) => l.map((x) => ({ ...x, ouverte: x === p })));
        return;
      }
    }
    this.aller(ETAPES[this.rang() + 1].cle);
  }

  protected basculer(i: number): void {
    this.pieces.update((l) => l.map((p, j) => ({ ...p, ouverte: j === i ? !p.ouverte : false })));
  }

  protected complete(p: Piece): boolean {
    return p.elements.length > 0 && p.elements.every((e) => e.etat);
  }

  protected notes(p: Piece): number {
    return p.elements.filter((e) => e.etat).length;
  }

  protected toutBon(p: Piece): void {
    p.elements.forEach((e) => { if (!e.etat) e.etat = 'BON'; });
    this.change();
  }

  protected change(): void {
    this.pieces.update((l) => [...l]);
  }

  protected ajouterElement(p: Piece): void {
    const n = this.ajout.trim();
    if (!n) return;
    p.elements.push({ nom: n, etat: null, note: '' });
    this.ajout = '';
    this.change();
  }

  protected ajouterPiece(): void {
    const n = this.ajoutPiece.trim();
    if (!n) return;
    this.pieces.update((l) => [...l.map((p) => ({ ...p, ouverte: false })), { ...nouvellePiece(n), ouverte: true }]);
    this.ajoutPiece = '';
  }

  protected retirerPiece(i: number): void {
    this.pieces.update((l) => {
      l[i].photos.forEach((x) => URL.revokeObjectURL(x.url));
      return l.filter((_, j) => j !== i);
    });
  }

  protected async ajouterPhotos(p: Piece, e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichiers = Array.from(champ.files ?? []);
    champ.value = '';
    for (const f of fichiers) {
      if (this.nbPhotos() >= 60) break;
      const blob = await reduirePhoto(f);
      p.photos.push({ blob, url: URL.createObjectURL(blob) });
      this.change();
    }
  }

  protected retirerPhoto(p: Piece, k: number): void {
    URL.revokeObjectURL(p.photos[k].url);
    p.photos.splice(k, 1);
    this.change();
  }

  protected async enregistrer(): Promise<void> {
    const b = this.bail();
    if (!b || this.envoi()) return;
    this.erreur.set(null);
    if (!this.signataire.trim()) {
      this.erreur.set('Écrivez le nom de la personne qui signe pour l\'occupant.');
      return;
    }
    const so = await this.sigOccupant()?.image();
    const sa = await this.sigAgent()?.image();
    if (!so || !sa) {
      this.erreur.set(!so ? 'La signature de l\'occupant manque.' : 'Votre signature manque.');
      return;
    }
    this.envoi.set(true);
    let position: GeolocationPosition | null = null;
    try {
      position = await Promise.race([maPosition(), new Promise<null>((ok) => setTimeout(() => ok(null), 4000))]);
    } catch { /* la position est un plus */ }
    try {
      const cle = this.terrain.nouvelleCle();
      const fichiers: { champ: string; nom: string; blob: Blob }[] = [
        { champ: 'signatureOccupant', nom: 'occupant.png', blob: so },
        { champ: 'signatureAgent', nom: 'agent.png', blob: sa },
      ];
      this.pieces().forEach((p, i) => p.photos.forEach((ph, k) => fichiers.push({ champ: 'photos', nom: `p${i}-${k + 1}.jpg`, blob: ph.blob })));
      const compteurs: Record<string, string> = {};
      if (this.compteurs.eau.trim()) compteurs['eau'] = this.compteurs.eau.trim();
      if (this.compteurs.electricite.trim()) compteurs['electricite'] = this.compteurs.electricite.trim();
      await this.terrain.mettreEnFile({
        cle, genre: 'etat-des-lieux', libelle: `État des lieux ${this.type() === 'ENTREE' ? 'd\'entrée' : 'de sortie'}`,
        detail: `${b.occupant} · lot ${b.numeroLot}, ${b.batiment} · ${this.nbPhotos()} photo(s)`, fichiers,
        donnees: {
          cle, idBail: b.idBail, type: this.type(), faitLe: new Date().toISOString(),
          pieces: this.pieces().map((p) => ({ piece: p.piece, note: p.note.trim() || null,
            elements: p.elements.map((e) => ({ nom: e.nom, etat: e.etat, note: e.note.trim() || null })) })),
          compteurs, clesRemises: this.clesRemises, observations: this.observations.trim() || null, signataire: this.signataire.trim(),
          latitude: position?.coords.latitude ?? null, longitude: position?.coords.longitude ?? null,
        },
      });
      this.fini.set({ type: this.type(), occupant: b.occupant, lot: b.numeroLot, batiment: b.batiment });
    } catch {
      this.erreur.set('Le téléphone n\'a pas pu garder cet état des lieux : libérez de la place et réessayez.');
    } finally {
      this.envoi.set(false);
    }
  }

  protected recommencer(): void {
    this.pieces().forEach((p) => p.photos.forEach((x) => URL.revokeObjectURL(x.url)));
    this.fini.set(null);
    this.bail.set(null);
    this.pieces.set([]);
    this.compteurs = { eau: '', electricite: '' };
    this.clesRemises = null;
    this.observations = '';
    this.signeOccupant.set(false);
    this.signeAgent.set(false);
    this.etape.set('logement');
  }
}
