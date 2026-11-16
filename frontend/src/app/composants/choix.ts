import {
  ChangeDetectionStrategy, Component, ElementRef, Pipe, PipeTransform, computed, forwardRef, inject, input, signal, viewChild,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { Icone } from './icone';

/** Une option de la liste : la valeur enregistrée, le texte affiché, un complément discret. */
export interface OptionChoix {
  valeur: unknown;
  libelle: string;
  detail?: string | null;
  desactive?: boolean;
}

/** Sans accents ni majuscules, pour que « bietry » trouve « Biétry ». */
const simplifier = (t: string) => t.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * Liste de choix avec un champ de recherche, à la place d'un <select> quand la liste est longue.
 * S'emploie comme un champ de formulaire :
 *   <fact-choix name="batiment" libelle="Bâtiment" [options]="opts()" [(ngModel)]="s.idBatiment" />
 * `vide` ajoute en tête un choix « sans valeur » (null), par exemple « Aucun gestionnaire ».
 */
@Component({
  selector: 'fact-choix',
  imports: [Icone],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => Choix), multi: true }],
  host: { '(document:pointerdown)': 'dehors($event)', '(keydown.escape)': 'echap($event)', '[class.ouvert]': 'ouvert()' },
  template: `
    <button #declencheur type="button" class="declencheur" [class.rempli]="!!choisie()" [class.sans-libelle]="!libelle()"
            [disabled]="inactif()" (click)="basculer()" (keydown)="clavierFerme($event)"
            role="combobox" aria-haspopup="listbox" [attr.aria-expanded]="ouvert()" [attr.aria-label]="libelle() || null">
      <span class="valeur">@if (choisie(); as o) { {{ o.libelle }}@if (o.detail) { <small>{{ o.detail }}</small> } } @else { <i>{{ invite() }}</i> }</span>
      <fact-icone class="fleche" nom="bas" [taille]="18" />
    </button>
    @if (libelle()) { <span class="etiquette">{{ libelle() }}</span> }

    @if (ouvert()) {
      <div class="liste" [class.haut]="versLeHaut()" [class.droite]="aDroite()">
        @if (avecRecherche()) {
          <label class="recherche">
            <fact-icone nom="recherche" [taille]="16" />
            <input #champ type="search" [value]="texte()" (input)="chercher($any($event.target).value)" (keydown)="clavier($event)"
                   placeholder="Rechercher…" autocomplete="off" aria-label="Rechercher dans la liste">
          </label>
        }
        <ul role="listbox" #ul (keydown)="clavier($event)" tabindex="-1">
          @for (o of filtrees(); track $index; let n = $index) {
            <li role="option" [attr.aria-selected]="o.valeur === valeur()" [class.choisie]="o.valeur === valeur()"
                [class.active]="n === active()" [class.desactive]="o.desactive" (pointerenter)="active.set(n)" (click)="choisir(o)">
              <span>{{ o.libelle }}@if (o.detail) { <small>{{ o.detail }}</small> }</span>
              @if (o.valeur === valeur()) { <fact-icone nom="coche" [taille]="16" /> }
            </li>
          } @empty {
            <li class="rien">Aucun résultat pour « {{ texte() }} »</li>
          }
        </ul>
      </div>
    }`,
  styles: [`
    :host { position: relative; display: block; }
    .declencheur { width: 100%; min-height: 52px; display: flex; align-items: center; gap: 8px; padding: 20px 12px 6px 14px;
      border-radius: var(--rayon-2); border: 1px solid var(--filet-fort); background: var(--surface); color: var(--encre);
      font: inherit; text-align: left; cursor: pointer;
      transition: border-color var(--courte) var(--courbe), box-shadow var(--courte) var(--courbe); }
    .declencheur.sans-libelle { padding-top: 6px; min-height: var(--tactile); }
    .declencheur:disabled { cursor: not-allowed; opacity: 0.6; background: var(--surface-2); }
    .declencheur:focus-visible, :host(.ouvert) .declencheur { outline: none; border-color: var(--marque);
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--marque) 22%, transparent); }
    .valeur { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .valeur i { font-style: normal; color: var(--discret); }
    small { margin-left: 6px; color: var(--discret); font-size: 12.5px; }
    .fleche { flex: none; color: var(--discret); transition: transform var(--standard) var(--courbe); }
    :host(.ouvert) .fleche { transform: rotate(180deg); }
    .etiquette { position: absolute; left: 14px; top: 16px; color: var(--discret); pointer-events: none; transform-origin: left top;
      transform: translateY(-11px) scale(0.78); }
    :host(.ouvert) .etiquette { color: var(--marque); }
    :host-context(.champ.erreur) .declencheur { border-color: var(--critique); animation: secousse var(--standard) var(--courbe); }

    .liste { position: absolute; z-index: 1200; left: 0; right: 0; min-width: min(260px, calc(100vw - 32px)); top: calc(100% + 6px); display: grid; overflow: hidden;
      border: 1px solid var(--filet); border-radius: var(--rayon-2); background: var(--surface);
      box-shadow: 0 18px 40px -12px rgba(22, 41, 63, 0.35); transform-origin: top center;
      animation: deroule var(--standard) var(--courbe) both; }
    .liste.droite { left: auto; }
    .liste.haut { top: auto; bottom: calc(100% + 6px); transform-origin: bottom center; }
    @keyframes deroule { from { opacity: 0; transform: translateY(-6px) scale(0.98); } }
    .recherche { display: flex; align-items: center; gap: 8px; padding: 0 12px; min-height: var(--tactile);
      border-bottom: 1px solid var(--filet); color: var(--discret); }
    .recherche input { flex: 1; min-width: 0; border: 0; outline: none; background: none; color: var(--encre); font: inherit; min-height: 42px; }
    ul { position: relative; list-style: none; margin: 0; padding: 4px; max-height: 264px; overflow-x: hidden; overflow-y: auto; overscroll-behavior: contain; outline: none; }
    li { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 42px; padding: 8px 10px;
      border-radius: 8px; cursor: pointer; transition: background var(--courte) var(--courbe); }
    li.active { background: var(--surface-3); }
    li.choisie { color: var(--marque); font-weight: 600; }
    li.desactive { opacity: 0.5; pointer-events: none; }
    li.rien { color: var(--discret); cursor: default; justify-content: center; font-size: 14px; }
    @media (prefers-reduced-motion: reduce) { .liste { animation: none; } }
  `],
})
export class Choix implements ControlValueAccessor {
  readonly libelle = input('');
  readonly options = input<OptionChoix[]>([]);
  /** Texte d'un choix « sans valeur » placé en tête ; absent, on ne peut pas revenir à vide. */
  readonly vide = input<string | null>(null);
  readonly invite = input('Choisir…');
  /** La recherche apparaît d'elle-même au-delà de ce nombre d'options. */
  readonly seuilRecherche = input(6);

  private readonly hote = inject(ElementRef<HTMLElement>);
  private readonly champ = viewChild<ElementRef<HTMLInputElement>>('champ');
  private readonly ul = viewChild<ElementRef<HTMLElement>>('ul');
  private readonly declencheur = viewChild.required<ElementRef<HTMLButtonElement>>('declencheur');

  protected readonly valeur = signal<unknown>(null);
  protected readonly ouvert = signal(false);
  protected readonly inactif = signal(false);
  protected readonly texte = signal('');
  protected readonly active = signal(0);
  protected readonly versLeHaut = signal(false);
  protected readonly aDroite = signal(false);

  private readonly toutes = computed<OptionChoix[]>(() =>
    this.vide() !== null ? [{ valeur: null, libelle: this.vide()! }, ...this.options()] : this.options());
  protected readonly avecRecherche = computed(() => this.options().length > this.seuilRecherche());
  protected readonly choisie = computed(() => {
    // null retrouve le choix « sans valeur » quand il existe ; sinon l'invite s'affiche.
    const v = this.valeur() ?? null;
    return this.toutes().find((o) => o.valeur === v) ?? null;
  });
  protected readonly filtrees = computed(() => {
    const t = simplifier(this.texte().trim());
    if (!t) return this.toutes();
    const mots = t.split(/\s+/);
    return this.toutes().filter((o) => {
      const cible = simplifier(o.libelle + ' ' + (o.detail ?? ''));
      return mots.every((m) => cible.includes(m));
    });
  });

  private auChangement: (v: unknown) => void = () => {};
  private auToucher: () => void = () => {};

  writeValue(v: unknown): void { this.valeur.set(v ?? null); }
  registerOnChange(fn: (v: unknown) => void): void { this.auChangement = fn; }
  registerOnTouched(fn: () => void): void { this.auToucher = fn; }
  setDisabledState(d: boolean): void { this.inactif.set(d); if (d) this.fermer(false); }

  protected basculer(): void {
    if (this.ouvert()) return this.fermer();
    // S'ouvre vers le haut s'il manque de la place sous le champ (bas d'un panneau, clavier du téléphone).
    const r = this.hote.nativeElement.getBoundingClientRect();
    this.versLeHaut.set(window.innerHeight - r.bottom < 320 && r.top > window.innerHeight - r.bottom);
    // Champ étroit près du bord droit (colonne de droite sur téléphone) : la liste s'aligne à droite.
    this.aDroite.set(r.left + 260 > window.innerWidth - 16);
    this.texte.set('');
    const i = this.toutes().findIndex((o) => o.valeur === this.valeur());
    this.active.set(Math.max(0, i));
    this.ouvert.set(true);
    setTimeout(() => {
      (this.champ()?.nativeElement ?? this.ul()?.nativeElement)?.focus();
      this.montrer(Math.max(0, this.active()));
    });
  }

  protected chercher(t: string): void {
    this.texte.set(t);
    this.active.set(0);
  }

  protected choisir(o: OptionChoix): void {
    if (o.desactive) return;
    this.valeur.set(o.valeur);
    this.auChangement(o.valeur);
    this.fermer();
  }

  protected clavier(e: KeyboardEvent): void {
    const l = this.filtrees();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const n = l.length ? (this.active() + (e.key === 'ArrowDown' ? 1 : -1) + l.length) % l.length : 0;
      this.active.set(n);
      this.montrer(n);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const o = l[this.active()];
      if (o) this.choisir(o);
    } else if (e.key === 'Tab') {
      this.fermer(false);
    }
  }

  /** Échap ferme la liste seule : le panneau autour reste ouvert, la saisie n'est pas perdue. */
  protected echap(e: Event): void {
    if (!this.ouvert()) return;
    e.preventDefault();
    e.stopPropagation();
    this.fermer();
  }

  protected clavierFerme(e: KeyboardEvent): void {
    if (!this.ouvert() && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
      e.preventDefault();
      this.basculer();
    }
  }

  /** Fait défiler la seule liste (pas le panneau autour) pour montrer l'option n. */
  private montrer(n: number): void {
    const ul = this.ul()?.nativeElement;
    const li = ul?.querySelectorAll('li')[n] as HTMLElement | undefined;
    if (!ul || !li) return;
    if (li.offsetTop < ul.scrollTop) ul.scrollTop = li.offsetTop - 4;
    else if (li.offsetTop + li.offsetHeight > ul.scrollTop + ul.clientHeight) ul.scrollTop = li.offsetTop + li.offsetHeight - ul.clientHeight + 4;
  }

  protected dehors(e: Event): void {
    if (this.ouvert() && !this.hote.nativeElement.contains(e.target as Node)) this.fermer(false);
  }

  private fermer(rendreLeFocus = true): void {
    if (!this.ouvert()) return;
    this.ouvert.set(false);
    this.auToucher();
    if (rendreLeFocus) this.declencheur().nativeElement.focus();
  }
}

/**
 * Transforme une liste en options de <fact-choix>, une seule fois par liste (pipe pur) :
 *   [options]="proprietaires() | options : optionProprietaire"
 * Le contexte facultatif est passé à la fabrique ; une fabrique qui rend null écarte l'élément
 * (par exemple le titulaire de la liste des suppléants).
 */
@Pipe({ name: 'options' })
export class OptionsPipe implements PipeTransform {
  transform<T, C>(liste: readonly T[] | null | undefined, fabrique: (x: T, contexte: C) => OptionChoix | null, contexte?: C): OptionChoix[] {
    return (liste ?? []).map((x) => fabrique(x, contexte as C)).filter((o): o is OptionChoix => o !== null);
  }
}
