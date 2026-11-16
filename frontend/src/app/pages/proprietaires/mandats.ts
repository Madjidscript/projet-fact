import { Component, computed, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { Icone } from '../../composants/icone';
import { messageErreur } from '../../noyau/api.interceptor';
import { AuthService } from '../../noyau/auth.service';
import { AvisService } from '../../noyau/avis.service';
import { date, fcfa } from '../../noyau/format';
import { Batiment, DROITS } from '../../noyau/modeles';
import { Mandat, MandatSaisie, RapportsService, Releve, moisIso } from '../../noyau/rapports.service';
import { ReferentielService } from '../../noyau/referentiel.service';

type Mode = 'liste' | 'formulaire' | 'resiliation';

const STATUTS: Record<Mandat['statut'], { libelle: string; ton: string }> = {
  ACTIF: { libelle: 'En cours', ton: 'bon' },
  A_VENIR: { libelle: 'À venir', ton: 'info' },
  ECHU: { libelle: 'Échu', ton: 'critique' },
  RESILIE: { libelle: 'Résilié', ton: 'neutre' },
};

/**
 * Dans la fiche d'un propriétaire : ses mandats (durée, honoraires, seuil de travaux) et son
 * relevé de gestion. La Direction saisit, modifie et résilie les mandats ; la Direction et le
 * propriétaire lui-même éditent le relevé.
 */
@Component({
  selector: 'fact-mandats',
  imports: [FormsModule, Icone],
  template: `
    <!-- ---------------------------------------------------------------- mandats -->
    <section class="bloc">
      <div class="titre">
        <h3>Mandat de gestion</h3>
        @if (direction() && mode() === 'liste') {
          <button type="button" class="btn petit" (click)="nouveau()"><fact-icone nom="plus" [taille]="16" /> Nouveau mandat</button>
        }
      </div>

      @switch (mode()) {
        @case ('liste') {
          @if (!enVigueur() && mandats().length === 0) {
            <p class="vide"><fact-icone nom="info" [taille]="18" /> Aucun mandat saisi : sans taux d'honoraires, le relevé ne peut pas déduire la part de l'agence.</p>
            @if (direction()) { <button type="button" class="btn principal" (click)="nouveau()"><fact-icone nom="plus" [taille]="16" /> Saisir le mandat</button> }
          }
          <ul class="mandats">
            @for (m of mandats(); track m.id) {
              <li class="mandat" [class.passe]="m.statut === 'RESILIE' || m.statut === 'ECHU'">
                <div class="ligne1">
                  <strong>{{ m.reference }} · {{ m.libelleType }}</strong>
                  <span class="pastille" [class]="statut(m).ton">{{ statut(m).libelle }}</span>
                </div>
                <p>Depuis le {{ f.date(m.dateDebut) }}, pour {{ m.dureeMois }} mois{{ m.reconductionTacite ? ', reconduit d\\'office' : '' }}.</p>
                @if (m.statut === 'ACTIF' || m.statut === 'A_VENIR') {
                  <p [class.alerte]="m.joursRestants <= 90">Échéance le <strong>{{ f.date(m.dateFin) }}</strong> ({{ m.joursRestants }} jour{{ m.joursRestants > 1 ? 's' : '' }})</p>
                }
                @if (m.resilieLe) { <p>Résilié le {{ f.date(m.resilieLe) }} : {{ m.motifResiliation }}</p> }
                <dl>
                  <div><dt>Honoraires</dt><dd class="donnee">{{ taux(m.tauxHonoraires) }} des loyers encaissés</dd></div>
                  <div><dt>Accord exigé au-delà de</dt><dd class="donnee">{{ m.seuilTravaux ? f.fcfa(m.seuilTravaux) : f.fcfa(seuilGeneral()) + ' (réglage général)' }}</dd></div>
                  <div><dt>Biens couverts</dt><dd>{{ m.tousLesBiens ? 'Tous ses bâtiments' : nomsBatiments(m) }}</dd></div>
                </dl>
                @if (m.notes) { <p class="notes">{{ m.notes }}</p> }
                <div class="document">
                  <fact-icone nom="document" [taille]="18" />
                  @if (m.documentNom) {
                    <button type="button" class="lien-doc" (click)="ouvrirDocument(m)">{{ m.documentNom }}</button>
                    <small>déposé le {{ f.date(m.documentLe!) }}, visible par le propriétaire</small>
                  } @else {
                    <small>Mandat signé pas encore déposé</small>
                  }
                  @if (peutDeposer()) {
                    <label class="btn petit discret depot" [class.occupe]="depot() === m.id">
                      <input type="file" accept="application/pdf,image/jpeg,image/png" (change)="deposer(m, $event)" hidden>
                      {{ m.documentNom ? 'Remplacer' : 'Déposer le mandat signé' }}
                    </label>
                  }
                </div>
                @if (direction() && !m.resilieLe) {
                  <div class="actions">
                    <button type="button" class="btn discret petit" (click)="resilier(m)">Résilier</button>
                    <button type="button" class="btn petit" (click)="modifier(m)"><fact-icone nom="crayon" [taille]="15" /> Modifier</button>
                  </div>
                }
              </li>
            }
          </ul>
        }

        @case ('formulaire') {
          <form class="formulaire" (ngSubmit)="enregistrer()" novalidate>
            <label class="champ">
              <select name="type" [(ngModel)]="s.type">
                @for (v of ref.liste('TYPE_MANDAT'); track v.code) { <option [value]="v.code">{{ v.libelle }}</option> }
              </select>
              <span>Type de mandat</span>
            </label>
            <div class="duo">
              <label class="champ"><input type="date" name="dateDebut" placeholder=" " [(ngModel)]="s.dateDebut" required><span>Début</span></label>
              <label class="champ"><input type="number" name="dureeMois" placeholder=" " min="1" max="240" [(ngModel)]="s.dureeMois"><span>Durée (mois)</span></label>
            </div>
            <label class="case"><input type="checkbox" name="reconduction" [(ngModel)]="s.reconductionTacite"> Reconduit d'office à l'échéance s'il n'est pas dénoncé</label>
            <div class="duo">
              <label class="champ"><input type="number" name="taux" placeholder=" " min="0" max="50" step="0.5" [(ngModel)]="s.tauxHonoraires"><span>Honoraires (%)</span></label>
              <label class="champ">
                <input type="number" name="seuil" placeholder=" " min="0" step="5000" [(ngModel)]="s.seuilTravaux">
                <span>Accord du propriétaire au-delà de (FCFA)</span>
              </label>
            </div>
            <p class="aide">Laissez le montant vide pour appliquer le réglage général ({{ f.fcfa(seuilGeneral()) }}).</p>
            <fieldset>
              <legend>Biens couverts</legend>
              <label class="case"><input type="checkbox" name="tous" [(ngModel)]="s.tousLesBiens"> Tous ses bâtiments, y compris ceux ajoutés plus tard</label>
              @if (!s.tousLesBiens) {
                <div class="liste-cases">
                  @for (b of batiments(); track b.id) {
                    <label class="case"><input type="checkbox" [checked]="s.batiments.includes(b.id)" (change)="basculer(b.id)"> {{ b.nom }}</label>
                  } @empty { <p class="aide">Ce propriétaire n'a encore aucun bâtiment.</p> }
                </div>
              }
            </fieldset>
            <label class="champ"><textarea name="notes" placeholder=" " [(ngModel)]="s.notes"></textarea><span>Notes (conditions particulières)</span></label>
            @if (erreur()) { <p class="message" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
            <div class="pied">
              <button type="button" class="btn" (click)="mode.set('liste')">Annuler</button>
              <button type="submit" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe()">Enregistrer le mandat</button>
            </div>
          </form>
        }

        @case ('resiliation') {
          <form class="formulaire" (ngSubmit)="confirmerResiliation()" novalidate>
            <p class="aide">Le mandat {{ aResilier()?.reference }} prendra fin à cette date. Il reste consultable dans la fiche.</p>
            <label class="champ"><input type="date" name="dateResiliation" placeholder=" " [(ngModel)]="resiliation.date"><span>Fin du mandat</span></label>
            <label class="champ"><input type="text" name="motif" placeholder=" " [(ngModel)]="resiliation.motif" autocomplete="off"><span>Motif</span></label>
            @if (erreur()) { <p class="message" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
            <div class="pied">
              <button type="button" class="btn" (click)="mode.set('liste')">Annuler</button>
              <button type="submit" class="btn danger" [class.occupe]="occupe()" [disabled]="occupe()">Résilier le mandat</button>
            </div>
          </form>
        }
      }
    </section>

    <!-- ----------------------------------------------------------------- relevé -->
    @if (voitReleve() && mode() === 'liste') {
      <section class="bloc">
        <h3>Relevé de gestion</h3>
        <p class="aide">Loyers encaissés, honoraires de l'agence, travaux à sa charge et montant à lui reverser.</p>
        <div class="duo">
          <label class="champ"><input type="month" name="du" [max]="maintenant" [ngModel]="du()" (ngModelChange)="du.set($event); releve.set(null)"><span>Du mois</span></label>
          <label class="champ"><input type="month" name="au" [max]="maintenant" [ngModel]="au()" (ngModelChange)="au.set($event); releve.set(null)"><span>Au mois</span></label>
        </div>
        <div class="pied">
          <button type="button" class="btn" [class.occupe]="calcul()" [disabled]="calcul()" (click)="calculer()">Voir les montants</button>
          <button type="button" class="btn principal" [class.occupe]="pdf()" [disabled]="pdf()" (click)="telecharger()"><fact-icone nom="telecharger" [taille]="16" /> Relevé PDF</button>
        </div>
        @if (releve(); as r) {
          <dl class="totaux">
            <div><dt>Loyers encaissés</dt><dd class="donnee">{{ f.fcfa(r.totaux.encaisse) }}</dd></div>
            <div><dt>Honoraires</dt><dd class="donnee">{{ moins(r.totaux.honoraires) }}</dd></div>
            <div><dt>Travaux à sa charge</dt><dd class="donnee">{{ moins(r.totaux.travaux) }}</dd></div>
            <div class="total" [class.negatif]="r.totaux.aReverser < 0"><dt>À lui reverser</dt><dd class="donnee">{{ f.fcfa(r.totaux.aReverser) }}</dd></div>
          </dl>
          @if (sansMandat(r)) { <p class="aide alerte">Certains bâtiments n'ont pas de mandat en vigueur : aucun honoraire n'y est déduit.</p> }
        }
      </section>
    }`,
  styles: [`
    :host { display: grid; gap: 22px; margin-top: 22px; }
    .bloc { display: grid; gap: 12px; }
    .titre { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    h3 { font-size: 15px; margin: 0; }
    .vide { display: flex; gap: 8px; align-items: flex-start; margin: 0; padding: 12px; border-radius: var(--rayon-2);
            background: var(--attention-clair); color: var(--attention-texte); font-size: 14px; }
    .mandats { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .mandat { display: grid; gap: 6px; padding: 14px; border: 1px solid var(--filet); border-radius: var(--rayon-2); background: var(--surface-2);
              animation: montee var(--standard) var(--courbe) both; }
    .mandat.passe { opacity: .7; }
    .ligne1 { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .mandat p { margin: 0; font-size: 14px; color: var(--encre-2); }
    .mandat p.alerte { color: var(--attention-texte); }
    .mandat .notes { font-style: italic; }
    .mandat .document { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; margin-top: 8px; padding: 8px 10px; border-radius: var(--rayon-2); background: var(--surface-2); color: var(--encre-2); }
    .mandat .document small { color: var(--discret); }
    .mandat .lien-doc { border: 0; background: none; padding: 0; font: 600 13.5px var(--texte); color: var(--marque); cursor: pointer; text-decoration: underline; }
    .mandat .depot { margin-left: auto; cursor: pointer; }
    dl { margin: 4px 0 0; display: grid; gap: 4px; }
    dl div { display: flex; justify-content: space-between; gap: 12px; font-size: 13.5px; }
    dt { color: var(--discret); }
    dd { margin: 0; text-align: right; }
    .actions, .pied { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
    .formulaire { display: grid; gap: 12px; }
    .duo { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    fieldset { border: 0; padding: 0; margin: 0; display: grid; gap: 8px; }
    legend { font-size: 13px; font-weight: 600; color: var(--encre-2); margin-bottom: 6px; }
    .liste-cases { display: grid; gap: 6px; padding-left: 4px; }
    .aide { margin: 0; font-size: 13px; color: var(--discret); }
    .aide.alerte { color: var(--attention-texte); }
    .message { display: flex; gap: 9px; margin: 0; padding: 11px 13px; border-radius: var(--rayon-2);
               background: var(--critique-clair); color: var(--critique-texte); font-size: 14px; }
    .totaux { padding: 12px 14px; border-radius: var(--rayon-2); border: 1px solid var(--filet); animation: montee var(--standard) var(--courbe) both; }
    .totaux .total { margin-top: 4px; padding-top: 8px; border-top: 1px solid var(--filet); font-weight: 700; }
    .totaux .total dd { color: var(--bon-texte); }
    .totaux .total.negatif dd { color: var(--critique-texte); }
  `],
})
export class Mandats {
  private readonly rapports = inject(RapportsService);
  private readonly avis = inject(AvisService);
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  protected readonly depot = signal<number | null>(null);
  protected readonly peutDeposer = computed(() => this.auth.a(DROITS.patrimoineEcrire));
  protected readonly ref = inject(ReferentielService);

  readonly idProprietaire = input.required<number>();
  readonly reference = input.required<string>();
  readonly idCompte = input<number | null>(null);
  readonly batiments = input<Batiment[]>([]);
  readonly mandats = input<Mandat[]>([]);
  /** Un mandat a été créé, modifié ou résilié : la fiche se recharge. */
  readonly change = output<void>();

  protected readonly mode = signal<Mode>('liste');
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly aResilier = signal<Mandat | null>(null);
  protected s: MandatSaisie = this.vide();
  protected resiliation = { date: moisIso(new Date()) + '-' + String(new Date().getDate()).padStart(2, '0'), motif: '' };

  protected readonly maintenant = moisIso(new Date());
  protected readonly du = signal(moisIso(this.moisPrecedent()));
  protected readonly au = signal(moisIso(this.moisPrecedent()));
  protected readonly releve = signal<Releve | null>(null);
  protected readonly calcul = signal(false);
  protected readonly pdf = signal(false);
  protected readonly f = { date, fcfa };

  protected readonly direction = computed(() => ['DIRECTION', 'ADMIN'].includes(this.auth.compte()?.profil ?? ''));
  protected readonly voitReleve = computed(() => this.direction() || (this.auth.compte()?.profil === 'PROPRIETAIRE'));
  protected readonly enVigueur = computed(() => this.mandats().some((m) => m.statut === 'ACTIF'));
  protected readonly seuilGeneral = computed(() => this.ref.reglage('travaux.seuil_accord_proprietaire', 150000));

  protected statut(m: Mandat): { libelle: string; ton: string } {
    return STATUTS[m.statut];
  }

  protected taux(t: number): string {
    return String(t).replace('.', ',') + ' %';
  }

  protected nomsBatiments(m: Mandat): string {
    return m.batiments.map((b) => b.nom).join(', ') || '-';
  }

  protected nouveau(): void {
    this.s = this.vide();
    this.erreur.set(null);
    this.mode.set('formulaire');
  }

  /** Dépose le mandat signé (PDF ou photo) : le propriétaire le retrouve dans son espace. */
  protected async deposer(m: Mandat, e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (!fichier) return;
    if (fichier.size > 10 * 1024 * 1024) {
      this.avis.erreur('Le fichier dépasse 10 Mo : réduisez-le ou photographiez les pages.');
      return;
    }
    const donnees = new FormData();
    donnees.append('fichier', fichier);
    this.depot.set(m.id);
    try {
      await firstValueFrom(this.http.post(`/api/mandats/${m.id}/document`, donnees));
      this.avis.succes('Mandat signé déposé : il est visible dans l\'espace du propriétaire.');
      this.change.emit();
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    } finally {
      this.depot.set(null);
    }
  }

  protected async ouvrirDocument(m: Mandat): Promise<void> {
    try {
      const blob = await firstValueFrom(this.http.get(`/api/mandats/${m.id}/document`, { responseType: 'blob' }));
      const adresse = URL.createObjectURL(blob);
      window.open(adresse, '_blank');
      setTimeout(() => URL.revokeObjectURL(adresse), 60_000);
    } catch (err) {
      this.avis.erreur(messageErreur(err));
    }
  }

  protected modifier(m: Mandat): void {
    this.s = {
      id: m.id, idProprietaire: m.idProprietaire, type: m.type, dateDebut: m.dateDebut, dureeMois: m.dureeMois,
      reconductionTacite: m.reconductionTacite, tauxHonoraires: m.tauxHonoraires, seuilTravaux: m.seuilTravaux,
      tousLesBiens: m.tousLesBiens, batiments: m.batiments.map((b) => b.id), notes: m.notes ?? '',
    };
    this.erreur.set(null);
    this.mode.set('formulaire');
  }

  protected basculer(id: number): void {
    this.s.batiments = this.s.batiments.includes(id) ? this.s.batiments.filter((x) => x !== id) : [...this.s.batiments, id];
  }

  protected async enregistrer(): Promise<void> {
    if (this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const m = await this.rapports.enregistrerMandat({ ...this.s, idProprietaire: this.idProprietaire() });
      this.avis.succes(`Mandat ${m.reference} enregistré.`);
      this.mode.set('liste');
      this.change.emit();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected resilier(m: Mandat): void {
    this.aResilier.set(m);
    this.resiliation = { date: new Date().toISOString().slice(0, 10), motif: '' };
    this.erreur.set(null);
    this.mode.set('resiliation');
  }

  protected async confirmerResiliation(): Promise<void> {
    const m = this.aResilier();
    if (!m || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      await this.rapports.resilierMandat(m.id, this.resiliation.date, this.resiliation.motif);
      this.avis.succes(`Mandat ${m.reference} résilié.`);
      this.mode.set('liste');
      this.change.emit();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  protected async calculer(): Promise<void> {
    this.calcul.set(true);
    try {
      this.releve.set(await this.rapports.releve(this.idProprietaire(), this.du(), this.au()));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.calcul.set(false);
    }
  }

  protected async telecharger(): Promise<void> {
    this.pdf.set(true);
    try {
      await this.rapports.relevePdf(this.idProprietaire(), this.reference(), this.du(), this.au());
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.pdf.set(false);
    }
  }

  /** « - 553 200 FCFA », mais « 0 FCFA » quand il n'y a rien à déduire. */
  protected moins(n: number): string {
    return n ? '- ' + fcfa(n) : fcfa(0);
  }

  protected sansMandat(r: Releve): boolean {
    return r.batiments.some((b) => !b.mandat);
  }

  private vide(): MandatSaisie {
    return {
      id: null, idProprietaire: 0, type: 'GESTION_LOCATIVE', dateDebut: new Date().toISOString().slice(0, 10), dureeMois: 12,
      reconductionTacite: true, tauxHonoraires: this.ref.reglage('mandat.taux_honoraires_defaut', 10), seuilTravaux: null,
      tousLesBiens: true, batiments: [], notes: '',
    };
  }

  private moisPrecedent(): Date {
    const d = new Date();
    d.setDate(1);
    d.setMonth(d.getMonth() - 1);
    return d;
  }
}
