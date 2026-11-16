import { HttpClient } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Colonne, Ligne, TableDonnees } from '../../composants/table-donnees';
import { messageErreur } from '../../noyau/api.interceptor';
import { AvisService } from '../../noyau/avis.service';
import { dateHeure } from '../../noyau/format';
import { LigneJournal } from '../../noyau/modeles';

const LIBELLES: Record<string, string> = {
  nom: 'Nom', fonction: 'Fonction', email: 'E-mail', telephone: 'Téléphone', profil: 'Profil',
  droits: 'Droits en plus', actif: 'Actif',
};

/**
 * Le journal des actions (SIG-22) : qui a modifié quoi, quand, avec l'ancienne et la nouvelle
 * valeur. Lecture seule, pour la Direction et l'administrateur.
 */
@Component({
  selector: 'fact-journal',
  imports: [FormsModule, TableDonnees],
  styleUrl: '../comptes/comptes.scss',
  template: `
    <header class="entete">
      <div>
        <h1>Journal</h1>
        <p class="accroche">Chaque création ou modification est tracée : auteur, date, ancienne et nouvelle valeur.</p>
      </div>
    </header>

    <div class="outils">
      <label class="filtre">
        <span class="sr">Filtrer par nature d'objet</span>
        <select [ngModel]="objet()" (ngModelChange)="filtrer($event)">
          <option value="">Toutes les natures</option>
          @for (o of objets(); track o) { <option [value]="o">{{ libelleObjet(o) }}</option> }
        </select>
      </label>
    </div>

    <fact-table-donnees titre="Journal des actions" [colonnes]="colonnes" [lignes]="lignes()" cleLigne="id" [parPage]="15"
                        [exportable]="true" [cliquable]="true" [chargement]="chargement()" [surbrillance]="choisie()?.id"
                        vide="Aucune action enregistrée." (ligne)="ouvrir($event)" />

    @if (choisie(); as l) {
      <section class="carte detail" aria-live="polite">
        <h2>{{ l.action }}</h2>
        <p class="accroche">
          <span class="donnee">{{ f(l.le) }}</span> · {{ l.auteur }}
          @if (l.cle) { · <span class="donnee">{{ l.cle }}</span> }
          @if (l.adresse_ip) { · depuis <span class="donnee">{{ l.adresse_ip }}</span> }
        </p>
        @if (comparaison().length) {
          <table class="comparaison">
            <caption class="sr">Ancienne et nouvelle valeur</caption>
            <thead><tr><th scope="col">Champ</th><th scope="col">Avant</th><th scope="col">Après</th></tr></thead>
            <tbody>
              @for (c of comparaison(); track c.champ) {
                <tr [class.change]="c.change"><th scope="row">{{ c.champ }}</th><td>{{ c.avant }}</td><td>{{ c.apres }}</td></tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="aide">Cette action ne porte pas de valeur à comparer.</p>
        }
      </section>
    }`,
})
export class Journal implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly avis = inject(AvisService);

  protected readonly chargement = signal(true);
  protected readonly journal = signal<LigneJournal[]>([]);
  protected readonly objets = signal<string[]>([]);
  protected readonly objet = signal('');
  protected readonly choisie = signal<LigneJournal | null>(null);
  protected readonly f = dateHeure;

  protected readonly colonnes: Colonne[] = [
    { cle: 'quand', libelle: 'Date', format: 'reference' },
    { cle: 'auteur', libelle: 'Auteur' },
    { cle: 'nature', libelle: 'Nature' },
    { cle: 'cle', libelle: 'Fiche', format: 'reference' },
    { cle: 'action', libelle: 'Action' },
  ];

  protected readonly lignes = computed<Ligne[]>(() => this.journal().map((l) => ({
    id: l.id, quand: dateHeure(l.le), auteur: l.auteur, nature: this.libelleObjet(l.objet), cle: l.cle, action: l.action,
  })));

  /** L'ancienne et la nouvelle valeur, champ par champ ; les champs qui ont changé sont marqués. */
  protected readonly comparaison = computed(() => {
    const l = this.choisie();
    if (!l || (!l.avant && !l.apres)) return [];
    const champs = [...new Set([...Object.keys(l.avant ?? {}), ...Object.keys(l.apres ?? {})])];
    return champs.map((c) => {
      const avant = this.dire(l.avant?.[c]), apres = this.dire(l.apres?.[c]);
      return { champ: LIBELLES[c] ?? c, avant: l.avant ? avant : '', apres: l.apres ? apres : '', change: !!l.avant && avant !== apres };
    });
  });

  ngOnInit(): Promise<void> {
    return this.charger();
  }

  protected libelleObjet(objet: string): string {
    return objet.charAt(0) + objet.slice(1).toLowerCase().replace(/_/g, ' ');
  }

  protected filtrer(objet: string): Promise<void> {
    this.objet.set(objet);
    this.choisie.set(null);
    return this.charger();
  }

  protected ouvrir(ligne: Ligne): void {
    this.choisie.set(this.journal().find((l) => l.id === ligne['id']) ?? null);
  }

  private dire(v: unknown): string {
    if (v === null || v === undefined || v === '') return '-';
    if (v === true) return 'Oui';
    if (v === false) return 'Non';
    if (Array.isArray(v)) return v.length ? v.join(', ') : '-';
    return String(v);
  }

  private async charger(): Promise<void> {
    this.chargement.set(true);
    try {
      const params: Record<string, string | number> = { limite: 500 };
      if (this.objet()) params['objet'] = this.objet();
      const r = await firstValueFrom(this.http.get<{ lignes: LigneJournal[]; objets: string[] }>('/api/journal', { params }));
      this.journal.set(r.lignes);
      this.objets.set(r.objets);
    } catch (e) {
      this.avis.erreur(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
