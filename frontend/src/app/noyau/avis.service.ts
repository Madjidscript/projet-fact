import { Injectable, signal } from '@angular/core';
import { Avis } from './modeles';

/**
 * Les messages brefs affichés en bas d'écran (§ 5.4, « Notification ») : ils glissent
 * depuis le bas et disparaissent au bout de 4 secondes (§ 6.3).
 */
@Injectable({ providedIn: 'root' })
export class AvisService {
  private suivant = 1;
  readonly liste = signal<Avis[]>([]);

  succes(texte: string): void {
    this.poser('succes', texte);
  }

  erreur(texte: string): void {
    this.poser('erreur', texte);
  }

  info(texte: string): void {
    this.poser('info', texte);
  }

  retirer(id: number): void {
    this.liste.update((l) => l.filter((a) => a.id !== id));
  }

  private poser(genre: Avis['genre'], texte: string): void {
    const id = this.suivant++;
    this.liste.update((l) => [...l, { id, genre, texte }]);
    // Une erreur reste à l'écran : elle demande une lecture, parfois une action.
    if (genre !== 'erreur') {
      setTimeout(() => this.retirer(id), 4000);
    }
  }
}
