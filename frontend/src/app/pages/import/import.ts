import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icone } from '../../composants/icone';
import { Compteur } from '../../composants/mouvement';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { RapportImport } from '../../noyau/modeles';
import { PatrimoineService } from '../../noyau/patrimoine.service';

/**
 * Import initial du patrimoine depuis Excel (SIG-10, § 7.3), en deux temps : le fichier est
 * d'abord contrôlé en entier, sans rien écrire ; l'import n'est proposé que s'il ne reste
 * aucune erreur. Les fiches déjà présentes sont reconnues et laissées telles quelles.
 */
@Component({
  selector: 'fact-import',
  imports: [RouterLink, Icone, Compteur],
  templateUrl: './import.html',
  styleUrl: '../patrimoine/patrimoine.scss',
})
export class Import {
  private readonly patrimoine = inject(PatrimoineService);
  private readonly avis = inject(AvisService);

  protected readonly fichier = signal<File | null>(null);
  protected readonly survol = signal(false);
  protected readonly occupe = signal<'controle' | 'import' | 'modele' | null>(null);
  protected readonly rapport = signal<RapportImport | null>(null);

  protected async telechargerModele(): Promise<void> {
    this.occupe.set('modele');
    try {
      const blob = await this.patrimoine.modeleImport();
      const lien = document.createElement('a');
      lien.href = URL.createObjectURL(blob);
      lien.download = 'modele_import_patrimoine.xlsx';
      lien.click();
      URL.revokeObjectURL(lien.href);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.occupe.set(null);
    }
  }

  protected choisir(evenement: Event): void {
    const f = (evenement.target as HTMLInputElement).files?.[0] ?? null;
    (evenement.target as HTMLInputElement).value = '';
    if (f) this.prendre(f);
  }

  protected deposer(e: DragEvent): void {
    e.preventDefault();
    this.survol.set(false);
    const f = e.dataTransfer?.files?.[0];
    if (f) this.prendre(f);
  }

  protected glisser(e: DragEvent, dessus: boolean): void {
    e.preventDefault();
    this.survol.set(dessus);
  }

  protected recommencer(): void {
    this.fichier.set(null);
    this.rapport.set(null);
  }

  protected async importer(): Promise<void> {
    const f = this.fichier();
    if (!f) return;
    this.occupe.set('import');
    try {
      const r = await this.patrimoine.importer(f, false);
      this.rapport.set(r);
      if (r.importe) this.avis.succes('Patrimoine importé.');
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.occupe.set(null);
    }
  }

  protected taille(octets: number): string {
    return octets < 1024 * 1024 ? Math.max(1, Math.round(octets / 1024)) + ' Ko' : (octets / 1024 / 1024).toFixed(1).replace('.', ',') + ' Mo';
  }

  /** Un fichier choisi est aussitôt contrôlé, sans rien écrire. */
  private async prendre(f: File): Promise<void> {
    if (!/\.xlsx$/i.test(f.name)) {
      this.avis.erreur('Choisissez un classeur Excel (.xlsx), de préférence le modèle fourni.');
      return;
    }
    this.fichier.set(f);
    this.rapport.set(null);
    this.occupe.set('controle');
    try {
      this.rapport.set(await this.patrimoine.importer(f, true));
    } catch (e) {
      this.avis.erreur(messageErreur(e));
      this.fichier.set(null);
    } finally {
      this.occupe.set(null);
    }
  }
}
