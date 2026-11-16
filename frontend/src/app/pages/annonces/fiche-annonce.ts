import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Icone } from '../../composants/icone';
import { PhotoSecurisee } from '../../composants/photo-securisee';
import { AnnonceSaisie, AnnoncesService, FicheAnnonce } from '../../noyau/annonces.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { date, fcfa } from '../../noyau/format';
import { DROITS, Lot } from '../../noyau/modeles';

const TONS: Record<string, string> = { BROUILLON: 'neutre', A_VALIDER: 'attention', PUBLIEE: 'bon', REFUSEE: 'critique', RETIREE: 'neutre' };

/**
 * L'annonce d'un lot (§ 4.10) : texte, prix, photos et mode de position, la liste de ce qui manque
 * avant la mise en ligne, puis les étapes : proposer, valider ou renvoyer, retirer, mettre à la une.
 * S'ouvre depuis la fiche d'un lot ([idLot]) ou depuis la rubrique Annonces ([idAnnonce]).
 */
@Component({
  selector: 'fact-fiche-annonce',
  imports: [FormsModule, NgTemplateOutlet, Icone, PhotoSecurisee],
  template: `
    @if (chargement()) {
      <div class="squelette"></div>
    } @else if (!fiche()) {
      <!-- -------------------------------------------------- pas encore d'annonce -->
      <section class="intro">
        <span class="rond"><fact-icone nom="monde" [taille]="26" /></span>
        <div>
          <h3>Ce lot n'est pas encore sur le site</h3>
          <p>Rédigez l'annonce, ajoutez au moins {{ photosMinimum() }} photos, puis proposez-la : l'éditeur web ou la Direction la met en ligne.</p>
        </div>
      </section>
      @if (lot(); as l) {
        @if (!libre(l)) {
          <p class="message">Seul un lot libre (vacant, à louer, à vendre ou réservé) se publie. Ce lot est « {{ l.libelleStatut }} ».</p>
        } @else {
          <ng-container *ngTemplateOutlet="formulaire" />
        }
      }
    } @else {
      @let a = fiche()!.annonce;
      @let act = fiche()!.actions;
      <!-- ------------------------------------------------------------- en-tête -->
      <header class="entete">
        <div>
          <p class="surtitre"><span class="donnee">{{ a.reference }}</span> · {{ a.operation === 'VENTE' ? 'À vendre' : 'À louer' }}@if (a.aLaUne) { · <span class="une">★ À la une</span> }</p>
          <h3>{{ a.titre }}</h3>
          <p class="discret">{{ a.batiment }} · lot {{ a.numeroLot }} · {{ a.quartier ? a.quartier + ', ' : '' }}{{ a.commune }}</p>
        </div>
        <span class="pastille" [class]="ton(a.statut)">{{ a.libelleStatut }}</span>
      </header>
      @if (a.motif && (a.statut === 'REFUSEE' || a.statut === 'RETIREE')) {
        <p class="message" [class.neutre]="a.statut === 'RETIREE'"><fact-icone nom="info" [taille]="18" /> {{ a.statut === 'REFUSEE' ? 'À revoir : ' : 'Retirée : ' }}{{ a.motif }}</p>
      }
      @if (a.statut === 'PUBLIEE') {
        <a class="lien-site" [href]="fiche()!.lien" target="_blank" rel="noopener"><fact-icone nom="monde" [taille]="16" /> Voir l'annonce sur le site</a>
      }

      <!-- ---------------------------------------------------------- contrôles -->
      @if (a.statut !== 'PUBLIEE' && a.statut !== 'RETIREE') {
        <section class="controle" [class.complet]="!fiche()!.manques.length">
          @if (fiche()!.manques.length) {
            <strong>Avant la mise en ligne, il manque :</strong>
            <ul>@for (m of fiche()!.manques; track m) { <li>{{ m }}</li> }</ul>
          } @else {
            <strong><fact-icone nom="coche" [taille]="18" /> Tout est prêt pour la mise en ligne.</strong>
          }
        </section>
      }

      <!-- -------------------------------------------------------------- photos -->
      <section class="bloc">
        <h4>Photos <small>{{ fiche()!.photos.length }} · la première est la photo principale</small></h4>
        <ul class="photos">
          @for (p of fiche()!.photos; track p.id; let i = $index; let dernier = $last) {
            <li [class.principale]="i === 0">
              <fact-photo-securisee [idPhoto]="p.id" source="annonces" [alt]="'Photo ' + (i + 1)" />
              @if (i === 0) { <span class="badge">Principale</span> }
              @if (act.photos) {
                <div class="outils">
                  <button type="button" [disabled]="i === 0 || occupe()" (click)="deplacer(i, -1)" aria-label="Avancer la photo"><fact-icone nom="gauche" [taille]="16" /></button>
                  <button type="button" [disabled]="dernier || occupe()" (click)="deplacer(i, 1)" aria-label="Reculer la photo"><fact-icone nom="droite" [taille]="16" /></button>
                  <button type="button" [disabled]="occupe()" (click)="supprimerPhoto(p.id)" aria-label="Retirer la photo"><fact-icone nom="fermer" [taille]="16" /></button>
                </div>
              }
            </li>
          }
          @if (act.photos) {
            <li class="ajout">
              <label [class.occupe]="envoiPhotos()">
                <input type="file" accept="image/*" multiple (change)="ajouterPhotos($event)" [disabled]="envoiPhotos()">
                <fact-icone nom="photo" [taille]="22" /> <span>{{ envoiPhotos() ? 'Envoi…' : 'Ajouter' }}</span>
              </label>
            </li>
          }
        </ul>
      </section>

      <!-- ---------------------------------------------------------- formulaire -->
      @if (act.modifier) {
        @if (a.statut === 'PUBLIEE' && !valideur()) {
          <p class="aide">Une modification renvoie l'annonce à la validation avant qu'elle ne reste en ligne.</p>
        }
        <ng-container *ngTemplateOutlet="formulaire" />
      } @else {
        <dl class="resume">
          <div><dt>Prix</dt><dd class="donnee">{{ f.fcfa(a.prix) }}{{ a.operation === 'LOCATION' ? ' / mois' : '' }}</dd></div>
          @if (a.charges) { <div><dt>Charges</dt><dd class="donnee">{{ f.fcfa(a.charges) }}</dd></div> }
          <div><dt>Position sur le site</dt><dd>{{ a.positionExacte ? 'Exacte' : 'Zone approximative' }}</dd></div>
        </dl>
        @if (a.description) { <p class="description">{{ a.description }}</p> }
      }

      <!-- ------------------------------------------------------------- étapes -->
      @if (motifOuvert(); as m) {
        <form class="motif" (ngSubmit)="confirmerMotif()" novalidate>
          <label class="champ"><input type="text" name="motif" placeholder=" " [(ngModel)]="motif" autocomplete="off">
            <span>{{ m === 'refuser' ? 'Ce qu\\'il faut revoir' : 'Motif du retrait (facultatif)' }}</span></label>
          <div class="pied">
            <button type="button" class="btn" (click)="motifOuvert.set(null)">Annuler</button>
            <button type="submit" class="btn" [class.danger]="m === 'retirer'" [class.principal]="m === 'refuser'" [disabled]="occupe()">
              {{ m === 'refuser' ? 'Renvoyer au rédacteur' : 'Retirer du site' }}</button>
          </div>
        </form>
      } @else {
        <div class="actions">
          @if (act.retirer) { <button type="button" class="btn discret" (click)="ouvrirMotif('retirer')">Retirer</button> }
          @if (act.aLaUne) {
            <button type="button" class="btn" (click)="basculerUne()" [disabled]="occupe()"><fact-icone nom="etoile" [taille]="16" /> {{ a.aLaUne ? 'Retirer de la une' : 'Mettre à la une' }}</button>
          }
          @if (act.refuser) { <button type="button" class="btn" (click)="ouvrirMotif('refuser')">Renvoyer</button> }
          @if (act.valider) {
            <button type="button" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe() || fiche()!.manques.length > 0" (click)="etape('valider')">
              <fact-icone nom="coche" [taille]="18" /> Valider et mettre en ligne</button>
          }
          @if (act.soumettre) {
            <button type="button" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe() || fiche()!.manques.length > 0" (click)="etape('soumettre')">
              {{ valideur() ? 'Mettre en ligne' : 'Proposer à la validation' }}</button>
          }
        </div>
      }
      <p class="trace">
        Préparée le {{ f.date(a.creeLe) }}@if (a.creePar) { par {{ a.creePar }} }
        @if (a.soumiseLe) { · proposée le {{ f.date(a.soumiseLe) }} }
        @if (a.publieeLe) { · en ligne depuis le {{ f.date(a.publieeLe) }}@if (a.publieePar) { ({{ a.publieePar }}) } }
        @if (a.retireeLe) { · retirée le {{ f.date(a.retireeLe) }} }
      </p>
    }

    <ng-template #formulaire>
      <form class="formulaire" (ngSubmit)="enregistrer()" novalidate>
        <div class="segments" role="radiogroup" aria-label="Opération">
          <button type="button" role="radio" [attr.aria-checked]="s.operation === 'LOCATION'" [class.actif]="s.operation === 'LOCATION'" (click)="s.operation = 'LOCATION'">À louer</button>
          <button type="button" role="radio" [attr.aria-checked]="s.operation === 'VENTE'" [class.actif]="s.operation === 'VENTE'" (click)="s.operation = 'VENTE'">À vendre</button>
        </div>
        <label class="champ"><input type="text" name="titre" placeholder=" " maxlength="160" [(ngModel)]="s.titre" required autocomplete="off"><span>Titre de l'annonce</span></label>
        <label class="champ"><textarea name="description" placeholder=" " rows="5" [(ngModel)]="s.description"></textarea><span>Description (pièces, atouts, environnement)</span></label>
        <div class="duo">
          <label class="champ"><input type="number" name="prix" placeholder=" " min="1" [(ngModel)]="s.prix" required><span>{{ s.operation === 'VENTE' ? 'Prix de vente (FCFA)' : 'Loyer mensuel (FCFA)' }}</span></label>
          <label class="champ"><input type="number" name="charges" placeholder=" " min="0" [(ngModel)]="s.charges"><span>Charges (FCFA)</span></label>
        </div>
        @if (s.operation === 'LOCATION') {
          <div class="duo">
            <label class="champ"><input type="number" name="caution" placeholder=" " min="0" max="24" [(ngModel)]="s.cautionMois"><span>Caution (mois)</span></label>
            <label class="champ"><input type="number" name="avance" placeholder=" " min="0" max="24" [(ngModel)]="s.avanceMois"><span>Avance (mois)</span></label>
          </div>
        }
        <label class="case"><input type="checkbox" name="exacte" [(ngModel)]="s.positionExacte"> Montrer l'emplacement exact sur la carte du site
          <small>Sinon, le site montre seulement le quartier.</small></label>
        @if (erreur()) { <p class="message" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
        <div class="pied">
          <button type="submit" class="btn" [class.principal]="!fiche()" [class.occupe]="occupe()" [disabled]="occupe()">
            {{ fiche() ? 'Enregistrer le texte' : 'Préparer l\\'annonce' }}</button>
        </div>
      </form>
    </ng-template>`,
  styles: [`
    :host { display: grid; gap: 16px; }
    .squelette { height: 260px; border-radius: var(--rayon); background: var(--surface-3); animation: pulse 1.2s ease-in-out infinite; }
    .intro { display: flex; gap: 14px; align-items: flex-start; padding: 14px; border-radius: var(--rayon-2); background: var(--nuit-clair); }
    .intro h3 { font-size: 15.5px; margin: 0 0 4px; }
    .intro p { margin: 0; font-size: 14px; color: var(--encre-2); }
    .rond { flex: none; width: 46px; height: 46px; border-radius: 50%; display: grid; place-items: center; background: var(--surface); color: var(--marque); }
    .entete { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; }
    .entete h3 { font-size: 17px; margin: 2px 0; }
    .surtitre { margin: 0; font-size: 12.5px; color: var(--discret); }
    .une { color: var(--ocre-texte); font-weight: 600; }
    .discret { margin: 0; font-size: 13.5px; color: var(--discret); }
    .lien-site { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; color: var(--marque); }
    .controle { padding: 12px 14px; border-radius: var(--rayon-2); background: var(--attention-clair); color: var(--attention-texte); font-size: 14px; }
    .controle ul { margin: 6px 0 0; padding-left: 20px; }
    .controle.complet { background: var(--bon-clair); color: var(--bon-texte); }
    .controle.complet strong { display: inline-flex; gap: 6px; align-items: center; }
    h4 { margin: 0 0 8px; font-size: 14px; }
    h4 small { font-weight: 400; color: var(--discret); }
    .photos { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(104px, 1fr)); gap: 8px; }
    .photos li { position: relative; aspect-ratio: 4 / 3; border-radius: 10px; overflow: hidden; animation: montee var(--standard) var(--courbe) both; }
    .photos li.principale { outline: 3px solid var(--ocre); outline-offset: -3px; }
    fact-photo-securisee { width: 100%; height: 100%; }
    .badge { position: absolute; left: 6px; top: 6px; padding: 2px 7px; border-radius: 8px; background: var(--ocre); color: #fff; font-size: 11px; font-weight: 600; }
    .outils { position: absolute; inset: auto 0 0 0; display: flex; justify-content: center; gap: 4px; padding: 4px;
              background: linear-gradient(0deg, rgba(0,0,0,.55), transparent); opacity: 0; transition: opacity var(--courte) var(--courbe); }
    .photos li:hover .outils, .photos li:focus-within .outils { opacity: 1; }
    @media (hover: none) { .outils { opacity: 1; } }
    .outils button { width: 30px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 8px; background: rgba(255,255,255,.92); color: var(--encre); }
    .outils button:disabled { opacity: .4; }
    .ajout label { position: absolute; inset: 0; display: grid; place-content: center; justify-items: center; gap: 4px; border: 2px dashed var(--filet-fort);
                   border-radius: 10px; color: var(--marque); font-size: 13px; cursor: pointer; transition: background var(--courte) var(--courbe); }
    .ajout label:hover { background: var(--surface-2); }
    .ajout input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
    .formulaire { display: grid; gap: 12px; }
    .duo { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .segments { display: flex; gap: 2px; padding: 4px; border-radius: 12px; background: var(--surface-3); width: max-content; }
    .segments button { min-height: 38px; padding: 0 16px; border: 0; border-radius: 9px; background: none; color: var(--encre-2); font-weight: 500; }
    .segments button.actif { background: var(--surface); color: var(--marque); font-weight: 600; box-shadow: 0 0 0 1px var(--filet); }
    .case { flex-wrap: wrap; }
    .case small { flex-basis: 100%; color: var(--discret); font-size: 12.5px; padding-left: 28px; }
    .message { display: flex; gap: 9px; margin: 0; padding: 11px 13px; border-radius: var(--rayon-2); background: var(--critique-clair); color: var(--critique-texte); font-size: 14px; }
    .message.neutre { background: var(--surface-3); color: var(--encre-2); }
    .aide { margin: 0; font-size: 13px; color: var(--attention-texte); }
    .pied, .actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
    .motif { display: grid; gap: 10px; padding: 12px; border-radius: var(--rayon-2); border: 1px solid var(--filet); animation: montee var(--standard) var(--courbe) both; }
    .resume { margin: 0; display: grid; gap: 4px; }
    .resume div { display: flex; justify-content: space-between; gap: 12px; font-size: 14px; }
    .resume dt { color: var(--discret); }
    .resume dd { margin: 0; }
    .description { margin: 0; white-space: pre-line; font-size: 14px; color: var(--encre-2); }
    .trace { margin: 0; font-size: 12.5px; color: var(--discret); }
  `],
})
export class FicheAnnonceComposant {
  private readonly annonces = inject(AnnoncesService);
  private readonly avis = inject(AvisService);
  private readonly auth = inject(AuthService);

  /** Depuis la fiche d'un lot. */
  readonly idLot = input<number | null>(null);
  /** Depuis la rubrique Annonces. */
  readonly idAnnonce = input<number | null>(null);
  /** L'annonce a changé (statut, texte, photos) : la liste ou le lot se mettent à jour. */
  readonly change = output<void>();

  protected readonly chargement = signal(true);
  protected readonly fiche = signal<FicheAnnonce | null>(null);
  protected readonly lot = signal<Lot | null>(null);
  protected readonly photosMinimum = signal(3);
  protected readonly occupe = signal(false);
  protected readonly envoiPhotos = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly motifOuvert = signal<'refuser' | 'retirer' | null>(null);
  protected motif = '';
  protected s: AnnonceSaisie = this.vide(0);
  protected readonly f = { date, fcfa };
  protected readonly valideur = computed(() => this.auth.a(DROITS.annonceValider));

  constructor() {
    effect(() => {
      const idLot = this.idLot(), idAnnonce = this.idAnnonce();
      untracked(() => this.charger(idLot, idAnnonce));
    });
  }

  protected ton(statut: string): string {
    return TONS[statut] ?? 'neutre';
  }

  protected libre(l: Lot): boolean {
    return ['VACANT', 'A_LOUER', 'A_VENDRE', 'RESERVE'].includes(l.statut) && !l.archiveLe;
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      this.poser(await this.annonces.enregistrer(this.s));
      this.avis.succes(this.s.id ? 'Annonce enregistrée.' : 'Annonce préparée : ajoutez maintenant les photos.');
      this.change.emit();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async etape(action: 'soumettre' | 'valider'): Promise<void> {
    await this.agir(() => this.annonces.action(this.fiche()!.annonce.id, action), (f) =>
      f.annonce.statut === 'PUBLIEE' ? 'L\'annonce est en ligne.' : 'Annonce proposée : l\'éditeur web ou la Direction va la vérifier.');
  }

  protected ouvrirMotif(m: 'refuser' | 'retirer'): void {
    this.motif = '';
    this.motifOuvert.set(m);
  }

  protected async confirmerMotif(): Promise<void> {
    const m = this.motifOuvert();
    if (!m) return;
    await this.agir(() => this.annonces.avecMotif(this.fiche()!.annonce.id, m, this.motif),
      () => (m === 'refuser' ? 'Annonce renvoyée au rédacteur.' : 'Annonce retirée du site.'));
    this.motifOuvert.set(null);
  }

  protected async basculerUne(): Promise<void> {
    const a = this.fiche()!.annonce;
    await this.agir(() => this.annonces.aLaUne(a.id, !a.aLaUne), (f) => (f.annonce.aLaUne ? 'Annonce mise à la une.' : 'Annonce retirée de la une.'));
  }

  protected async ajouterPhotos(e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichiers = Array.from(champ.files ?? []);
    champ.value = '';
    if (!fichiers.length) return;
    this.envoiPhotos.set(true);
    try {
      this.poser(await this.annonces.ajouterPhotos(this.fiche()!.annonce.id, fichiers));
      this.avis.succes(`${fichiers.length} photo(s) ajoutée(s).`);
      this.change.emit();
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    } finally {
      this.envoiPhotos.set(false);
    }
  }

  protected async supprimerPhoto(id: number): Promise<void> {
    await this.agir(() => this.annonces.supprimerPhoto(id), () => 'Photo retirée.');
  }

  protected async deplacer(i: number, sens: number): Promise<void> {
    const ids = this.fiche()!.photos.map((p) => p.id);
    [ids[i], ids[i + sens]] = [ids[i + sens], ids[i]];
    await this.agir(() => this.annonces.ordonner(this.fiche()!.annonce.id, ids), () => null);
  }

  private async agir(appel: () => Promise<FicheAnnonce>, message: (f: FicheAnnonce) => string | null): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    try {
      const f = await appel();
      this.poser(f);
      const m = message(f);
      if (m) this.avis.succes(m);
      this.change.emit();
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  private poser(f: FicheAnnonce): void {
    this.fiche.set(f);
    const a = f.annonce;
    this.s = {
      id: a.id, idLot: a.idLot, operation: a.operation, titre: a.titre, description: a.description ?? '', prix: a.prix,
      charges: a.charges, cautionMois: a.cautionMois, avanceMois: a.avanceMois, positionExacte: a.positionExacte,
    };
  }

  private async charger(idLot: number | null, idAnnonce: number | null): Promise<void> {
    this.chargement.set(true);
    this.erreur.set(null);
    try {
      if (idAnnonce) {
        this.poser(await this.annonces.fiche(idAnnonce));
      } else if (idLot) {
        const r = await this.annonces.duLot(idLot);
        this.lot.set(r.lot);
        this.photosMinimum.set(r.photosMinimum);
        if (r.annonce && r.photos && r.actions && r.manques) {
          this.poser(r as FicheAnnonce);
        } else {
          this.fiche.set(null);
          this.s = this.vide(idLot, r.lot);
        }
      }
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private vide(idLot: number, l?: Lot): AnnonceSaisie {
    return {
      id: null, idLot, operation: l?.statut === 'A_VENDRE' ? 'VENTE' : 'LOCATION',
      titre: l ? `${l.libelleType}${l.pieces ? ', ' + l.pieces + ' pièces' : ''}` : '', description: l?.description ?? '',
      prix: l?.loyerReference ?? null, charges: l?.charges ?? 0, cautionMois: 2, avanceMois: 2, positionExacte: false,
    };
  }
}
