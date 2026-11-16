import { Component, ElementRef, effect, input, output, viewChild } from '@angular/core';

/**
 * Fenêtre de confirmation (§ 5.4), pour toute action sensible : archiver un bâtiment,
 * réaffecter un portefeuille, résilier un bail. Ouverture par léger agrandissement avec
 * assombrissement du fond (§ 6.3). Échap ou un clic sur le fond annule.
 */
@Component({
  selector: 'fact-confirmation',
  template: `
    <dialog #dlg (close)="annuler.emit()" (click)="fond($event)" aria-labelledby="confirmation-titre">
      <div class="corps">
        <span class="pictogramme" [class.danger]="danger()" aria-hidden="true">{{ danger() ? '!' : '?' }}</span>
        <h2 id="confirmation-titre">{{ titre() }}</h2>
        <p>{{ message() }}</p>
        <div class="boutons">
          <button class="btn" type="button" (click)="fermer()">Annuler</button>
          <button class="btn" [class.danger]="danger()" [class.principal]="!danger()" type="button" (click)="confirmer.emit()">{{ action() }}</button>
        </div>
      </div>
    </dialog>`,
  styles: [`
    dialog { border: 1px solid var(--filet); border-radius: 20px; padding: 0; width: min(460px, calc(100vw - 32px));
             background: var(--surface); color: var(--encre); }
    dialog[open] { animation: ouverture var(--standard) var(--courbe); }
    dialog::backdrop { background: var(--voile); animation: fondu var(--standard) var(--courbe); }
    @keyframes ouverture { from { opacity: 0; transform: scale(.95); } }
    .corps { padding: 24px; display: grid; gap: 12px; }
    .pictogramme { width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center; font: 700 20px var(--titre);
                   background: var(--info-clair); color: var(--info-texte); }
    .pictogramme.danger { background: var(--critique-clair); color: var(--critique-texte); }
    h2 { font-size: 19px; }
    p { margin: 0; color: var(--encre-2); }
    .boutons { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
})
export class Confirmation {
  readonly ouvert = input(false);
  readonly titre = input('Confirmer');
  readonly message = input('');
  readonly action = input('Confirmer');
  readonly danger = input(true);
  readonly confirmer = output<void>();
  readonly annuler = output<void>();
  private readonly dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');

  constructor() {
    effect(() => {
      const d = this.dlg().nativeElement;
      if (this.ouvert() && !d.open) d.showModal();
      if (!this.ouvert() && d.open) d.close();
    });
  }

  protected fermer(): void {
    this.dlg().nativeElement.close();
  }

  protected fond(e: MouseEvent): void {
    if (e.target === this.dlg().nativeElement) this.fermer();
  }
}
