import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Choix, OptionChoix } from '../composants/choix';
import { Accuse, EMAIL, STYLE_PIEGE, TELEPHONE } from '../composants/envoi';
import { Icone } from '../composants/icone';
import { Api, messageErreur } from '../noyau/api';
import { AGENCE } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

/** Contact : coordonnées de l'agence et formulaire envoyé à l'agence (demande de type CONTACT). */
@Component({
  selector: 'site-contact',
  imports: [FormsModule, RouterLink, Icone, Choix, Accuse, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>Contact</span></nav>
        <h1>Parlons de votre projet</h1>
        <p>Une question, une recherche de logement, un renseignement : écrivez-nous, appelez-nous ou passez à l’agence.</p>
      </div>
    </section>

    <section class="conteneur grille">
      <div class="carte-form" siteRevele>
        @if (accuse(); as a) {
          <site-accuse [reference]="a.reference" [message]="a.message" (nouvelle)="nouvelle()" />
        } @else {
          <form class="formulaire-site" (ngSubmit)="envoyer()" novalidate>
            <h2>Votre message</h2>
            <p class="lien-devis"><fact-icone nom="contrat" [taille]="18" /><span>Un devis de construction, de travaux ou une estimation ?
              <a routerLink="/devis">Utilisez le formulaire de devis</a>, qui accepte vos plans et photos.</span></p>
            <fact-choix name="sujet" libelle="Objet" [options]="sujets()" [(ngModel)]="s.sujet" />
            <div class="duo-site">
              <label class="champ" [class.erreur]="erreurs().includes('nom')"><input type="text" name="nom" placeholder=" " autocomplete="name" [(ngModel)]="s.nom"><span>Nom et prénoms</span>
                @if (erreurs().includes('nom')) { <small class="aide-champ">Indiquez votre nom.</small> }</label>
              <label class="champ" [class.erreur]="erreurs().includes('telephone')"><input type="tel" name="telephone" placeholder=" " autocomplete="tel" [(ngModel)]="s.telephone"><span>Téléphone</span>
                @if (erreurs().includes('telephone')) { <small class="aide-champ">10 chiffres, par exemple 07 01 02 03 04.</small> }</label>
            </div>
            <label class="champ" [class.erreur]="erreurs().includes('email')"><input type="email" name="email" placeholder=" " autocomplete="email" [(ngModel)]="s.email"><span>E-mail (facultatif)</span>
              @if (erreurs().includes('email')) { <small class="aide-champ">Adresse e-mail incomplète.</small> }</label>
            <label class="champ" [class.erreur]="erreurs().includes('message')"><textarea name="message" placeholder=" " rows="5" [(ngModel)]="s.message"></textarea><span>Votre message</span>
              @if (erreurs().includes('message')) { <small class="aide-champ">Dites-nous en quelques mots ce que vous souhaitez.</small> }</label>
            <input type="text" name="site" tabindex="-1" autocomplete="off" aria-hidden="true" [style]="stylePiege" [(ngModel)]="s.site">
            <label class="case" [class.erreur-case]="erreurs().includes('accord')"><input type="checkbox" name="accord" [(ngModel)]="s.accord">
              <span>J’accepte que l’agence utilise ces informations pour me répondre (<a routerLink="/mentions-legales" fragment="donnees">données personnelles</a>).</span></label>
            @if (erreurApi()) { <p class="ko" role="alert">{{ erreurApi() }}</p> }
            <button type="submit" class="btn accent grand" [class.occupe]="envoi()" [disabled]="envoi()" siteAimant>Envoyer mon message <fact-icone nom="droite" [taille]="18" /></button>
          </form>
        }
      </div>

      <aside class="coordonnees" siteCascade aria-label="Coordonnées">
        <a class="ligne" [href]="agence.telephones[0].lien"><span class="rond"><fact-icone nom="appel" [dessin]="true" /></span><span><small>Bureau</small><strong>{{ agence.telephones[0].libelle }}</strong></span></a>
        <a class="ligne" [href]="agence.telephones[1].lien"><span class="rond"><fact-icone nom="appel" [dessin]="true" /></span><span><small>Mobile</small><strong>{{ agence.telephones[1].libelle }}</strong></span></a>
        <a class="ligne" [href]="agence.whatsapp" target="_blank" rel="noopener"><span class="rond vert"><fact-icone nom="whatsapp" /></span><span><small>WhatsApp</small><strong>Écrire à l’agence</strong></span></a>
        <a class="ligne" [href]="'mailto:' + agence.email"><span class="rond"><fact-icone nom="courriel" [dessin]="true" /></span><span><small>E-mail</small><strong>{{ agence.email }}</strong></span></a>
        <div class="ligne"><span class="rond"><fact-icone nom="adresse" [dessin]="true" /></span><span><small>Adresse</small><strong>{{ agence.adresse }}</strong><small>{{ agence.ville }}</small></span></div>
      </aside>
    </section>`,
  styles: [`
    :host { display: block; }
    .grille { display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; padding-top: 32px; padding-bottom: 24px; }
    .carte-form { padding: 24px; border: 1px solid var(--filet); border-radius: 22px; background: var(--surface); box-shadow: 0 24px 50px -38px rgba(11, 18, 34, .6); }
    h2 { margin: 0 0 4px; font: 700 22px var(--titre); color: var(--nuit); }
    .lien-devis { display: flex; gap: 8px; margin: 0; padding: 10px 12px; border-radius: 12px; background: var(--ocre-clair); color: var(--ocre-texte); font-size: 14px; }
    .formulaire-site .btn { justify-self: start; }
    .ko { margin: 0; color: var(--critique-texte); animation: secousse var(--standard) var(--courbe); }
    .erreur-case { color: var(--critique-texte); animation: secousse var(--standard) var(--courbe); }
    .coordonnees { display: grid; gap: 10px; align-content: start; }
    .ligne { display: flex; align-items: center; gap: 14px; padding: 16px; border: 1px solid var(--filet); border-radius: 18px; background: var(--surface);
             color: var(--encre); text-decoration: none; transition: transform 300ms var(--courbe), border-color 300ms var(--courbe); }
    a.ligne:hover { transform: translateX(4px); border-color: var(--filet-fort); }
    .ligne > span:last-child { display: grid; gap: 2px; min-width: 0; overflow-wrap: anywhere; }
    .ligne small { color: var(--discret); font-size: 13px; }
    .rond { flex: none; width: 46px; height: 46px; display: grid; place-items: center; border-radius: 14px; background: var(--nuit-clair); color: var(--nuit); }
    .rond.vert { background: #dcf8e7; color: #128c4a; }
    @media (min-width: 1024px) { .grille { grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); gap: 40px; } .carte-form { padding: 36px; } }
  `],
})
export class Contact {
  private readonly api = inject(Api);
  /** ?sujet=… : l'objet est prérempli (recherche sans résultat, par exemple). */
  readonly sujet = input<string | undefined>();
  protected readonly agence = AGENCE;
  protected readonly sujets = signal<OptionChoix[]>(['Question générale', 'Recherche de location', 'Recherche d’achat', 'Gestion de mon bien', 'Autre']
    .map((x) => ({ valeur: x, libelle: x })));
  protected s = this.vide();
  protected readonly erreurs = signal<string[]>([]);
  protected readonly erreurApi = signal<string | null>(null);
  protected readonly envoi = signal(false);
  protected readonly accuse = signal<{ reference: string; message: string } | null>(null);
  protected readonly stylePiege = STYLE_PIEGE;

  constructor() {
    effect(() => {
      const sujet = this.sujet();
      untracked(() => {
        if (!sujet) return;
        if (!this.sujets().some((x) => x.valeur === sujet)) this.sujets.update((l) => [{ valeur: sujet, libelle: sujet }, ...l]);
        this.s.sujet = sujet;
      });
    });
  }

  protected async envoyer(): Promise<void> {
    const e: string[] = [];
    if (this.s.nom.trim().length < 2) e.push('nom');
    if (!TELEPHONE.test(this.s.telephone.trim())) e.push('telephone');
    if (this.s.email.trim() && !EMAIL.test(this.s.email.trim())) e.push('email');
    if (this.s.message.trim().length < 5) e.push('message');
    if (!this.s.accord) e.push('accord');
    // Réinitialiser puis reposer les erreurs rejoue la secousse (§ 6.2).
    this.erreurs.set([]);
    this.erreurApi.set(null);
    if (e.length) {
      setTimeout(() => this.erreurs.set(e));
      return;
    }
    this.envoi.set(true);
    try {
      this.accuse.set(await this.api.demande({
        type: 'CONTACT', nom: this.s.nom.trim(), telephone: this.s.telephone.trim(), email: this.s.email.trim() || null,
        message: this.s.message.trim(), details: { sujet: this.s.sujet }, consentement: true, site: this.s.site,
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
    return { sujet: 'Question générale' as string | null, nom: '', telephone: '', email: '', message: '', accord: false, site: '' };
  }
}
