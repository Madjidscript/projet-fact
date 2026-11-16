import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CartePose } from '../composants/carte-pose';
import { Api, messageErreur } from '../noyau/api';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Choix, OptionChoix } from '../composants/choix';
import { Accuse, EMAIL, STYLE_PIEGE, TELEPHONE } from '../composants/envoi';
import { Icone } from '../composants/icone';
import { CommunesService } from '../noyau/communes.service';
import { TYPES_BIEN } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

/** Confier mon bien (§ 3.4) : le formulaire des propriétaires et ce que l'agence fait pour eux. */
@Component({
  selector: 'site-confier',
  imports: [FormsModule, RouterLink, Icone, Choix, Accuse, CartePose, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>Confier mon bien</span></nav>
        <h1>Confiez-nous votre bien</h1>
        <p>Gestion locative, mise en location ou vente : décrivez votre bien, un conseiller vous rappelle pour une visite et une estimation.</p>
      </div>
    </section>

    <section class="conteneur grille">
      <div class="atouts" siteRevele>
        <h2>Ce que vous y gagnez</h2>
        <ul siteCascade>
          <li><span class="rond"><fact-icone nom="utilisateur" /></span><div><strong>Des locataires choisis</strong><p>Dossiers vérifiés, garants contrôlés, bail rédigé par l’agence.</p></div></li>
          <li><span class="rond"><fact-icone nom="monnaie" /></span><div><strong>Des loyers suivis</strong><p>Encaissement, quittances et relances des retards.</p></div></li>
          <li><span class="rond"><fact-icone nom="outils" /></span><div><strong>Un bien entretenu</strong><p>Pannes traitées par des prestataires de confiance ; votre accord au-delà d’un montant fixé.</p></div></li>
          <li><span class="rond"><fact-icone nom="document" /></span><div><strong>Des comptes clairs</strong><p>Un relevé de gestion : loyers reçus, honoraires, travaux, montant reversé.</p></div></li>
        </ul>
      </div>

      <div class="carte-form" siteRevele="0.1">
        @if (accuse(); as a) {
          <site-accuse [reference]="a.reference" [message]="a.message" (nouvelle)="nouvelle()" />
        } @else {
          <form class="formulaire-site" (ngSubmit)="valider()" novalidate>
            <h2>Votre bien</h2>
            <div class="services" role="radiogroup" aria-label="Service souhaité">
              @for (x of services; track x) {
                <button type="button" role="radio" [attr.aria-checked]="s.service === x" [class.actif]="s.service === x" (click)="s.service = x">{{ x }}</button>
              }
            </div>
            <div class="duo-site">
              <fact-choix name="type" libelle="Type de bien" [options]="types" [(ngModel)]="s.type" />
              <label class="champ"><input type="number" name="lots" placeholder=" " min="1" [(ngModel)]="s.lots"><span>Nombre de logements ou locaux</span></label>
            </div>
            <div class="duo-site">
              <fact-choix name="commune" libelle="Commune" [seuilRecherche]="0" [options]="optionsCommunes()" [ngModel]="s.commune" (ngModelChange)="s.commune = $event; s.quartier = null; communeChoisie.set($event)" />
              <fact-choix name="quartier" libelle="Quartier" vide="Je ne sais pas" [seuilRecherche]="0" [options]="quartiers(s.commune)" [(ngModel)]="s.quartier" [disabled]="!s.commune" />
            </div>
            <div class="pose">
              <p class="aide-pose"><fact-icone nom="cible" [taille]="18" />
                @if (s.latitude !== null) { Emplacement noté. Touchez la carte ou faites glisser le point pour le corriger. }
                @else { Facultatif : touchez la carte à l’emplacement de votre bien, cela facilite la visite. }</p>
              <site-carte-pose [centre]="centre()" (pose)="s.latitude = $event.latitude; s.longitude = $event.longitude" />
            </div>
            <div class="duo-site">
              <label class="champ" [class.erreur]="erreurs().includes('nom')"><input type="text" name="nom" placeholder=" " autocomplete="name" [(ngModel)]="s.nom"><span>Nom et prénoms</span>
                @if (erreurs().includes('nom')) { <small class="aide-champ">Indiquez votre nom.</small> }</label>
              <label class="champ" [class.erreur]="erreurs().includes('telephone')"><input type="tel" name="telephone" placeholder=" " autocomplete="tel" [(ngModel)]="s.telephone"><span>Téléphone</span>
                @if (erreurs().includes('telephone')) { <small class="aide-champ">10 chiffres, par exemple 07 01 02 03 04.</small> }</label>
            </div>
            <label class="champ" [class.erreur]="erreurs().includes('email')"><input type="email" name="email" placeholder=" " autocomplete="email" [(ngModel)]="s.email"><span>E-mail (facultatif)</span></label>
            <label class="champ"><textarea name="message" placeholder=" " rows="4" [(ngModel)]="s.message"></textarea><span>Précisions (état, loyer actuel, disponibilité…)</span></label>
            @if (erreurs().includes('bien')) { <p class="manque" role="alert">Choisissez le type de bien et sa commune.</p> }
            <input type="text" name="site" tabindex="-1" autocomplete="off" aria-hidden="true" [style]="stylePiege" [(ngModel)]="s.site">
            <label class="case" [class.erreur-case]="erreurs().includes('accord')"><input type="checkbox" name="accord" [(ngModel)]="s.accord">
              J’accepte que l’agence utilise ces informations pour me recontacter.</label>
            @if (erreurApi()) { <p class="ko" role="alert">{{ erreurApi() }}</p> }
            <button type="submit" class="btn accent grand" [class.occupe]="envoi()" [disabled]="envoi()" siteAimant>Envoyer ma demande <fact-icone nom="droite" [taille]="18" /></button>
          </form>
        }
      </div>
    </section>`,
  styles: [`
    :host { display: block; }
    .grille { display: grid; grid-template-columns: minmax(0, 1fr); gap: 28px; padding-top: 32px; padding-bottom: 24px; }
    h2 { margin: 0 0 6px; font: 700 22px var(--titre); color: var(--nuit); }
    .atouts ul { display: grid; gap: 14px; margin: 14px 0 0; padding: 0; list-style: none; }
    .atouts li { display: flex; gap: 14px; }
    .atouts strong { color: var(--nuit); }
    .atouts p { margin: 2px 0 0; color: var(--encre-2); line-height: 1.55; }
    .rond { flex: none; width: 46px; height: 46px; display: grid; place-items: center; border-radius: 14px; background: var(--ocre-clair); color: var(--ocre-texte); }
    .carte-form { order: -1; padding: 24px; border: 1px solid var(--filet); border-radius: 22px; background: var(--surface); box-shadow: 0 24px 50px -38px rgba(11, 18, 34, .6); }
    .services { display: flex; flex-wrap: wrap; gap: 8px; }
    .services button { min-height: 42px; padding: 0 16px; border: 1px solid var(--filet-fort); border-radius: var(--rayon-pastille); background: var(--surface);
                       color: var(--encre-2); font-weight: 500; transition: all var(--courte) var(--courbe); }
    .services button.actif { border-color: var(--nuit); background: var(--nuit); color: #fff; }
    .formulaire-site .btn { justify-self: start; }
    .pose { display: grid; gap: 8px; }
    .aide-pose { display: flex; gap: 8px; margin: 0; color: var(--encre-2); font-size: 14px; }
    .ko { margin: 0; color: var(--critique-texte); animation: secousse var(--standard) var(--courbe); }
    .manque { margin: 0; color: var(--critique-texte); font-size: 14px; animation: secousse var(--standard) var(--courbe); }
    .erreur-case { color: var(--critique-texte); animation: secousse var(--standard) var(--courbe); }
    @media (min-width: 1024px) { .grille { grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 48px; } .carte-form { order: 0; padding: 36px; } }
  `],
})
export class Confier implements OnInit {
  protected readonly communes = inject(CommunesService);
  private readonly api = inject(Api);
  protected readonly stylePiege = STYLE_PIEGE;
  protected readonly erreurApi = signal<string | null>(null);
  protected readonly envoi = signal(false);
  protected readonly accuse = signal<{ reference: string; message: string } | null>(null);
  /** La carte se rend au centre de la commune choisie. */
  protected readonly communeChoisie = signal<string | null>(null);
  protected readonly centre = computed<[number, number] | null>(() => {
    const c = this.communes.communes().find((x) => x.libelle === this.communeChoisie());
    return c?.latitude && c?.longitude ? [c.latitude, c.longitude] : null;
  });
  protected readonly services = ['Gestion locative', 'Mise en location', 'Vente'];
  protected readonly types: OptionChoix[] = TYPES_BIEN.map((t) => ({ valeur: t, libelle: t }));
  protected s = this.vide();
  protected readonly optionsCommunes = computed<OptionChoix[]>(() => this.communes.communes().map((c) => ({ valeur: c.libelle, libelle: c.libelle })));
  protected readonly erreurs = signal<string[]>([]);

  ngOnInit(): void {
    this.communes.assurer();
  }

  /** Les quartiers de la commune choisie ; même tableau tant que la commune ne change pas. */
  private cache: { cle: string | null; liste: OptionChoix[] } = { cle: '∅', liste: [] };
  protected quartiers(commune: string | null): OptionChoix[] {
    if (this.cache.cle !== commune) {
      const c = this.communes.communes().find((x) => x.libelle === commune);
      this.cache = { cle: commune, liste: (c?.quartiers ?? []).map((q) => ({ valeur: q.libelle, libelle: q.libelle })) };
    }
    return this.cache.liste;
  }

  protected async valider(): Promise<void> {
    const e: string[] = [];
    if (!this.s.type || !this.s.commune) e.push('bien');
    if (this.s.nom.trim().length < 2) e.push('nom');
    if (!TELEPHONE.test(this.s.telephone.trim())) e.push('telephone');
    if (this.s.email.trim() && !EMAIL.test(this.s.email.trim())) e.push('email');
    if (!this.s.accord) e.push('accord');
    this.erreurs.set([]);
    this.erreurApi.set(null);
    if (e.length) {
      setTimeout(() => this.erreurs.set(e));
      return;
    }
    this.envoi.set(true);
    try {
      this.accuse.set(await this.api.demande({
        type: 'MANDAT', nom: this.s.nom.trim(), telephone: this.s.telephone.trim(), email: this.s.email.trim() || null,
        message: this.s.message.trim() || null,
        details: { service: this.s.service, typeBien: this.s.type, commune: this.s.commune, quartier: this.s.quartier, lots: this.s.lots },
        latitude: this.s.latitude, longitude: this.s.longitude, consentement: true, site: this.s.site,
      }));
    } catch (err) {
      this.erreurApi.set(messageErreur(err));
    } finally {
      this.envoi.set(false);
    }
  }

  protected nouvelle(): void {
    this.accuse.set(null);
    this.s = this.vide();
  }

  private vide() {
    return { service: 'Gestion locative', type: null as string | null, lots: null as number | null, commune: null as string | null,
      quartier: null as string | null, latitude: null as number | null, longitude: null as number | null,
      nom: '', telephone: '', email: '', message: '', accord: false, site: '' };
  }
}
