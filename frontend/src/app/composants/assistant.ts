import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, OnDestroy, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Icone } from './icone';
import {
  ActionAssistant, ActionNaviguer, ActionValidation, AssistantService, MessageAssistant, ReponseAssistant,
} from '../noyau/assistant.service';
import { messageErreur } from '../noyau/api.interceptor';
import { miseEnForme } from '../noyau/mise-en-forme';

interface Bulle {
  role: 'moi' | 'robot';
  texte: string;
  erreur?: boolean;
  /** Les pages ouvertes par l'assistant pour cette réponse, rappelées en liens. */
  liens?: ActionNaviguer[];
  /** Les actions proposées, qui attendent le clic « Valider ». */
  validations?: CarteValidation[];
}

interface CarteValidation extends ActionValidation {
  etat: 'attente' | 'envoi' | 'validee' | 'annulee' | 'impossible';
}

/**
 * Le robot de l'assistant : un bouton flottant en bas à droite, présent sur toutes les pages,
 * qui ouvre une fenêtre de discussion.
 *
 * Étape 2 : la conversation est gardée par l'API, en base. À l'ouverture, le robot la recharge :
 * elle survit au rechargement de la page et au changement d'appareil.
 *
 * Étape 4 : le guide. Une réponse peut porter des consignes (« naviguer vers /patrimoine/12 ») :
 * le robot ouvre la page lui-même, et rappelle le lien sous sa réponse.
 *
 * Étape 5 : les actions. Une action qui modifie des données arrive en carte « Valider / Annuler ».
 * Rien n'est fait avant le clic : c'est le clic qui appelle le serveur.
 * Le robot envoie aussi l'adresse de la page ouverte (« ici »), et propose le micro du navigateur.
 */
@Component({
  selector: 'fact-assistant',
  imports: [FormsModule, NgTemplateOutlet, RouterLink, DatePipe, Icone],
  template: `
    @if (!ouvert()) {
      <button type="button" class="lanceur" (click)="ouvrir()" aria-label="Ouvrir l'assistant" title="Une question ? Demandez à l'assistant">
        <ng-container *ngTemplateOutlet="robot" />
      </button>
    } @else {
      <section class="fenetre" role="dialog" aria-label="Assistant FACT">
        <header>
          <span class="visage" [class.reflechit]="enCours()"><ng-container *ngTemplateOutlet="robot" /></span>
          <div class="titre">
            <strong>Assistant FACT</strong>
            <small>{{ enCours() ? 'Je réfléchis…' : 'Posez votre question' }}</small>
          </div>
          @if (bulles().length) {
            <button type="button" class="fermer" (click)="nouvelle()" [disabled]="enCours()"
                    aria-label="Nouvelle conversation" title="Nouvelle conversation">
              <fact-icone nom="plus" [taille]="20" />
            </button>
          }
          <button type="button" class="fermer" (click)="ouvert.set(false)" aria-label="Fermer l'assistant">
            <fact-icone nom="fermer" [taille]="20" />
          </button>
        </header>

        <div class="fil" aria-live="polite">
          @if (chargement()) {
            <p class="discret">Je retrouve notre conversation…</p>
          } @else if (!bulles().length) {
            <div class="accueil">
              <p>Bonjour ! Je suis l'assistant de FACT Immobilier.</p>
              <p class="discret">Je connais vos bâtiments, vos impayés et vos travaux, j'explique l'application, j'ouvre la bonne page,
                et je prépare des actions que vous validez.</p>
              <div class="idees">
                @for (idee of idees; track idee) {
                  <button type="button" (click)="texte = idee; envoyer()">{{ idee }}</button>
                }
              </div>
            </div>
          }
          @for (b of bulles(); track $index) {
            @if (b.role === 'moi') {
              <p class="bulle moi">{{ b.texte }}</p>
            } @else {
              <div class="ligne-robot">
                <span class="mini" aria-hidden="true"><ng-container *ngTemplateOutlet="robot" /></span>
                <div class="contenu-robot">
                  <!-- Texte échappé puis mis en forme (noyau/mise-en-forme.ts) : aucun HTML ne passe tel quel. -->
                  <div class="bulle robot-dit" [class.erreur]="b.erreur" [innerHTML]="formater(b.texte)"></div>
                  @for (v of b.validations ?? []; track v.id) {
                    <div class="carte-validation" [class.terminee]="v.etat !== 'attente' && v.etat !== 'envoi'">
                      <p class="tete-validation"><fact-icone nom="alerte" [taille]="16" />
                        @switch (v.etat) {
                          @case ('validee') { Action validée }
                          @case ('annulee') { Action annulée }
                          @case ('impossible') { Action non effectuée }
                          @default { À valider : rien n'est fait avant votre clic }
                        }
                      </p>
                      <p class="resume">{{ v.resume }}</p>
                      @if (v.etat === 'attente' || v.etat === 'envoi') {
                        <p class="expire">Expire à {{ v.expireLe | date: 'HH:mm' }}</p>
                        <div class="boutons">
                          <button type="button" class="valider" [disabled]="v.etat === 'envoi'" (click)="decider(v, true)">
                            <fact-icone nom="coche" [taille]="16" /> Valider
                          </button>
                          <button type="button" class="annuler" [disabled]="v.etat === 'envoi'" (click)="decider(v, false)">Annuler</button>
                        </div>
                      }
                    </div>
                  }
                  @if (b.liens?.length) {
                    <div class="liens">
                      @for (l of b.liens; track l.chemin) {
                        <a class="lien-page" [routerLink]="l.chemin"><fact-icone nom="fleche" [taille]="15" /> {{ l.libelle }}</a>
                      }
                    </div>
                  }
                </div>
              </div>
            }
          }
          @if (enCours()) {
            <div class="ligne-robot">
              <span class="mini reflechit" aria-hidden="true"><ng-container *ngTemplateOutlet="robot" /></span>
              <p class="bulle points" aria-label="L'assistant écrit"><span></span><span></span><span></span></p>
            </div>
          }
          <div #bas></div>
        </div>

        <form (ngSubmit)="envoyer()">
          @if (microDisponible) {
            <button type="button" class="micro" [class.ecoute]="ecoute()" (click)="basculerMicro()" [disabled]="enCours()"
                    [attr.aria-label]="ecoute() ? 'Arrêter le micro' : 'Parler à l’assistant'" [attr.aria-pressed]="ecoute()"
                    [title]="ecoute() ? 'Arrêter le micro' : 'Parler'">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="9" y="2" width="6" height="12" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /><path d="M12 18v4" />
              </svg>
            </button>
          }
          <textarea name="texte" rows="1" maxlength="4000" [placeholder]="ecoute() ? 'Je vous écoute…' : 'Écrivez votre question…'" aria-label="Votre question"
                    [(ngModel)]="texte" (keydown)="touche($event)" #champ></textarea>
          <button type="submit" class="envoyer" [disabled]="enCours() || !texte.trim()" aria-label="Envoyer">
            <fact-icone nom="droite" [taille]="20" />
          </button>
        </form>
      </section>
    }

    <!-- Le robot, dessiné une fois et réutilisé dans le bouton et dans l'en-tête. -->
    <ng-template #robot>
      <svg class="robot" viewBox="0 0 64 64" aria-hidden="true">
        <line x1="32" y1="6" x2="32" y2="14" stroke="currentColor" stroke-width="3" stroke-linecap="round" />
        <circle class="antenne" cx="32" cy="6" r="4" />
        <rect x="10" y="14" width="44" height="36" rx="14" class="tete" />
        <rect x="16" y="22" width="32" height="18" rx="9" class="ecran" />
        <ellipse class="oeil" cx="25" cy="31" rx="3.4" ry="4.2" />
        <ellipse class="oeil" cx="39" cy="31" rx="3.4" ry="4.2" />
        <path d="M27 44 q5 3 10 0" stroke="currentColor" stroke-width="2.6" fill="none" stroke-linecap="round" class="bouche" />
        <rect x="5" y="26" width="5" height="12" rx="2.5" class="oreille" />
        <rect x="54" y="26" width="5" height="12" rx="2.5" class="oreille" />
      </svg>
    </ng-template>
  `,
  styles: [`
    :host { --bas: calc(var(--barre-basse) + env(safe-area-inset-bottom) + 14px); }
    @media (min-width: 768px) { :host { --bas: 22px; } }

    /* ----------------------------------------------------------- le robot */
    .robot { width: 100%; height: 100%; color: #fff; overflow: visible; }
    .robot .tete { fill: var(--ocre); }
    .robot .ecran { fill: var(--nuit-3); }
    .robot .oeil { fill: #7fe3ff; transform-box: fill-box; transform-origin: center; animation: cligne 4.5s infinite; }
    .robot .antenne { fill: #7fe3ff; animation: lueur 2.4s ease-in-out infinite; }
    .robot .oreille { fill: var(--nuit-2); }
    .robot .bouche { color: var(--nuit-3); }
    @keyframes cligne { 0%, 93%, 100% { transform: scaleY(1); } 96% { transform: scaleY(.1); } }
    @keyframes lueur { 0%, 100% { opacity: 1; } 50% { opacity: .35; } }

    /* ------------------------------------------------ le bouton flottant */
    .lanceur { position: fixed; z-index: 1050; right: 18px; bottom: var(--bas); width: 64px; height: 64px; padding: 9px;
               border: 0; border-radius: 50%; cursor: pointer; background: linear-gradient(160deg, var(--nuit-2), var(--nuit-3));
               box-shadow: 0 12px 28px -10px rgba(15, 29, 54, .65), 0 0 0 4px var(--surface);
               animation: flotte 3.2s ease-in-out infinite; transition: transform var(--courte) var(--courbe); }
    .lanceur:hover { transform: scale(1.07); animation-play-state: paused; }
    .lanceur:focus-visible { outline: 3px solid var(--ocre); outline-offset: 3px; }
    @keyframes flotte { 0%, 100% { translate: 0 0; } 50% { translate: 0 -5px; } }

    /* -------------------------------------------------------- la fenêtre */
    .fenetre { position: fixed; z-index: 1050; left: 10px; right: 10px; bottom: var(--bas); height: min(560px, calc(100dvh - var(--bas) - 20px));
               display: flex; flex-direction: column; overflow: hidden; border-radius: 20px; background: var(--surface); color: var(--encre);
               border: 1px solid var(--filet); box-shadow: 0 24px 60px -18px rgba(15, 29, 54, .45);
               animation: monte var(--standard) var(--courbe); transform-origin: bottom right; }
    @media (min-width: 768px) { .fenetre { left: auto; right: 22px; width: 380px; } }
    @keyframes monte { from { opacity: 0; transform: translateY(16px) scale(.96); } }

    header { display: flex; align-items: center; gap: 12px; padding: 12px 12px 12px 14px;
             background: linear-gradient(160deg, var(--nuit-2), var(--nuit-3)); color: #fff; }
    .visage { width: 42px; height: 42px; flex: none; }
    .visage.reflechit .robot { animation: hoche .9s ease-in-out infinite; }
    .visage.reflechit .antenne { animation-duration: .6s; }
    @keyframes hoche { 0%, 100% { transform: rotate(-6deg); } 50% { transform: rotate(6deg); } }
    .titre { display: grid; flex: 1; line-height: 1.25; }
    .titre strong { font: 600 15px var(--titre); }
    .titre small { color: #b9c8de; font-size: 12.5px; }
    .fermer { width: var(--tactile); height: var(--tactile); display: grid; place-items: center; border: 0; border-radius: var(--rayon-2);
              background: none; color: #fff; cursor: pointer; }
    .fermer:hover:not(:disabled) { background: rgba(255, 255, 255, .12); }
    .fermer:disabled { opacity: .5; cursor: not-allowed; }

    .fil { flex: 1; overflow-y: auto; padding: 16px 14px 8px; display: flex; flex-direction: column; gap: 10px; background: var(--surface-2); }
    .accueil p { margin: 0 0 8px; }
    .discret { color: var(--discret); font-size: 14px; }
    .idees { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
    .idees button { padding: 8px 12px; border: 1px solid var(--filet-fort); border-radius: var(--rayon-pastille); background: var(--surface);
                    color: var(--marque); font: 500 13.5px var(--texte); cursor: pointer; transition: background var(--courte) var(--courbe); }
    .idees button:hover { background: var(--nuit-clair); }

    .bulle { max-width: 85%; margin: 0; padding: 10px 14px; border-radius: 16px 16px 16px 4px; white-space: pre-wrap; overflow-wrap: anywhere;
             background: var(--surface); border: 1px solid var(--filet); font-size: 14.5px; line-height: 1.45;
             animation: monte var(--standard) var(--courbe); }
    .bulle.moi { align-self: flex-end; border-radius: 16px 16px 4px 16px; background: linear-gradient(160deg, var(--ocre), var(--ocre-2));
                 border-color: transparent; color: #fff; box-shadow: 0 6px 16px -10px rgba(35, 88, 180, .8); }
    .bulle.erreur { background: var(--critique-clair); border-color: transparent; color: var(--critique-texte); }

    /* La réponse du robot : son visage à gauche, la bulle mise en forme à droite. */
    .ligne-robot { display: flex; align-items: flex-end; gap: 8px; max-width: 92%; }
    .mini { width: 28px; height: 28px; flex: none; padding: 3px; border-radius: 50%;
            background: linear-gradient(160deg, var(--nuit-2), var(--nuit-3)); }
    .mini.reflechit .robot { animation: hoche .9s ease-in-out infinite; }
    .contenu-robot { display: grid; gap: 6px; min-width: 0; }
    .robot-dit { max-width: 100%; white-space: normal; box-shadow: 0 6px 18px -14px rgba(15, 29, 54, .5); }
    .robot-dit :is(p, ul, ol) { margin: 0; }
    .robot-dit > * + * { margin-top: 8px; }
    .robot-dit strong { color: var(--marque); font-weight: 600; }
    .robot-dit .titre-bulle { font: 600 14.5px var(--titre); color: var(--encre); }
    .robot-dit ul, .robot-dit ol { padding-left: 0; list-style: none; display: grid; gap: 5px; }
    .robot-dit ul li { position: relative; padding-left: 16px; }
    .robot-dit ul li::before { content: ''; position: absolute; left: 2px; top: .6em; width: 6px; height: 6px; border-radius: 50%; background: var(--ocre); }
    .robot-dit ol { counter-reset: n; }
    .robot-dit ol li { position: relative; padding-left: 26px; counter-increment: n; }
    .robot-dit ol li::before { content: counter(n); position: absolute; left: 0; top: .1em; width: 19px; height: 19px; display: grid; place-items: center;
                               border-radius: 50%; background: var(--nuit-clair); color: var(--marque); font: 700 11px var(--texte); }
    .robot-dit code { padding: 1px 6px; border-radius: 6px; background: var(--surface-3); font: 13px var(--donnee); }

    .liens { display: flex; flex-wrap: wrap; gap: 6px; }
    .lien-page { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border: 1px solid transparent;
                 border-radius: var(--rayon-pastille); background: var(--nuit-clair); color: var(--marque); font: 600 13px var(--texte);
                 text-decoration: none; animation: monte var(--standard) var(--courbe);
                 transition: border-color var(--courte) var(--courbe), background var(--courte) var(--courbe); }
    .lien-page:hover { background: var(--ocre-clair); border-color: var(--ocre); }
    .points { display: inline-flex; gap: 5px; align-self: flex-start; }
    .points span { width: 7px; height: 7px; border-radius: 50%; background: var(--argent-fonce); animation: saute 1s infinite; }
    .points span:nth-child(2) { animation-delay: .15s; }
    .points span:nth-child(3) { animation-delay: .3s; }
    @keyframes saute { 0%, 60%, 100% { transform: translateY(0); opacity: .5; } 30% { transform: translateY(-5px); opacity: 1; } }

    /* La carte de validation : bien visible, car c'est elle qui protège les données. */
    .carte-validation { display: grid; gap: 8px; padding: 12px 14px; border-radius: 14px; border: 1.5px solid var(--attention);
                        background: var(--attention-clair); animation: monte var(--standard) var(--courbe); }
    .carte-validation.terminee { border-color: var(--filet); background: var(--surface-3); }
    .carte-validation p { margin: 0; }
    .tete-validation { display: flex; align-items: center; gap: 6px; font: 600 13px var(--texte); color: var(--attention-texte); }
    .terminee .tete-validation { color: var(--discret); }
    .resume { white-space: pre-wrap; font-size: 14px; line-height: 1.45; color: var(--encre); }
    .expire { font-size: 12px; color: var(--discret); }
    .boutons { display: flex; gap: 8px; }
    .boutons button { min-height: 40px; padding: 0 16px; border-radius: var(--rayon-2); font: 600 14px var(--texte); cursor: pointer;
                      display: inline-flex; align-items: center; gap: 6px; transition: filter var(--courte) var(--courbe); }
    .valider { border: 0; background: var(--bon); color: #fff; }
    .annuler { border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre); }
    .boutons button:hover:not(:disabled) { filter: brightness(.95); }
    .boutons button:disabled { opacity: .55; cursor: wait; }

    .micro { width: var(--tactile); height: var(--tactile); flex: none; display: grid; place-items: center; cursor: pointer;
             border: 1.5px solid var(--filet); border-radius: var(--rayon-2); background: var(--surface); color: var(--encre-2);
             transition: border-color var(--courte) var(--courbe); }
    .micro:hover:not(:disabled) { border-color: var(--ocre); color: var(--ocre); }
    .micro.ecoute { background: var(--critique); border-color: var(--critique); color: #fff; animation: pulse 1.2s ease-in-out infinite; }
    .micro:disabled { opacity: .45; cursor: not-allowed; }
    @keyframes pulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(214, 69, 69, .45); } 50% { box-shadow: 0 0 0 6px rgba(214, 69, 69, 0); } }

    form { display: flex; gap: 8px; align-items: flex-end; padding: 10px; border-top: 1px solid var(--filet); background: var(--surface); }
    textarea { flex: 1; min-height: var(--tactile); max-height: 120px; padding: 11px 14px; resize: none; border: 1.5px solid var(--filet);
               border-radius: var(--rayon-2); background: var(--surface); color: var(--encre); font: 15px var(--texte); }
    textarea:focus { outline: none; border-color: var(--ocre); }
    .envoyer { width: var(--tactile); height: var(--tactile); flex: none; display: grid; place-items: center; border: 0; border-radius: var(--rayon-2);
               background: var(--ocre); color: #fff; cursor: pointer; transition: background var(--courte) var(--courbe); }
    .envoyer:hover:not(:disabled) { background: var(--ocre-2); }
    .envoyer:disabled { opacity: .45; cursor: not-allowed; }

    @media (prefers-reduced-motion: reduce) {
      .lanceur, .robot, .robot .oeil, .robot .antenne, .fenetre, .bulle, .lien-page, .points span, .mini .robot, .carte-validation, .micro.ecoute { animation: none !important; }
    }
  `],
})
export class Assistant implements OnDestroy {
  private readonly assistant = inject(AssistantService);
  private readonly router = inject(Router);
  private readonly bas = viewChild<ElementRef<HTMLElement>>('bas');
  private readonly champ = viewChild<ElementRef<HTMLTextAreaElement>>('champ');

  protected readonly ouvert = signal(false);
  protected readonly enCours = signal(false);
  protected readonly bulles = signal<Bulle[]>([]);
  protected readonly chargement = signal(false);
  /** L'historique n'est demandé à l'API qu'une fois, à la première ouverture. */
  private historiqueCharge = false;
  protected texte = '';

  protected readonly formater = miseEnForme;

  protected readonly idees = ['Fais-moi le point', 'Qui a des impayés ?', 'Comment signer un bail ?', 'Ouvre la carte'];

  /** Le micro du navigateur (Chrome, Edge, Safari) : la voix devient du texte, qu'on relit avant d'envoyer. */
  private readonly Reconnaissance = (window as any).SpeechRecognition ?? (window as any).webkitSpeechRecognition;
  protected readonly microDisponible = !!this.Reconnaissance;
  protected readonly ecoute = signal(false);
  private reconnaissance: any = null;

  protected ouvrir(): void {
    this.ouvert.set(true);
    setTimeout(() => this.champ()?.nativeElement.focus());
    if (!this.historiqueCharge) {
      this.historiqueCharge = true;
      this.chargement.set(true);
      this.assistant.historique().subscribe({
        next: (messages) => {
          this.chargement.set(false);
          this.bulles.set(messages.map((m: MessageAssistant) => ({ role: m.role === 'user' ? 'moi' : 'robot', texte: m.texte })));
          this.defiler();
        },
        error: () => this.chargement.set(false),
      });
    }
  }

  /** Efface la conversation en base : le robot repart de zéro, sans mémoire. */
  protected nouvelle(): void {
    this.assistant.effacer().subscribe(() => this.bulles.set([]));
  }

  protected envoyer(): void {
    const texte = this.texte.trim();
    if (!texte || this.enCours()) return;
    this.texte = '';
    this.ajouter({ role: 'moi', texte });
    this.enCours.set(true);

    // L'adresse de la page ouverte part avec la question : l'assistant comprend « ici ».
    this.assistant.demander(texte, this.router.url).subscribe({
      next: (r) => {
        this.enCours.set(false);
        this.recevoir(r);
      },
      error: (e) => {
        this.enCours.set(false);
        this.ajouter({ role: 'robot', texte: messageErreur(e), erreur: true });
      },
    });
  }

  /** Une réponse du serveur : la bulle, ses liens, ses cartes de validation, puis la navigation. */
  private recevoir(r: ReponseAssistant): void {
    const actions: ActionAssistant[] = r.actions ?? [];
    const liens = actions.filter((a): a is ActionNaviguer => a.type === 'naviguer');
    const validations: CarteValidation[] = actions
      .filter((a): a is ActionValidation => a.type === 'validation')
      .map((v) => ({ ...v, etat: 'attente' }));
    this.ajouter({ role: 'robot', texte: r.reponse, liens, validations });
    this.executer(liens);
  }

  /** Le clic sur « Valider » ou « Annuler » : le serveur vérifie tout, puis exécute ou abandonne. */
  protected decider(v: CarteValidation, valider: boolean): void {
    this.changerEtat(v, 'envoi');
    (valider ? this.assistant.valider(v.id) : this.assistant.annuler(v.id)).subscribe({
      next: (r) => {
        this.changerEtat(v, valider ? 'validee' : 'annulee');
        this.recevoir(r);
      },
      error: (e) => {
        this.changerEtat(v, 'impossible');
        this.ajouter({ role: 'robot', texte: messageErreur(e), erreur: true });
      },
    });
  }

  /** Un signal ne voit un changement que si l'objet change : on recopie la bulle de la carte. */
  private changerEtat(v: CarteValidation, etat: CarteValidation['etat']): void {
    v.etat = etat;
    this.bulles.update((l) => l.map((b) => (b.validations?.includes(v) ? { ...b, validations: [...b.validations] } : b)));
  }

  protected basculerMicro(): void {
    if (this.ecoute()) {
      this.reconnaissance?.stop();
      return;
    }
    const r = new this.Reconnaissance();
    r.lang = 'fr-FR';
    r.interimResults = true; // le texte s'écrit pendant qu'on parle
    const debut = this.texte ? this.texte.trim() + ' ' : '';
    r.onresult = (e: any) => {
      let entendu = '';
      for (let i = 0; i < e.results.length; i++) entendu += e.results[i][0].transcript;
      this.texte = debut + entendu;
    };
    r.onerror = (e: any) => {
      if (e.error === 'not-allowed') {
        this.ajouter({ role: 'robot', texte: "Le micro est bloqué : autorisez-le dans la barre d'adresse du navigateur.", erreur: true });
      }
    };
    r.onend = () => {
      this.ecoute.set(false);
      this.champ()?.nativeElement.focus();
    };
    this.reconnaissance = r;
    this.ecoute.set(true);
    r.start();
  }

  ngOnDestroy(): void {
    this.reconnaissance?.abort();
  }

  /**
   * Exécute les consignes du serveur : ouvre la dernière page demandée. Sur téléphone, la fenêtre
   * couvre l'écran : on la referme pour que la personne voie la page ouverte.
   */
  private executer(liens: ActionNaviguer[]): void {
    const derniere = liens.at(-1);
    if (!derniere) return;
    this.router.navigateByUrl(derniere.chemin);
    if (window.matchMedia('(max-width: 767px)').matches) {
      setTimeout(() => this.ouvert.set(false), 900);
    }
  }

  /** Entrée envoie, Maj+Entrée va à la ligne. */
  protected touche(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      this.envoyer();
    }
  }

  private ajouter(b: Bulle): void {
    this.bulles.update((l) => [...l, b]);
    this.defiler();
  }

  private defiler(): void {
    setTimeout(() => this.bas()?.nativeElement.scrollIntoView({ block: 'end', behavior: 'smooth' }));
  }
}
