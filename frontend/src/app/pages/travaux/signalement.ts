import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icone } from '../../composants/icone';
import { messageErreur } from '../../noyau/api.interceptor';
import { Batiment, Intervention, Lot } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';
import { ReferentielService } from '../../noyau/referentiel.service';
import { TravauxService } from '../../noyau/travaux.service';
import { Choix, OptionsPipe } from '../../composants/choix';
import { OPTIONS } from '../../noyau/options';

/**
 * Signaler une panne ou un besoin de travaux (§ 4.5, étape « Signalée ») : sur un bâtiment, ou sur
 * l'un de ses lots ; catégorie, urgence, description, photos prises sur place.
 */
@Component({
  selector: 'fact-signalement',
  imports: [FormsModule, Icone, Choix, OptionsPipe],
  template: `
    <form class="formulaire" (ngSubmit)="signaler()" novalidate>
      @if (!idBatiment()) {
        <fact-choix name="batiment" [seuilRecherche]="0" libelle="Bâtiment" [options]="batiments() | options : opt.batiment"
                    [ngModel]="s.idBatiment" (ngModelChange)="choisirBatiment($event)" required />
      }
      <fact-choix name="lot" libelle="Où" vide="Parties communes / tout le bâtiment" [options]="lots() | options : opt.lot"
                  [(ngModel)]="s.idLot" [disabled]="!lots().length" />
      <label class="champ">
        <select name="categorie" [(ngModel)]="s.categorie" required>
          @for (v of ref.liste('CATEGORIE_INTERVENTION'); track v.code) { <option [value]="v.code">{{ v.libelle }}</option> }
        </select>
        <span>Catégorie</span>
      </label>
      <fieldset>
        <legend>Urgence</legend>
        <div class="segments" role="radiogroup" aria-label="Urgence">
          @for (v of ref.liste('URGENCE'); track v.code) {
            <button type="button" role="radio" [attr.aria-checked]="s.urgence === v.code" [class.actif]="s.urgence === v.code" [class]="'u-' + v.code"
                    (click)="s.urgence = v.code">{{ v.libelle }}</button>
          }
        </div>
      </fieldset>
      <label class="champ">
        <input type="text" name="titre" placeholder=" " maxlength="160" [(ngModel)]="s.titre" autocomplete="off" required>
        <span>En quelques mots (« Fuite sous l'évier »)</span>
      </label>
      <label class="champ">
        <textarea name="description" placeholder=" " [(ngModel)]="s.description"></textarea>
        <span>Description</span>
      </label>
      <label class="photos">
        <fact-icone nom="photo" [taille]="20" />
        <span>{{ photos().length ? photos().length + ' photo(s) choisie(s)' : 'Ajouter des photos de la panne' }}</span>
        <input type="file" accept="image/*" capture="environment" multiple (change)="choisirPhotos($event)">
      </label>
      @if (erreur()) { <p class="message" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
      <div class="pied">
        <button type="button" class="btn" (click)="annule.emit()">Annuler</button>
        <button type="submit" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe()">Signaler</button>
      </div>
    </form>`,
  styles: [`
    .formulaire { display: grid; gap: 14px; }
    fieldset { margin: 0; padding: 0; border: 0; }
    legend { margin-bottom: 8px; font: 600 12.5px var(--titre); letter-spacing: .05em; text-transform: uppercase; color: var(--discret); }
    .segments { display: grid; grid-template-columns: repeat(2, 1fr); gap: 4px; padding: 4px; border-radius: 12px; background: var(--surface-3); }
    .segments button { min-height: 38px; border: 0; border-radius: 9px; background: transparent; color: var(--encre-2); font-weight: 500;
                       transition: background var(--courte) var(--courbe), color var(--courte) var(--courbe); }
    .segments button.actif { background: var(--surface); color: var(--marque); font-weight: 600; box-shadow: 0 0 0 1px var(--filet); }
    .segments button.actif.u-HAUTE { color: var(--attention-texte); box-shadow: inset 0 0 0 2px var(--attention); }
    .segments button.actif.u-CRITIQUE { color: var(--critique-texte); box-shadow: inset 0 0 0 2px var(--critique); }
    .photos { position: relative; display: flex; align-items: center; gap: 10px; min-height: 52px; padding: 0 14px; border: 2px dashed var(--filet-fort);
              border-radius: var(--rayon-2); color: var(--marque); cursor: pointer; }
    .photos:hover { border-color: var(--marque); background: var(--nuit-clair); }
    .photos input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
    .message { display: flex; gap: 9px; margin: 0; padding: 11px 13px; border-radius: var(--rayon-2); background: var(--critique-clair);
               color: var(--critique-texte); font-size: 14px; }
    .pied { position: sticky; bottom: -16px; display: flex; justify-content: flex-end; gap: 8px; margin: 4px -18px -16px; padding: 12px 18px;
            border-top: 1px solid var(--filet); background: var(--surface); }
  `],
})
export class Signalement implements OnInit {
  protected readonly opt = OPTIONS;
  private readonly patrimoine = inject(PatrimoineService);
  private readonly travaux = inject(TravauxService);
  protected readonly ref = inject(ReferentielService);

  /** Le bâtiment, s'il est déjà connu (depuis sa fiche) ; sinon la liste est proposée. */
  readonly idBatiment = input<number | null>(null);
  readonly idLot = input<number | null>(null);
  readonly cree = output<Intervention>();
  readonly annule = output<void>();

  protected readonly batiments = signal<Batiment[]>([]);
  protected readonly lots = signal<Lot[]>([]);
  protected readonly photos = signal<File[]>([]);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected s = { idBatiment: null as number | null, idLot: null as number | null, categorie: 'PLOMBERIE', urgence: 'NORMALE', titre: '', description: '' };

  async ngOnInit(): Promise<void> {
    this.s.idLot = this.idLot();
    try {
      await this.ref.assurer();
      if (this.idBatiment()) {
        await this.choisirBatiment(this.idBatiment());
      } else {
        this.batiments.set(await this.patrimoine.batiments());
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }

  protected async choisirBatiment(id: number | null): Promise<void> {
    this.s.idBatiment = id;
    this.lots.set([]);
    if (!id) return;
    const fiche = await this.patrimoine.batiment(id);
    this.lots.set(fiche.lots);
    if (this.s.idLot && !fiche.lots.some((l) => l.id === this.s.idLot)) this.s.idLot = null;
  }

  protected choisirPhotos(e: Event): void {
    const champ = e.target as HTMLInputElement;
    this.photos.set(Array.from(champ.files ?? []));
  }

  protected async signaler(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const i = await this.travaux.signaler(this.s);
      if (this.photos().length) {
        try {
          await this.travaux.envoyerPhotos(i.id, 'AVANT', this.photos());
        } catch (e) {
          this.erreur.set('Intervention signalée, mais les photos n\'ont pas pu être envoyées : ' + messageErreur(e));
        }
      }
      this.cree.emit(i);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }
}
