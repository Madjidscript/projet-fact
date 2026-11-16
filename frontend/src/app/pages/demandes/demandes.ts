import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Choix, OptionsPipe } from '../../composants/choix';
import { EtatVide } from '../../composants/etat-vide';
import { Icone } from '../../composants/icone';
import { Apparition } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { AnnoncesService, Demande, MessageAEnvoyer, PieceJointe, StatutDemande, lienWhatsApp } from '../../noyau/annonces.service';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { date, dateHeure } from '../../noyau/format';
import { depuis } from '../../noyau/travaux.service';

type Vue = 'nouvelles' | 'en-cours' | 'closes' | 'toutes' | 'messages';
const STATUTS: { code: StatutDemande; libelle: string; ton: string }[] = [
  { code: 'NOUVELLE', libelle: 'Nouvelle', ton: 'attention' },
  { code: 'PRISE_EN_CHARGE', libelle: 'Prise en charge', ton: 'info' },
  { code: 'RENDEZ_VOUS', libelle: 'Rendez-vous fixé', ton: 'accent' },
  { code: 'CONCLUE', libelle: 'Conclue', ton: 'bon' },
  { code: 'SANS_SUITE', libelle: 'Sans suite', ton: 'neutre' },
];
const ICONES: Record<string, string> = { VISITE: 'cle', MANDAT: 'batiment', DEVIS: 'outils', ESTIMATION: 'monnaie', CONTACT: 'courrier' };
/** Les champs libres des formulaires du site, en clair. */
const LIBELLES: Record<string, string> = {
  typeBien: 'Type de bien', commune: 'Commune', quartier: 'Quartier', lots: 'Nombre de lots', service: 'Service souhaité',
  metier: 'Métier', projet: 'Projet', terrain: 'Terrain disponible', surface: 'Surface (m²)', budget: 'Budget (FCFA)',
  natureTravaux: 'Nature des travaux', localisation: 'Localisation', objet: 'Objet',
};

/**
 * Le suivi des demandes du site (§ 3.8) : un tableau unique, les nouvelles d'abord (en rouge au-delà
 * de 24 heures ouvrées), et pour chacune les coordonnées, le bien, le message, les pièces jointes et
 * le changement de statut. La boîte d'envoi garde les accusés et les alertes à envoyer aux visiteurs.
 */
@Component({
  selector: 'fact-demandes',
  imports: [FormsModule, Icone, EtatVide, Apparition, PanneauLateral, Choix, OptionsPipe],
  template: `
    <header class="entete">
      <div>
        <h1>Demandes</h1>
        <p class="accroche">Visites, biens à confier, devis, estimations et messages venus du site.</p>
      </div>
    </header>

    <div class="segments" role="tablist" aria-label="Demandes">
      @for (v of [{ c: 'nouvelles', l: 'Nouvelles' }, { c: 'en-cours', l: 'En cours' }, { c: 'closes', l: 'Closes' }, { c: 'toutes', l: 'Toutes' }]; track v.c) {
        <button type="button" role="tab" [attr.aria-selected]="vue() === v.c" [class.actif]="vue() === v.c" (click)="vue.set($any(v.c))">
          {{ v.l }} <span class="nombre" [class.alerte]="v.c === 'nouvelles' && compte('nouvelles') > 0">{{ compte($any(v.c)) }}</span>
        </button>
      }
      @if (voitMessages()) {
        <button type="button" role="tab" [attr.aria-selected]="vue() === 'messages'" [class.actif]="vue() === 'messages'" (click)="ouvrirMessages()">
          Messages à envoyer <span class="nombre" [class.alerte]="messagesEnAttente() > 0">{{ messagesEnAttente() }}</span>
        </button>
      }
    </div>

    @if (chargement()) {
      <ul class="liste">@for (i of [1, 2, 3]; track i) { <li class="squelette"></li> }</ul>
    } @else if (vue() === 'messages') {
      <!-- --------------------------------------------------------- boîte d'envoi -->
      <p class="astuce"><fact-icone nom="info" [taille]="18" /> L'agence n'a pas encore de compte d'envoi automatique. Envoyez chaque message d'un geste, puis marquez-le comme envoyé.</p>
      <ul class="liste">
        @for (m of messages(); track m.id; let n = $index) {
          <li class="carte message-envoi" [factApparition]="n">
            <div class="ligne1">
              <span class="pastille" [class]="m.motif === 'ACCUSE' ? 'info' : m.motif === 'RELEVE' || m.motif === 'RECU' ? 'bon' : 'accent'">{{ m.motif === 'ACCUSE' ? 'Accusé de réception' : m.motif === 'RELEVE' ? 'Relevé du propriétaire' : m.motif === 'RECU' ? 'Reçu de paiement' : 'Alerte nouveau bien' }}</span>
              <small>{{ depuis(m.creeLe) }}@if (m.referenceDemande) { · {{ m.referenceDemande }} }</small>
            </div>
            <p class="dest"><fact-icone [nom]="m.canal === 'EMAIL' ? 'courrier' : 'whatsapp'" [taille]="16" /> <span class="donnee">{{ m.destinataire }}</span></p>
            <pre class="corps">{{ m.corps }}</pre>
            <div class="actions">
              <button type="button" class="btn discret petit" (click)="marquer(m, false)">Inutile</button>
              <a class="btn petit" [href]="lienEnvoi(m)" target="_blank" rel="noopener">
                <fact-icone [nom]="m.canal === 'EMAIL' ? 'courrier' : 'whatsapp'" [taille]="16" /> {{ m.canal === 'EMAIL' ? 'Écrire l\\'e-mail' : 'Ouvrir WhatsApp' }}</a>
              <button type="button" class="btn principal petit" (click)="marquer(m, true)"><fact-icone nom="coche" [taille]="16" /> Envoyé</button>
            </div>
          </li>
        } @empty {
          <li><fact-etat-vide icone="coche" titre="Tout est envoyé" message="Aucun message n'attend d'être envoyé." /></li>
        }
      </ul>
    } @else {
      @if (demandes().length > 6) {
        <div class="filtres">
          <label class="cherche">
            <fact-icone nom="recherche" [taille]="18" />
            <input type="search" placeholder="Nom, téléphone, référence, bien…" [ngModel]="recherche()" (ngModelChange)="recherche.set($event)" aria-label="Rechercher une demande">
          </label>
          <fact-choix class="filtre-type" vide="Tous les types" invite="Tous les types" [options]="types | options : optionType"
                      [ngModel]="type()" (ngModelChange)="type.set($event)" />
        </div>
      }
      <ul class="liste">
        @for (d of visibles(); track d.id; let n = $index) {
          <li [factApparition]="n">
            <button type="button" class="carte element" [class.urgente]="d.enRetard" (click)="ouvrir(d.id)">
              <span class="pictogramme" [class.attention]="d.statut === 'NOUVELLE'" [class.critique]="d.enRetard"><fact-icone [nom]="icone(d)" /></span>
              <span class="texte">
                <strong>{{ d.nom }}</strong>
                <small>{{ d.libelleType }}@if (d.annonce) { · {{ d.annonce }} }</small>
                <small><span class="donnee">{{ d.reference }}</span> · {{ depuis(d.creeLe) }}@if (d.traitePar) { · suivie par {{ d.traitePar }} }</small>
              </span>
              <span class="droite">
                @if (d.enRetard) { <span class="pastille critique">Sans réponse depuis plus de 24 h</span> }
                <span class="pastille" [class]="ton(d.statut)">{{ d.libelleStatut }}</span>
                @if (d.pieces) { <span class="photos"><fact-icone nom="journal" [taille]="14" /> {{ d.pieces }}</span> }
              </span>
            </button>
          </li>
        } @empty {
          <li><fact-etat-vide icone="courrier" titre="Aucune demande ici"
            [message]="vue() === 'nouvelles' ? 'Toutes les demandes ont été prises en charge.' : 'Les demandes envoyées depuis le site arriveront ici.'" /></li>
        }
      </ul>
    }

    <!-- ------------------------------------------------------------- fiche -->
    <fact-panneau-lateral [page]="true" [titre]="fiche()?.demande?.reference ?? 'Demande'" [ouvert]="!!ouverte()" (fermer)="fermer()">
      @if (fiche(); as f) {
        @let d = f.demande;
        <section class="fiche">
          <p class="surtitre">{{ d.libelleType }} · reçue le {{ fmt.dateHeure(d.creeLe) }}</p>
          <h3>{{ d.nom }}</h3>
          <div class="contacts">
            <a class="btn petit" [href]="telLien(d.telephone)"><fact-icone nom="telephone" [taille]="16" /> {{ d.telephone }}</a>
            <a class="btn petit" [href]="whatsapp(d)" target="_blank" rel="noopener"><fact-icone nom="whatsapp" [taille]="16" /> WhatsApp</a>
            @if (d.email) { <a class="btn petit" [href]="'mailto:' + d.email"><fact-icone nom="courrier" [taille]="16" /> {{ d.email }}</a> }
          </div>

          <dl class="faits">
            @if (d.annonce) { <div><dt>Bien</dt><dd>{{ d.annonce }} <small class="donnee">{{ d.referenceAnnonce }}</small></dd></div> }
            @if (d.batiment) { <div><dt>Bâtiment</dt><dd>{{ d.batiment }}</dd></div> }
            @if (d.dateSouhaitee) { <div><dt>Visite souhaitée</dt><dd>{{ fmt.date(d.dateSouhaitee) }}@if (d.creneau) { · {{ d.creneau }} }</dd></div> }
            @for (x of details(d); track x.cle) { <div><dt>{{ x.cle }}</dt><dd>{{ x.valeur }}</dd></div> }
            @if (d.latitude !== null) {
              <div><dt>Position indiquée</dt><dd><a [href]="'https://www.openstreetmap.org/?mlat=' + d.latitude + '&mlon=' + d.longitude + '#map=17/' + d.latitude + '/' + d.longitude" target="_blank" rel="noopener">Voir sur la carte</a></dd></div>
            }
          </dl>
          @if (d.message) { <blockquote>{{ d.message }}</blockquote> }

          @if (f.pieces.length) {
            <h4>Pièces jointes</h4>
            <ul class="pieces">
              @for (p of f.pieces; track p.id) {
                <li><button type="button" class="btn discret petit" (click)="ouvrirPiece(p)"><fact-icone [nom]="p.type === 'application/pdf' ? 'journal' : 'photo'" [taille]="16" /> {{ p.nom }} <small>{{ taille(p.taille) }}</small></button></li>
              }
            </ul>
          }

          @if (f.peutModifier) {
            <form class="suivi" (ngSubmit)="enregistrer()" novalidate>
              <h4>Suivi</h4>
              <div class="statuts" role="radiogroup" aria-label="Statut">
                @for (s of statuts; track s.code) {
                  <button type="button" role="radio" [attr.aria-checked]="saisie.statut === s.code" [class.actif]="saisie.statut === s.code" [class]="s.ton" (click)="saisie.statut = s.code">{{ s.libelle }}</button>
                }
              </div>
              @if (saisie.statut === 'RENDEZ_VOUS') {
                <label class="champ"><input type="datetime-local" name="rdv" placeholder=" " [(ngModel)]="saisie.rdvLe"><span>Date et heure du rendez-vous</span></label>
              }
              @if (comptes()) {
                <fact-choix name="compte" libelle="Suivie par" vide="Personne pour l'instant" [seuilRecherche]="0"
                            [options]="comptes()! | options : optionCompte" [(ngModel)]="saisie.idCompte" />
              }
              <label class="champ"><textarea name="note" placeholder=" " rows="3" [(ngModel)]="saisie.note"></textarea><span>Note interne (ce qui a été dit, la suite)</span></label>
              @if (erreur()) { <p class="erreur" role="alert"><fact-icone nom="alerte" [taille]="18" /> {{ erreur() }}</p> }
              <div class="pied"><button type="submit" class="btn principal" [class.occupe]="occupe()" [disabled]="occupe()">Enregistrer le suivi</button></div>
            </form>
          } @else {
            <p class="aide">Statut : <strong>{{ d.libelleStatut }}</strong>@if (d.note) { · {{ d.note }} }</p>
          }
          @if (d.majLe) { <p class="trace">Dernière mise à jour le {{ fmt.dateHeure(d.majLe) }}@if (d.majPar) { par {{ d.majPar }} }</p> }
        </section>
      } @else if (ouverte()) {
        <div class="squelette haut"></div>
      }
    </fact-panneau-lateral>`,
  styleUrl: '../travaux/travaux.scss',
  styles: [`
    .astuce { display: flex; gap: 8px; align-items: flex-start; margin: 0 0 12px; padding: 10px 12px; border-radius: var(--rayon-2);
              background: var(--nuit-clair); color: var(--encre-2); font-size: 13.5px; }
    .filtres { display: grid; gap: 8px; margin-bottom: 12px; }
    .filtres .cherche { margin: 0; }
    @media (min-width: 768px) { .filtres { grid-template-columns: 1fr 240px; } }
    .pictogramme.critique { background: var(--critique-clair); color: var(--critique-texte); }
    .message-envoi { display: grid; gap: 8px; padding: 14px; }
    .ligne1 { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .ligne1 small { color: var(--discret); font-size: 12.5px; }
    .dest { display: flex; align-items: center; gap: 6px; margin: 0; }
    .corps { margin: 0; padding: 10px 12px; border-radius: var(--rayon-2); background: var(--surface-2); font: 13.5px/1.5 var(--texte);
             white-space: pre-wrap; color: var(--encre-2); max-height: 180px; overflow: auto; }
    .actions, .pied { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
    .fiche { display: grid; gap: 14px; }
    .surtitre { margin: 0; font-size: 12.5px; color: var(--discret); }
    .fiche h3 { margin: -8px 0 0; font-size: 19px; }
    .contacts { display: flex; flex-wrap: wrap; gap: 6px; }
    .faits { margin: 0; display: grid; gap: 6px; }
    .faits div { display: flex; justify-content: space-between; gap: 14px; font-size: 14px; }
    .faits dt { color: var(--discret); flex: none; }
    .faits dd { margin: 0; text-align: right; }
    blockquote { margin: 0; padding: 12px 14px; border-left: 3px solid var(--ocre); border-radius: 0 var(--rayon-2) var(--rayon-2) 0;
                 background: var(--surface-2); white-space: pre-line; font-size: 14px; }
    h4 { margin: 4px 0 0; font-size: 14px; }
    .pieces { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
    .pieces small { color: var(--discret); margin-left: 4px; }
    .suivi { display: grid; gap: 12px; padding-top: 12px; border-top: 1px solid var(--filet); }
    .statuts { display: flex; flex-wrap: wrap; gap: 6px; }
    .statuts button { min-height: 38px; padding: 0 12px; border: 1px solid var(--filet-fort); border-radius: 19px; background: var(--surface);
                      color: var(--encre-2); font-size: 13.5px; transition: all var(--courte) var(--courbe); }
    .statuts button.actif { border-color: var(--marque); background: var(--marque); color: #fff; font-weight: 600; }
    .erreur { display: flex; gap: 9px; margin: 0; padding: 11px 13px; border-radius: var(--rayon-2); background: var(--critique-clair); color: var(--critique-texte); font-size: 14px; }
    .aide, .trace { margin: 0; font-size: 13px; color: var(--discret); }
    .squelette.haut { height: 320px; }
  `],
})
export class Demandes implements OnInit {
  private readonly service = inject(AnnoncesService);
  private readonly avis = inject(AvisService);

  /** ?demande=12 : la fiche s'ouvre d'elle-même (lien d'une alerte). */
  readonly demandeAdresse = input<string | undefined>(undefined, { alias: 'demande' });

  protected readonly statuts = STATUTS;
  protected readonly demandes = signal<Demande[]>([]);
  protected readonly comptes = signal<{ id: number; nom: string; fonction: string | null }[] | null>(null);
  protected readonly messagesEnAttente = signal(0);
  protected readonly messages = signal<MessageAEnvoyer[]>([]);
  protected readonly chargement = signal(true);
  protected readonly vue = signal<Vue>('nouvelles');
  protected readonly recherche = signal('');
  protected readonly type = signal<string | null>(null);
  protected readonly ouverte = signal<number | null>(null);
  protected readonly fiche = signal<{ demande: Demande; pieces: PieceJointe[]; peutModifier: boolean } | null>(null);
  protected readonly occupe = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected saisie: { statut: StatutDemande; note: string; idCompte: number | null; rdvLe: string } = { statut: 'NOUVELLE', note: '', idCompte: null, rdvLe: '' };
  protected readonly fmt = { date, dateHeure };
  protected readonly depuis = depuis;
  protected readonly voitMessages = computed(() => this.comptes() !== null);
  protected readonly types = [
    { code: 'VISITE', libelle: 'Demande de visite' }, { code: 'MANDAT', libelle: 'Confier un bien' }, { code: 'DEVIS', libelle: 'Devis' },
    { code: 'ESTIMATION', libelle: 'Estimation' }, { code: 'CONTACT', libelle: 'Contact' },
  ];
  protected readonly optionType = (t: { code: string; libelle: string }) => ({ valeur: t.code, libelle: t.libelle });
  protected readonly optionCompte = (c: { id: number; nom: string; fonction: string | null }) => ({ valeur: c.id, libelle: c.nom, detail: c.fonction });

  protected readonly visibles = computed(() => {
    const v = this.vue(), t = this.recherche().trim().toLowerCase(), type = this.type();
    return this.demandes().filter((d) => this.dansVue(d, v) && (!type || d.type === type)
      && (!t || `${d.nom} ${d.telephone} ${d.reference} ${d.annonce ?? ''} ${d.email ?? ''}`.toLowerCase().includes(t)));
  });

  constructor() {
    effect(() => {
      const id = Number(this.demandeAdresse());
      if (id) untracked(() => this.ouvrir(id));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
    if (!this.compte('nouvelles')) this.vue.set(this.compte('en-cours') ? 'en-cours' : 'toutes');
  }

  protected compte(v: Vue): number {
    return this.demandes().filter((d) => this.dansVue(d, v)).length;
  }

  private dansVue(d: Demande, v: Vue): boolean {
    return v === 'toutes' || (v === 'nouvelles' && d.statut === 'NOUVELLE')
      || (v === 'en-cours' && ['PRISE_EN_CHARGE', 'RENDEZ_VOUS'].includes(d.statut))
      || (v === 'closes' && ['CONCLUE', 'SANS_SUITE'].includes(d.statut));
  }

  protected ton(s: StatutDemande): string {
    return STATUTS.find((x) => x.code === s)?.ton ?? 'neutre';
  }

  protected icone(d: Demande): string {
    return ICONES[d.type] ?? 'courrier';
  }

  protected details(d: Demande): { cle: string; valeur: string }[] {
    return Object.entries(d.details ?? {}).map(([k, v]) => ({
      cle: LIBELLES[k] ?? k,
      valeur: Array.isArray(v) ? v.join(', ') : typeof v === 'number' && k === 'budget' ? v.toLocaleString('fr-FR') : String(v),
    }));
  }

  protected whatsapp(d: Demande): string {
    return lienWhatsApp(d.telephone, `Bonjour ${d.nom}, FACT Immobilier vous répond au sujet de votre demande ${d.reference}.`);
  }

  protected telLien(t: string): string {
    return 'tel:' + t.replace(/[^\d+]/g, '');
  }

  protected taille(octets: number): string {
    return octets > 1_000_000 ? (octets / 1_000_000).toFixed(1).replace('.', ',') + ' Mo' : Math.max(1, Math.round(octets / 1000)) + ' ko';
  }

  protected async ouvrir(id: number): Promise<void> {
    this.ouverte.set(id);
    this.fiche.set(null);
    this.erreur.set(null);
    try {
      this.poser(await this.service.demande(id));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
      this.ouverte.set(null);
    }
  }

  protected fermer(): void {
    this.ouverte.set(null);
    this.fiche.set(null);
  }

  protected async ouvrirPiece(p: PieceJointe): Promise<void> {
    try {
      await this.service.ouvrirPiece(p);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected async enregistrer(): Promise<void> {
    const id = this.ouverte();
    if (!id || this.occupe()) return;
    this.occupe.set(true);
    this.erreur.set(null);
    try {
      const r = await this.service.suivre(id, {
        statut: this.saisie.statut, note: this.saisie.note || null, idCompte: this.saisie.idCompte,
        rdvLe: this.saisie.statut === 'RENDEZ_VOUS' && this.saisie.rdvLe ? new Date(this.saisie.rdvLe).toISOString() : null,
      });
      this.poser(r);
      this.avis.succes(`${r.demande.reference} : ${r.demande.libelleStatut.toLowerCase()}.`);
      await this.charger();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.occupe.set(false);
    }
  }

  // ------------------------------------------------------------------ boîte d'envoi
  protected async ouvrirMessages(): Promise<void> {
    this.vue.set('messages');
    try {
      this.messages.set(await this.service.messages());
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  protected lienEnvoi(m: MessageAEnvoyer): string {
    return m.canal === 'EMAIL'
      ? `mailto:${m.destinataire}?subject=${encodeURIComponent(m.sujet ?? 'FACT Immobilier')}&body=${encodeURIComponent(m.corps)}`
      : lienWhatsApp(m.destinataire, m.corps);
  }

  protected async marquer(m: MessageAEnvoyer, envoye: boolean): Promise<void> {
    try {
      const r = await this.service.marquerMessage(m.id, envoye);
      this.messages.update((l) => l.filter((x) => x.id !== m.id));
      this.messagesEnAttente.set(r.restants);
      this.avis.succes(envoye ? 'Message marqué comme envoyé.' : 'Message écarté.');
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    }
  }

  private poser(r: { demande: Demande; pieces: PieceJointe[]; peutModifier: boolean }): void {
    this.fiche.set(r);
    const d = r.demande;
    this.saisie = {
      statut: d.statut === 'NOUVELLE' ? 'PRISE_EN_CHARGE' : d.statut, note: d.note ?? '', idCompte: d.idCompte,
      rdvLe: d.rdvLe ? new Date(d.rdvLe).toISOString().slice(0, 16) : '',
    };
  }

  private async charger(): Promise<void> {
    try {
      const r = await this.service.demandes();
      this.demandes.set(r.demandes);
      this.comptes.set(r.comptes);
      this.messagesEnAttente.set(r.messagesEnAttente);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
