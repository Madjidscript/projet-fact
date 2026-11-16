import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { PhotoSecurisee } from '../../composants/photo-securisee';
import { Annonce, AnnoncesService, StatutAnnonce } from '../../noyau/annonces.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { fcfa } from '../../noyau/format';
import { DROITS } from '../../noyau/modeles';
import { depuis } from '../../noyau/travaux.service';
import { FicheAnnonceComposant } from './fiche-annonce';

type Vue = 'A_VALIDER' | 'PUBLIEE' | 'PREPARATION' | 'RETIREE';
const VUES: { cle: Vue; libelle: string; statuts: StatutAnnonce[] }[] = [
  { cle: 'A_VALIDER', libelle: 'À valider', statuts: ['A_VALIDER'] },
  { cle: 'PUBLIEE', libelle: 'En ligne', statuts: ['PUBLIEE'] },
  { cle: 'PREPARATION', libelle: 'En préparation', statuts: ['BROUILLON', 'REFUSEE'] },
  { cle: 'RETIREE', libelle: 'Retirées', statuts: ['RETIREE'] },
];
const TONS: Record<string, string> = { BROUILLON: 'neutre', A_VALIDER: 'attention', PUBLIEE: 'bon', REFUSEE: 'critique', RETIREE: 'neutre' };

/**
 * Les annonces du site (§ 4.10) : celles à valider en tête pour l'éditeur web et la Direction,
 * celles en ligne, celles qu'on prépare et celles retirées (bien loué, vendu ou retiré à la main).
 * Une annonce se crée depuis la fiche d'un lot libre : « Publier sur le site ».
 */
@Component({
  selector: 'fact-annonces',
  imports: [FormsModule, Icone, EtatVide, Apparition, PanneauLateral, PhotoSecurisee, FicheAnnonceComposant],
  template: `
    <header class="entete">
      <div>
        <h1>Annonces</h1>
        <p class="accroche">Les biens proposés sur le site de l'agence.</p>
      </div>
    </header>

    <p class="astuce"><fact-icone nom="info" [taille]="18" /> Pour publier un logement libre : ouvrez son bâtiment, cliquez sur le lot, puis « Publier sur le site ».</p>

    <div class="segments" role="tablist" aria-label="Étapes des annonces">
      @for (v of vues; track v.cle) {
        <button type="button" role="tab" [attr.aria-selected]="vue() === v.cle" [class.actif]="vue() === v.cle" (click)="vue.set(v.cle)">
          {{ v.libelle }} <span class="nombre" [class.alerte]="v.cle === 'A_VALIDER' && compte(v.cle) > 0">{{ compte(v.cle) }}</span>
        </button>
      }
    </div>

    @if (annonces().length > 6) {
      <label class="cherche">
        <fact-icone nom="recherche" [taille]="18" />
        <input type="search" placeholder="Titre, bâtiment, quartier…" [ngModel]="recherche()" (ngModelChange)="recherche.set($event)" aria-label="Rechercher une annonce">
      </label>
    }

    @if (chargement()) {
      <ul class="grille">@for (i of [1, 2, 3]; track i) { <li class="squelette"></li> }</ul>
    } @else {
      <ul class="grille">
        @for (a of visibles(); track a.id; let n = $index) {
          <li [factApparition]="n">
            <button type="button" class="carte annonce" (click)="ouverte.set(a.id)">
              <div class="visuel">
                @if (a.idPhoto) { <fact-photo-securisee [idPhoto]="a.idPhoto" source="annonces" [alt]="a.titre" /> }
                @else { <span class="sans-photo"><fact-icone nom="photo" [taille]="28" /> Aucune photo</span> }
                <span class="pastille" [class]="ton(a.statut)">{{ a.libelleStatut }}</span>
                @if (a.aLaUne) { <span class="une">★ À la une</span> }
              </div>
              <div class="texte">
                <strong class="prix donnee">{{ f.fcfa(a.prix) }}{{ a.operation === 'LOCATION' ? ' / mois' : '' }}</strong>
                <span class="titre">{{ a.titre }}</span>
                <small>{{ a.batiment }} · lot {{ a.numeroLot }} · {{ a.quartier ? a.quartier + ', ' : '' }}{{ a.commune }}</small>
                <small class="quand">
                  @switch (a.statut) {
                    @case ('A_VALIDER') { Proposée {{ depuis(a.soumiseLe!) }}@if (a.soumisePar) { par {{ a.soumisePar }} } }
                    @case ('PUBLIEE') { En ligne {{ depuis(a.publieeLe!) }} · {{ a.photos }} photo(s) }
                    @case ('REFUSEE') { À revoir : {{ a.motif }} }
                    @case ('RETIREE') { {{ a.motif }} }
                    @default { Préparée {{ depuis(a.creeLe) }} · {{ a.photos }} photo(s) }
                  }
                </small>
              </div>
            </button>
          </li>
        } @empty {
          <li class="pleine">
            <fact-etat-vide icone="annonce" titre="Rien à cette étape"
              [message]="vue() === 'A_VALIDER' ? 'Aucune annonce n\\'attend de validation.' : 'Aucune annonce ici pour le moment.'" />
          </li>
        }
      </ul>
    }

    <fact-panneau-lateral [page]="true" titre="Annonce" [ouvert]="!!ouverte()" (fermer)="ouverte.set(null)">
      @if (ouverte(); as id) { <fact-fiche-annonce [idAnnonce]="id" (change)="charger()" /> }
    </fact-panneau-lateral>`,
  styleUrl: '../travaux/travaux.scss',
  styles: [`
    .astuce { display: flex; gap: 8px; align-items: flex-start; margin: 16px 0 0; padding: 10px 12px; border-radius: var(--rayon-2);
              background: var(--nuit-clair); color: var(--encre-2); font-size: 13.5px; }
    .grille { list-style: none; margin: 0; padding: 0; display: grid; gap: 14px; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
    .grille .pleine { grid-column: 1 / -1; }
    .annonce { width: 100%; padding: 0; overflow: hidden; display: grid; text-align: left; font: inherit; color: inherit; cursor: pointer;
               transition: transform var(--courte) var(--courbe), box-shadow var(--courte) var(--courbe); }
    .annonce:hover { transform: translateY(-3px); box-shadow: 0 14px 28px -16px rgba(22, 41, 63, .4); }
    .visuel { position: relative; aspect-ratio: 16 / 10; background: var(--surface-3); }
    .visuel fact-photo-securisee { width: 100%; height: 100%; }
    .sans-photo { position: absolute; inset: 0; display: grid; place-content: center; justify-items: center; gap: 4px; color: var(--discret); font-size: 13px; }
    .visuel .pastille { position: absolute; left: 10px; top: 10px; }
    .une { position: absolute; right: 10px; top: 10px; padding: 2px 8px; border-radius: 10px; background: var(--ocre); color: #fff; font-size: 12px; font-weight: 600; }
    .texte { display: grid; gap: 3px; padding: 12px 14px 14px; }
    .prix { font-size: 15px; color: var(--marque); }
    .titre { font-weight: 600; }
    small { font-size: 12.5px; color: var(--discret); }
    .quand { color: var(--encre-2); }
    .squelette { height: 270px; }
  `],
})
export class Annonces implements OnInit {
  private readonly service = inject(AnnoncesService);
  private readonly avis = inject(AvisService);
  private readonly auth = inject(AuthService);

  /** ?annonce=12 : la fiche s'ouvre d'elle-même (lien d'une alerte). */
  readonly annonceAdresse = input<string | undefined>(undefined, { alias: 'annonce' });

  protected readonly vues = VUES;
  protected readonly annonces = signal<Annonce[]>([]);
  protected readonly chargement = signal(true);
  protected readonly vue = signal<Vue>('A_VALIDER');
  protected readonly recherche = signal('');
  protected readonly ouverte = signal<number | null>(null);
  protected readonly f = { fcfa };
  protected readonly depuis = depuis;

  protected readonly visibles = computed(() => {
    const statuts = VUES.find((v) => v.cle === this.vue())!.statuts;
    const t = this.recherche().trim().toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
    return this.annonces().filter((a) => statuts.includes(a.statut)
      && (!t || `${a.titre} ${a.batiment} ${a.quartier ?? ''} ${a.commune} ${a.reference}`.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').includes(t)));
  });

  constructor() {
    effect(() => {
      const id = Number(this.annonceAdresse());
      if (id) untracked(() => this.ouverte.set(id));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
    // À l'ouverture : celles à valider pour qui valide, sinon celles en préparation ou en ligne.
    const valideur = this.auth.a(DROITS.annonceValider);
    const premiere = (valideur ? ['A_VALIDER', 'PUBLIEE', 'PREPARATION'] : ['PREPARATION', 'A_VALIDER', 'PUBLIEE'] as Vue[])
      .find((v) => this.compte(v as Vue) > 0);
    if (premiere) this.vue.set(premiere as Vue);
  }

  protected compte(v: Vue): number {
    const statuts = VUES.find((x) => x.cle === v)!.statuts;
    return this.annonces().filter((a) => statuts.includes(a.statut)).length;
  }

  protected ton(statut: string): string {
    return TONS[statut] ?? 'neutre';
  }

  async charger(): Promise<void> {
    try {
      this.annonces.set(await this.service.lister());
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
