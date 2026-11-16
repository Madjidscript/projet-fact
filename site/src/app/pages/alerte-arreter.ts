import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../composants/icone';
import { Api, messageErreur } from '../noyau/api';
import { MOUVEMENT } from '../noyau/mouvement';

/** Le lien de désabonnement des alertes : /alertes/arreter?jeton=… (§ 3.7). */
@Component({
  selector: 'site-alerte-arreter',
  imports: [RouterLink, Icone, ...MOUVEMENT],
  template: `
    <section class="bandeau plein">
      <div class="conteneur" siteRevele>
        @switch (etat()) {
          @case ('encours') { <h1>Un instant…</h1><p>Nous arrêtons votre alerte.</p> }
          @case ('fait') {
            <span class="rond ok"><fact-icone nom="coche" [taille]="30" /></span>
            <h1>Alerte arrêtée</h1>
          }
          @default {
            <span class="rond"><fact-icone nom="info" [taille]="30" /></span>
            <h1>Alerte non trouvée</h1>
          }
        }
        @if (message()) { <p>{{ message() }}</p> }
        @if (etat() !== 'encours') {
          <div class="actions">
            <a class="btn accent grand" routerLink="/louer">Voir les biens à louer</a>
            <a class="btn grand clair" routerLink="/">Retour à l’accueil</a>
          </div>
        }
      </div>
    </section>`,
  styles: [`
    .plein { min-height: 70vh; display: flex; align-items: center; }
    .rond { width: 64px; height: 64px; display: grid; place-items: center; margin-bottom: 18px; border-radius: 50%;
            background: rgba(255,255,255,.12); color: #fff; }
    .rond.ok { background: var(--bon); }
    .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 24px; }
    .clair { background: transparent; color: #fff; border-color: rgba(255,255,255,.4); }
  `],
})
export class AlerteArreter implements OnInit {
  private readonly api = inject(Api);
  readonly jeton = input<string | undefined>();
  protected readonly etat = signal<'encours' | 'fait' | 'echec'>('encours');
  protected readonly message = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const j = this.jeton();
    if (!j) {
      this.etat.set('echec');
      this.message.set('Ce lien est incomplet. Utilisez le lien reçu dans le message d’alerte.');
      return;
    }
    try {
      const r = await this.api.arreterAlerte(j);
      this.etat.set(r.arrete ? 'fait' : 'echec');
      this.message.set(r.message);
    } catch (e) {
      this.etat.set('echec');
      this.message.set(messageErreur(e));
    }
  }
}
