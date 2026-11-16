import { Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

/**
 * Référencement : chaque page porte son titre et sa description (données de la route), repris
 * dans les balises Open Graph pour le partage sur les réseaux et WhatsApp.
 */
@Injectable()
export class Referencement extends TitleStrategy {
  private readonly titre = inject(Title);
  private readonly meta = inject(Meta);

  override updateTitle(etat: RouterStateSnapshot): void {
    const t = this.buildTitle(etat);
    let r = etat.root;
    while (r.firstChild) r = r.firstChild;
    const description = r.data['description'] as string | undefined;
    const complet = t ? `${t} — FACT Immobilier` : 'FACT Immobilier — Agence immobilière à Abidjan';
    this.poser(complet, description);
  }

  /** Pour les pages dont le titre dépend du contenu (fiche d'un bien). */
  poser(titre: string, description?: string): void {
    this.titre.setTitle(titre);
    this.meta.updateTag({ property: 'og:title', content: titre });
    if (description) {
      this.meta.updateTag({ name: 'description', content: description });
      this.meta.updateTag({ property: 'og:description', content: description });
    }
  }
}
