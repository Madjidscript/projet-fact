import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../composants/icone';
import { AGENCE, anneesActivite } from '../noyau/donnees';
import { MOUVEMENT } from '../noyau/mouvement';

/**
 * L'agence (§ 3.6) : histoire depuis 2013, valeurs, appartenance à la CDAIM. L'équipe n'est
 * publiée qu'avec ses vrais noms et photos : en attendant, la section l'annonce sobrement.
 */
@Component({
  selector: 'site-agence',
  imports: [RouterLink, Icone, ...MOUVEMENT],
  template: `
    <section class="bandeau">
      <div class="conteneur" siteRevele>
        <nav class="fil" aria-label="Fil d'Ariane"><a routerLink="/">Accueil</a> <span>/</span> <span>L’agence</span></nav>
        <h1>Un partenaire de confiance à Abidjan</h1>
        <p>Depuis {{ agence.demarrage }}, FACT Immobilier accompagne propriétaires, locataires et porteurs de projets avec sérieux, dynamisme et professionnalisme.</p>
      </div>
    </section>

    <section class="section conteneur histoire">
      <div siteRevele>
        <p class="surtitre">Notre histoire</p>
        <h2 class="titre-section" siteTitre>{{ annees }} ans au service de l’immobilier ivoirien</h2>
        <p class="chapeau">L’agence a été créée en {{ agence.creation }} et a démarré son activité en {{ agence.demarrage }}. Elle gère aujourd’hui plus de 50 bâtiments pour le compte de leurs propriétaires et intervient de la location à la construction.</p>
      </div>
      <ol class="frise" siteCascade>
        <li><span class="annee">{{ agence.creation }}</span><p>Création de FACT Immobilier à Abidjan.</p></li>
        <li><span class="annee">{{ agence.demarrage }}</span><p>Début de l’activité : gestion locative et transactions.</p></li>
        <li><span class="annee">Aujourd’hui</span><p>Plus de 50 bâtiments gérés, quatre métiers, une agence membre de la CDAIM.</p></li>
      </ol>
    </section>

    <section class="section valeurs">
      <div class="conteneur">
        <div><p class="surtitre">Nos valeurs</p><h2 class="titre-section" siteTitre>Ce qui guide notre travail</h2></div>
        <ul class="cartes" siteLot>
          @for (v of agence.valeurs; track v.titre) {
            <li siteInclinaison="5"><span class="rond"><fact-icone [nom]="v.icone" [taille]="30" [dessin]="true" /></span><h3>{{ v.titre }}</h3><p>{{ v.texte }}</p></li>
          }
        </ul>
      </div>
    </section>

    <section class="section conteneur deux">
      <div class="encart" siteRevele>
        <span class="rond"><fact-icone nom="bouclier" [taille]="28" /></span>
        <h2>Membre de la CDAIM</h2>
        <p>FACT Immobilier adhère à la Chambre des agents immobiliers de Côte d’Ivoire, gage d’une pratique encadrée du métier.</p>
      </div>
      <div class="encart" siteRevele="0.1">
        <span class="rond"><fact-icone nom="comptes" [taille]="28" /></span>
        <h2>Notre équipe</h2>
        <p>Les associés et conseillers de l’agence seront présentés ici très prochainement, avec leurs fonctions. En attendant, toute l’équipe vous répond au {{ agence.telephones[0].libelle }}.</p>
      </div>
    </section>

    <section class="conteneur">
      <div class="appel" siteRevele>
        <div><h2>Venez nous rencontrer</h2><p>{{ agence.adresse }}, {{ agence.ville }}.</p></div>
        <a class="btn grand" routerLink="/contact" siteAimant>Nous contacter <fact-icone nom="droite" [taille]="18" /></a>
      </div>
    </section>`,
  styles: [`
    :host { display: block; }
    .histoire { display: grid; grid-template-columns: minmax(0, 1fr); gap: 36px; }
    .frise { display: grid; gap: 14px; margin: 0; padding: 0 0 0 22px; list-style: none; border-left: 2px solid var(--filet); }
    .frise li { position: relative; padding: 4px 0 4px 14px; }
    .frise li::before { content: ''; position: absolute; left: -31px; top: 10px; width: 16px; height: 16px; border-radius: 50%;
                        background: var(--ocre); box-shadow: 0 0 0 5px var(--ocre-clair); }
    .annee { color: var(--nuit); font: 700 26px var(--titre); }
    .frise p { margin: 4px 0 0; color: var(--encre-2); }
    .valeurs { background: var(--surface-2); }
    .cartes { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; margin: 28px 0 0; padding: 0; list-style: none; }
    .cartes li { padding: 26px; border: 1px solid var(--filet); border-radius: 20px; background: var(--surface); }
    .cartes h3 { margin: 14px 0 6px; font: 700 20px var(--titre); color: var(--nuit); }
    .cartes p, .encart p { margin: 0; color: var(--encre-2); line-height: 1.6; }
    .rond { width: 56px; height: 56px; display: grid; place-items: center; border-radius: 16px; background: var(--ocre-clair); color: var(--ocre-texte); }
    .deux { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
    .encart { padding: 28px; border-radius: 22px; border: 1px solid var(--filet); background: var(--surface); }
    .encart h2 { margin: 14px 0 8px; font: 700 22px var(--titre); color: var(--nuit); }
    @media (min-width: 900px) {
      .histoire, .deux { grid-template-columns: 1fr 1fr; gap: 56px; }
      .cartes { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    }
  `],
})
export class Agence {
  protected readonly agence = AGENCE;
  protected readonly annees = anneesActivite();
}
