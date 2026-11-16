import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AGENCE } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

/** Mentions légales et protection des données personnelles. */
@Component({
  selector: 'site-mentions',
  imports: [RouterLink, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>Mentions légales</span></nav>
        <h1>Mentions légales</h1>
        <p>Les informations sur l’éditeur du site et sur l’usage de vos données.</p>
      </div>
    </section>
    <article class="conteneur texte" siteRevele>
      <h2>Éditeur du site</h2>
      <p>{{ agence.nom }}, agence immobilière membre de la CDAIM.<br>{{ agence.adresse }}, {{ agence.ville }}.<br>
        Téléphone : {{ agence.telephones[0].libelle }} · {{ agence.telephones[1].libelle }} · E-mail : <a [href]="'mailto:' + agence.email">{{ agence.email }}</a></p>
      <p>La forme juridique, le numéro d’immatriculation, le numéro d’agrément et le nom du directeur de la publication seront précisés ici.</p>

      <h2>Annonces</h2>
      <p>Les biens présentés sont proposés sous réserve de disponibilité. Les prix sont indiqués en francs CFA ; les loyers s’entendent par mois.
        La position exacte d’un logement occupé n’est jamais publiée.</p>

      <h2 id="donnees">Données personnelles</h2>
      <p>Les informations que vous nous transmettez (nom, téléphone, e-mail, message) servent uniquement à répondre à votre demande et à vous
        recontacter. Elles ne sont ni vendues ni cédées. Vous pouvez demander à les consulter, les corriger ou les supprimer en écrivant à
        <a [href]="'mailto:' + agence.email">{{ agence.email }}</a>.</p>
      <p>Le site ne dépose pas de cookie publicitaire. Vos biens favoris, quand cette fonction sera disponible, resteront enregistrés sur votre seul appareil.</p>
    </article>`,
  styles: [`
    :host { display: block; }
    .texte { max-width: 820px; padding-top: 40px; padding-bottom: 24px; }
    h2 { margin: 28px 0 10px; font: 700 22px var(--titre); color: var(--nuit); }
    p { margin: 0 0 12px; color: var(--encre-2); line-height: 1.7; }
  `],
})
export class Mentions {
  protected readonly agence = AGENCE;
}
