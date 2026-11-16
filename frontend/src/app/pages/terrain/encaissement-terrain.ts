import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Choix, OptionChoix, OptionsPipe } from '../../composants/choix';
import { Icone } from '../../composants/icone';
import { BailTerrain, BatimentTerrain, TerrainService, maPosition } from '../../noyau/terrain.service';
import { EnteteTerrain } from './entete-terrain';

const MODES = [
  { code: 'ESPECES', libelle: 'Espèces', icone: 'monnaie' },
  { code: 'MOBILE_MONEY', libelle: 'Mobile money', icone: 'telephone' },
  { code: 'CHEQUE', libelle: 'Chèque', icone: 'document' },
  { code: 'VIREMENT', libelle: 'Virement', icone: 'synchro' },
];

/**
 * L'encaissement sur le terrain (§ 4.6) : l'agent choisit le bâtiment puis le lot, saisit le
 * montant et le mode, confirme ; le paiement part à l'agence (tout de suite, ou au retour du
 * réseau) et le reçu est envoyé au locataire, par WhatsApp et par e-mail s'il en a un.
 */
@Component({
  selector: 'fact-encaissement-terrain',
  imports: [FormsModule, RouterLink, Icone, Choix, OptionsPipe, EnteteTerrain],
  template: `
    <div class="tr">
      <fact-entete-terrain titre="Encaisser un loyer" retour="/terrain" />

      @if (fini(); as f) {
        <section class="tr-fini carte" role="status">
          <svg class="tr-coche" viewBox="0 0 52 52" aria-hidden="true"><circle cx="26" cy="26" r="23" /><path d="m15 27 7.500 7.500L37 19" /></svg>
          <h2>{{ fcfa(f.montant) }} encaissés</h2>
          <p>{{ f.occupant }} · lot {{ f.lot }}, {{ f.batiment }}</p>
          <p>{{ terrain.enLigne() ? 'Le paiement part à l\\'agence.' : 'Le paiement est gardé dans le téléphone et partira au retour du réseau.' }}
            @if (f.recu) { Le reçu sera envoyé au locataire{{ f.telephone ? ' par WhatsApp au ' + f.telephone : '' }}. }</p>
          <div class="en-actions">
            <button type="button" class="btn principal grand" (click)="recommencer()"><fact-icone nom="plus" [taille]="18" /> Autre encaissement</button>
            <a class="btn grand" routerLink="/terrain">Retour à l'accueil</a>
          </div>
        </section>
      } @else {
        <section class="tr-bloc">
          <h2>Quel logement ?</h2>
          <fact-choix name="batiment" libelle="Bâtiment" [options]="batiments() | options : optionBatiment" [(ngModel)]="idBatiment"
                      (ngModelChange)="bail.set(null)" />
          @if (idBatiment) {
            <ul class="en-lots">
              @for (b of baux(); track b.idBail; let i = $index) {
                <li [style.--rang]="i">
                  <button type="button" class="carte en-lot" [class.choisi]="bail()?.idBail === b.idBail" (click)="choisir(b)">
                    <span class="en-porte donnee">{{ b.numeroLot }}</span>
                    <span class="en-qui"><strong>{{ b.occupant }}</strong><small>{{ fcfa(b.loyerMensuel) }} / mois{{ b.telephone ? ' · ' + b.telephone : '' }}</small></span>
                    @if (b.impaye > 0) { <span class="pastille critique">{{ b.moisRetard }} mois · {{ fcfa(b.impaye) }}</span> }
                    @else { <span class="pastille bon">À jour</span> }
                  </button>
                </li>
              } @empty {
                <li class="tr-aide">Aucun bail en cours dans ce bâtiment.</li>
              }
            </ul>
          }
        </section>

        @if (bail(); as b) {
          <section class="tr-bloc en-saisie">
            <h2>Le paiement</h2>
            <label class="champ en-montant">
              <input type="number" inputmode="numeric" name="montant" placeholder=" " min="1" step="500" [(ngModel)]="montant">
              <span>Montant reçu (FCFA)</span>
            </label>
            <div class="en-raccourcis">
              <button type="button" class="btn petit" (click)="montant = b.loyerMensuel">1 mois · {{ fcfa(b.loyerMensuel) }}</button>
              @if (b.impaye > b.loyerMensuel) { <button type="button" class="btn petit" (click)="montant = b.impaye">Tout l'impayé · {{ fcfa(b.impaye) }}</button> }
            </div>
            <div class="en-modes" role="radiogroup" aria-label="Mode de paiement">
              @for (m of modes; track m.code) {
                <button type="button" role="radio" [attr.aria-checked]="mode === m.code" [class.actif]="mode === m.code" (click)="mode = m.code">
                  <fact-icone [nom]="m.icone" [taille]="20" />{{ m.libelle }}
                </button>
              }
            </div>
            @if (mode !== 'ESPECES') {
              <label class="champ"><input type="text" name="reference" placeholder=" " [(ngModel)]="reference" autocomplete="off">
                <span>{{ mode === 'MOBILE_MONEY' ? 'N° de transaction' : mode === 'CHEQUE' ? 'N° du chèque' : 'Référence du virement' }}</span></label>
            }
            <label class="case"><input type="checkbox" name="recu" [(ngModel)]="envoyerRecu" [disabled]="!b.telephone">
              Envoyer le reçu au locataire{{ b.telephone ? ' (WhatsApp ' + b.telephone + ')' : ' — pas de téléphone connu' }}</label>
          </section>

          @if (erreur()) { <p class="tr-erreur" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }

          @if (confirmation()) {
            <div class="en-voile" (click)="confirmation.set(false)"></div>
            <section class="carte en-confirmer" role="dialog" aria-modal="true" aria-label="Confirmer l'encaissement">
              <p>Vous confirmez avoir reçu</p>
              <strong class="en-somme">{{ fcfa(montant ?? 0) }}</strong>
              <p>de <strong>{{ b.occupant }}</strong>, lot {{ b.numeroLot }} · {{ b.batiment }}<br>en {{ libelleMode() }}{{ reference ? ' · ' + reference : '' }}</p>
              <div class="en-actions">
                <button type="button" class="btn grand" (click)="confirmation.set(false)">Corriger</button>
                <button type="button" class="btn principal grand" [class.occupe]="envoi()" [disabled]="envoi()" (click)="enregistrer(b)"><fact-icone nom="coche" [taille]="18" /> Confirmer</button>
              </div>
            </section>
          }

          <footer class="tr-pied">
            <button type="button" class="btn principal grand" (click)="verifier(b)"><fact-icone nom="monnaie" [taille]="18" /> Encaisser</button>
          </footer>
        }
      }
    </div>
  `,
  styles: [`
    .en-lots { list-style: none; margin: 12px 0 0; padding: 0; display: grid; gap: 8px; }
    .en-lots li { animation: montee var(--standard) var(--courbe) both; animation-delay: calc(var(--rang) * 40ms); }
    .en-lot { width: 100%; display: flex; flex-wrap: wrap; align-items: center; gap: 8px 10px; text-align: left; font: inherit; color: inherit; cursor: pointer;
              transition: border-color var(--courte) var(--courbe), box-shadow var(--courte) var(--courbe); }
    .en-lot.choisi { border-color: var(--ocre); box-shadow: 0 0 0 3px color-mix(in srgb, var(--ocre) 22%, transparent); }
    .en-porte { min-width: 50px; padding: 4px 6px; border-radius: 8px; background: var(--surface-3); text-align: center; font-size: 13px; }
    .en-qui { flex: 1 1 150px; display: grid; min-width: 0; }
    .en-lot .pastille { margin-left: auto; }
    .en-qui small { color: var(--discret); font-size: 12.5px; }
    .en-saisie { display: grid; gap: 12px; animation: montee var(--standard) var(--courbe) both; }
    .en-montant input { font: 700 24px var(--titre); }
    .en-raccourcis { display: flex; flex-wrap: wrap; gap: 6px; }
    .en-modes { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .en-modes button { display: flex; align-items: center; gap: 8px; min-height: 50px; padding: 0 12px; border-radius: var(--rayon-2);
                       border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre); font: 600 14px var(--texte); cursor: pointer;
                       transition: all var(--courte) var(--courbe); }
    .en-modes button.actif { border-color: var(--ocre); background: var(--ocre); color: #fff; }
    .en-voile { position: fixed; inset: 0; z-index: 1300; background: rgba(8, 15, 30, .5); animation: fondu var(--courte) both; }
    .en-confirmer { position: fixed; z-index: 1301; left: 12px; right: 12px; bottom: 12px; max-width: 480px; margin: 0 auto; display: grid;
                    gap: 6px; text-align: center; animation: montee var(--standard) var(--courbe) both; }
    .en-confirmer p { margin: 0; color: var(--encre-2); }
    .en-somme { font: 700 32px var(--titre); color: var(--marque); }
    .en-actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; margin-top: 10px; }
    .en-actions .btn { min-width: 0; white-space: normal; }
    @keyframes fondu { from { opacity: 0; } }
  `],
})
export class EncaissementTerrain implements OnInit {
  protected readonly terrain = inject(TerrainService);
  /** ?batiment=12 : arrivé depuis une étape de la tournée. */
  readonly batimentAdresse = input<string | undefined>(undefined, { alias: 'batiment' });

  protected readonly modes = MODES;
  protected idBatiment: number | null = null;
  protected readonly bail = signal<BailTerrain | null>(null);
  protected montant: number | null = null;
  protected mode = 'ESPECES';
  protected reference = '';
  protected envoyerRecu = true;
  protected readonly confirmation = signal(false);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly fini = signal<{ montant: number; occupant: string; lot: string; batiment: string; recu: boolean; telephone: string | null } | null>(null);
  private readonly selection = signal(0);

  protected readonly batiments = computed(() => {
    const baux = this.terrain.preparation()?.baux ?? [];
    const ids = new Set(baux.map((b) => b.idBatiment));
    return (this.terrain.preparation()?.batiments ?? []).filter((b) => ids.has(b.id));
  });
  protected readonly baux = computed(() => {
    this.selection();
    return (this.terrain.preparation()?.baux ?? []).filter((b) => b.idBatiment === this.idBatiment)
      .sort((a, b) => b.impaye - a.impaye || a.numeroLot.localeCompare(b.numeroLot, 'fr', { numeric: true }));
  });
  protected readonly optionBatiment = (b: BatimentTerrain): OptionChoix => {
    const impayes = (this.terrain.preparation()?.baux ?? []).filter((x) => x.idBatiment === b.id && x.impaye > 0).length;
    return { valeur: b.id, libelle: b.nom, detail: (b.quartier ? b.quartier + ', ' : '') + b.commune + (impayes ? ` · ${impayes} en retard` : '') };
  };

  async ngOnInit(): Promise<void> {
    if (!this.terrain.preparation()) await this.terrain.preparer();
    const b = Number(this.batimentAdresse());
    if (b) {
      this.idBatiment = b;
      this.selection.update((n) => n + 1);
    }
  }

  protected choisir(b: BailTerrain): void {
    this.bail.set(b);
    this.montant = b.impaye > 0 ? Math.min(b.impaye, b.loyerMensuel) : b.loyerMensuel;
    this.envoyerRecu = !!b.telephone;
    this.erreur.set(null);
    setTimeout(() => document.querySelector('.en-saisie')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 60);
  }

  protected libelleMode(): string {
    return MODES.find((m) => m.code === this.mode)?.libelle.toLowerCase() ?? this.mode;
  }

  protected verifier(b: BailTerrain): void {
    this.erreur.set(null);
    if (!this.montant || this.montant <= 0) {
      this.erreur.set('Saisissez le montant reçu.');
      return;
    }
    if (this.montant > Math.max(b.impaye, 0) + b.loyerMensuel * 12) {
      this.erreur.set('Ce montant dépasse un an de loyer d\'avance : vérifiez-le.');
      return;
    }
    if (this.mode === 'MOBILE_MONEY' && !this.reference.trim()) {
      this.erreur.set('Notez le numéro de la transaction mobile money : il prouve le paiement.');
      return;
    }
    this.confirmation.set(true);
  }

  protected async enregistrer(b: BailTerrain): Promise<void> {
    if (this.envoi()) return;
    this.envoi.set(true);
    let position: { latitude: number; longitude: number } | null = null;
    try {
      const p = await Promise.race([maPosition(), new Promise<null>((ok) => setTimeout(() => ok(null), 4000))]);
      if (p) position = { latitude: p.coords.latitude, longitude: p.coords.longitude };
    } catch { /* la position est un plus, pas une condition */ }
    try {
      const cle = this.terrain.nouvelleCle();
      const d = new Date();
      const jour = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      await this.terrain.mettreEnFile({
        cle, genre: 'encaissement', libelle: `Encaissement ${this.fcfa(this.montant!)}`, detail: `${b.occupant} · lot ${b.numeroLot}, ${b.batiment}`,
        donnees: { cle, idBail: b.idBail, datePaiement: jour, montant: this.montant, mode: this.mode, referencePaiement: this.reference.trim() || null,
          notes: 'Encaissé sur le terrain', latitude: position?.latitude ?? null, longitude: position?.longitude ?? null, envoyerRecu: this.envoyerRecu },
      });
      this.fini.set({ montant: this.montant!, occupant: b.occupant, lot: b.numeroLot, batiment: b.batiment, recu: this.envoyerRecu,
        telephone: this.envoyerRecu ? b.telephone : null });
      this.confirmation.set(false);
    } catch {
      this.erreur.set('Le téléphone n\'a pas pu garder ce paiement : libérez de la place et réessayez.');
    } finally {
      this.envoi.set(false);
    }
  }

  protected recommencer(): void {
    this.fini.set(null);
    this.bail.set(null);
    this.montant = null;
    this.reference = '';
    this.mode = 'ESPECES';
  }

  protected fcfa(n: number): string {
    return new Intl.NumberFormat('fr-FR').format(n).replace(/ | /g, ' ') + ' FCFA';
  }
}
