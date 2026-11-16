import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { gsap } from 'gsap';
import { Choix, OptionChoix } from '../composants/choix';
import { Icone } from '../composants/icone';
import { VignetteBien } from '../composants/vignette-bien';
import { CommunesService } from '../noyau/communes.service';
import { Api, Chiffres, Vignette } from '../noyau/api';
import { AGENCE, METIERS, TYPES_BIEN } from '../noyau/donnees';
import { COURBE, MOUVEMENT, mouvementReduit } from '../noyau/mouvement';

/**
 * Page d'accueil (§ 3.1) : bandeau animé avec le moteur de recherche, chiffres clés, quatre
 * métiers, biens à la une, valeurs de l'agence et appel aux propriétaires.
 */
@Component({
  selector: 'site-accueil',
  imports: [FormsModule, RouterLink, Icone, Choix, VignetteBien, ...MOUVEMENT],
  templateUrl: './accueil.html',
  styleUrl: './accueil.scss',
})
export class Accueil implements OnInit, AfterViewInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);
  protected readonly communes = inject(CommunesService);
  private readonly heros = viewChild.required<ElementRef<HTMLElement>>('heros');

  protected readonly lignes = [['Votre', 'partenaire'], ['immobilier', 'de', 'confiance'], ['à', 'Abidjan.']];
  protected readonly agence = AGENCE;
  protected readonly metiers = METIERS;
  private readonly api = inject(Api);
  protected readonly une = signal<Vignette[] | null>(null);
  /** Chiffres clés calculés par le SIG ; une valeur absente ou nulle n'est pas affichée (§ 3.1). */
  protected readonly chiffres = signal<{ valeur: number; libelle: string }[]>([]);

  protected readonly operation = signal<'louer' | 'acheter'>('louer');
  protected type: string | null = null;
  protected commune: number | null = null;
  protected budget: number | null = null;

  protected readonly optionsTypes: OptionChoix[] = TYPES_BIEN.map((t) => ({ valeur: t, libelle: t }));
  protected readonly optionsCommunes = computed<OptionChoix[]>(() =>
    this.communes.communes().map((c) => ({ valeur: c.id, libelle: c.libelle })));

  private contexte: gsap.Context | null = null;

  ngOnInit(): void {
    this.communes.assurer();
    this.api.aLaUne().then((l) => this.une.set(l)).catch(() => this.une.set([]));
    this.api.chiffres().then((c) => this.chiffres.set(this.cles(c))).catch(() => this.chiffres.set([]));
  }

  private cles(c: Chiffres): { valeur: number; libelle: string }[] {
    const l: [number | undefined, string][] = [
      [c.annees, 'années d’activité à Abidjan'],
      [c.batiments, 'bâtiments gérés pour leurs propriétaires'],
      [c.lots, 'logements et locaux suivis'],
      [c.communes, 'communes du District d’Abidjan'],
      [c.enLigne, 'biens disponibles en ce moment'],
    ];
    return l.filter(([v]) => typeof v === 'number' && v > 0).map(([v, libelle]) => ({ valeur: v!, libelle }));
  }

  ngAfterViewInit(): void {
    const racine = this.heros().nativeElement;
    this.zone.runOutsideAngular(() => {
      this.contexte = gsap.context(() => {
        if (mouvementReduit()) {
          gsap.from('.contenu-heros', { opacity: 0, duration: 0.4 });
          return;
        }
        // Le titre se compose mot à mot, ligne par ligne, puis le texte et le moteur de recherche.
        gsap.timeline({ defaults: { ease: COURBE } })
          .from('.mot', { yPercent: 110, rotate: 4, duration: 0.8, stagger: 0.07 }, 0.15)
          .from('.surtitre-heros', { y: 16, opacity: 0, duration: 0.5 }, 0)
          .from('.texte-heros', { y: 20, opacity: 0, duration: 0.6 }, '-=0.45')
          .from('.recherche', { y: 36, opacity: 0, scale: 0.98, duration: 0.7 }, '-=0.4')
          .from('.ruban span', { y: 12, opacity: 0, duration: 0.4, stagger: 0.06 }, '-=0.3');
        // Léger zoom lent sur le fond, puis parallaxe des plans de la ville au défilement.
        gsap.fromTo('.ciel', { scale: 1.12 }, { scale: 1, duration: 8, ease: 'power1.out' });
        gsap.to('.plan-loin', { yPercent: 18, ease: 'none', scrollTrigger: { trigger: racine, start: 'top top', end: 'bottom top', scrub: true } });
        gsap.to('.plan-pres', { yPercent: 8, ease: 'none', scrollTrigger: { trigger: racine, start: 'top top', end: 'bottom top', scrub: true } });
        gsap.to('.contenu-heros', { yPercent: -10, opacity: 0.2, ease: 'none',
          scrollTrigger: { trigger: racine, start: 'center top', end: 'bottom top', scrub: true } });
        gsap.to('.soleil', { y: -14, duration: 3.5, repeat: -1, yoyo: true, ease: 'sine.inOut' });
      }, racine);
    });
  }

  ngOnDestroy(): void {
    this.contexte?.revert();
  }

  protected rechercher(): void {
    const params: Record<string, string | number> = {};
    if (this.type) params['types'] = this.type;
    if (this.commune) params['commune'] = this.commune;
    if (this.budget && this.budget > 0) params['prixMax'] = Math.round(this.budget);
    this.router.navigate(['/' + this.operation()], { queryParams: params });
  }
}
