import { ChangeDetectionStrategy, Component, computed, inject, output } from '@angular/core';
import { Router } from '@angular/router';
import { Alerte, AlertesService } from '../noyau/alertes.service';
import { depuis } from '../noyau/travaux.service';
import { EtatVide } from './etat-vide';
import { Icone } from './icone';

const ICONES: Record<Alerte['type'], string> = {
  BAIL_ECHEANCE: 'horloge',
  LOYER_RETARD: 'monnaie',
  LOT_VACANT: 'batiment',
  MANDAT_ECHEANCE: 'journal',
  INTERVENTION_URGENTE: 'outils',
  SANS_GESTIONNAIRE: 'comptes',
  DEMANDE_NOUVELLE: 'courrier',
  DEMANDE_EN_ATTENTE: 'courrier',
  ANNONCE_A_VALIDER: 'annonce',
  AFFECTATION: 'comptes',
  DEVIS_A_APPROUVER: 'outils',
  ACCORD_PROPRIETAIRE: 'coche',
  RELEVE_DISPONIBLE: 'document',
  TOURNEE: 'cible',
};

/**
 * Le contenu du panneau des alertes : les alertes qui demandent encore une action en tête,
 * puis les plus récentes. Un clic marque l'alerte lue et mène à la fiche concernée.
 */
@Component({
  selector: 'fact-liste-alertes',
  imports: [Icone, EtatVide],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (alertes.liste(); as liste) {
      @if (aTraiter() > 0) {
        <div class="barre">
          <span>{{ aTraiter() }} non lue(s)</span>
          <button type="button" class="btn discret petit" (click)="alertes.toutMarquerLu()"><fact-icone nom="coche" [taille]="16" /> Tout marquer comme lu</button>
        </div>
      }
      <ul>
        @for (a of liste; track a.id; let n = $index) {
          <li [style.animation-delay.ms]="n < 8 ? n * 60 : 480">
            <button type="button" class="alerte" [class]="a.gravite.toLowerCase()" [class.lue]="!!a.lueLe" [class.reglee]="!!a.resolueLe" (click)="aller(a)">
              <span class="pastille-icone"><fact-icone [nom]="icone(a)" [taille]="18" /></span>
              <span class="texte">
                <strong>{{ a.titre }}</strong>
                @if (a.message) { <span class="message">{{ a.message }}</span> }
                <small>{{ depuis(a.creeLe) }}@if (a.resolueLe) { · <span class="ok">situation réglée</span> }</small>
              </span>
              @if (!a.lueLe && !a.resolueLe) { <span class="point" aria-label="Non lue"></span> }
            </button>
          </li>
        } @empty {
          <li><fact-etat-vide icone="cloche" titre="Rien à signaler" message="Les retards de loyer, fins de bail, lots vides et urgences apparaîtront ici." /></li>
        }
      </ul>
    } @else {
      <ul>@for (i of [1, 2, 3, 4]; track i) { <li class="squelette"></li> }</ul>
    }`,
  styles: [`
    :host { display: block; }
    .barre { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: -4px 0 12px; font-size: 13.5px; color: var(--encre-2); }
    ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    li { animation: montee var(--standard) var(--courbe) both; }
    .alerte { width: 100%; display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: start; padding: 12px;
      border: 1px solid var(--filet); border-left: 4px solid var(--info); border-radius: var(--rayon-2); background: var(--surface);
      color: inherit; font: inherit; text-align: left; cursor: pointer;
      transition: transform var(--courte) var(--courbe), background var(--courte) var(--courbe); }
    .alerte:hover { transform: translateX(-2px); background: var(--surface-2); }
    .alerte.attention { border-left-color: var(--attention); }
    .alerte.critique { border-left-color: var(--critique); }
    .alerte.lue { opacity: .72; }
    .alerte.reglee { border-left-color: var(--bon); opacity: .6; }
    .pastille-icone { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; background: var(--info-clair); color: var(--info-texte); }
    .attention .pastille-icone { background: var(--attention-clair); color: var(--attention-texte); }
    .critique .pastille-icone { background: var(--critique-clair); color: var(--critique-texte); }
    .reglee .pastille-icone { background: var(--bon-clair); color: var(--bon-texte); }
    .texte { display: grid; gap: 3px; min-width: 0; }
    strong { font-size: 14.5px; }
    .message { font-size: 13.5px; color: var(--encre-2); }
    small { font-size: 12.5px; color: var(--discret); }
    .ok { color: var(--bon-texte); font-weight: 600; }
    .point { width: 9px; height: 9px; margin-top: 6px; border-radius: 50%; background: var(--ocre); }
    .squelette { height: 76px; border-radius: var(--rayon-2); background: var(--surface-3); animation: pulse 1.2s ease-in-out infinite; }
  `],
})
export class ListeAlertes {
  protected readonly alertes = inject(AlertesService);
  private readonly router = inject(Router);
  /** Émis quand on suit le lien d'une alerte : le panneau se referme. */
  readonly suivi = output<void>();

  protected readonly depuis = depuis;
  protected readonly aTraiter = computed(() => (this.alertes.liste() ?? []).filter((a) => !a.lueLe && !a.resolueLe).length);

  protected icone(a: Alerte): string {
    return ICONES[a.type] ?? 'cloche';
  }

  protected async aller(a: Alerte): Promise<void> {
    this.alertes.marquerLue(a).catch(() => undefined);
    if (a.lien) {
      this.suivi.emit();
      await this.router.navigateByUrl(a.lien);
    }
  }
}
