import { AfterViewInit, Directive, ElementRef, NgZone, OnDestroy, effect, inject, input } from '@angular/core';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

gsap.registerPlugin(ScrollTrigger);

/** Courbe du § 6.1 (« départ franc, arrivée douce ») traduite pour GSAP. */
export const COURBE = 'power3.out';

/** Vrai si le visiteur a demandé la réduction des animations : on se contente de fondus. */
export function mouvementReduit(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Base commune : les animations sont créées hors de la zone Angular (pas de détection de
 * changement à chaque image) dans un contexte GSAP, défait à la destruction du composant.
 */
@Directive()
abstract class Animation implements AfterViewInit, OnDestroy {
  protected readonly el = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
  private readonly zone = inject(NgZone);
  private contexte: gsap.Context | null = null;

  ngAfterViewInit(): void {
    this.zone.runOutsideAngular(() => {
      this.contexte = gsap.context(() => this.animer(mouvementReduit()), this.el);
    });
  }

  ngOnDestroy(): void {
    this.contexte?.revert();
  }

  protected abstract animer(reduit: boolean): void;

  protected declencheur(cible: Element = this.el): ScrollTrigger.Vars {
    return { trigger: cible, start: 'top 88%', once: true };
  }
}

/** Section qui monte de 24 px en fondu à son entrée dans l'écran (§ 6.2), une fois par visite. */
@Directive({ selector: '[siteRevele]' })
export class Revele extends Animation {
  readonly delai = input(0, { alias: 'siteRevele', transform: (v: unknown) => Number(v) || 0 });

  protected animer(reduit: boolean): void {
    gsap.from(this.el, reduit
      ? { opacity: 0, duration: 0.3, scrollTrigger: this.declencheur() }
      : { y: 24, opacity: 0, duration: 0.6, delay: this.delai(), ease: COURBE, scrollTrigger: this.declencheur() });
  }
}

/** Les enfants directs apparaissent en cascade : 60 ms d'écart, huit au plus (§ 6.1). */
@Directive({ selector: '[siteCascade]' })
export class Cascade extends Animation {
  protected animer(reduit: boolean): void {
    const enfants = Array.from(this.el.children) as HTMLElement[];
    if (!enfants.length) return;
    gsap.from(enfants, reduit
      ? { opacity: 0, duration: 0.3, scrollTrigger: this.declencheur() }
      : { y: 32, opacity: 0, scale: 0.98, duration: 0.6, ease: COURBE, scrollTrigger: this.declencheur(),
          stagger: (i: number) => Math.min(i, 7) * 0.06 });
  }
}

/** Compteur qui défile de zéro à la valeur à l'entrée dans l'écran. */
@Directive({ selector: '[siteCompteur]' })
export class Compteur extends Animation {
  readonly valeur = input.required<number>({ alias: 'siteCompteur' });
  readonly suffixe = input('');

  protected animer(reduit: boolean): void {
    const fin = this.valeur();
    const ecrire = (v: number): void => void (this.el.textContent = Math.round(v).toLocaleString('fr-FR').replace(/ /g, ' ') + this.suffixe());
    if (reduit) {
      ecrire(fin);
      return;
    }
    const etat = { v: 0 };
    ecrire(0);
    gsap.to(etat, { v: fin, duration: 1.6, ease: 'power2.out', scrollTrigger: this.declencheur(), onUpdate: () => { ecrire(etat.v); } });
  }
}

/** Les tracés SVG d'un pictogramme se dessinent trait par trait (blocs métiers, § 6.2). */
@Directive({ selector: '[siteDessin]' })
export class Dessin extends Animation {
  protected animer(reduit: boolean): void {
    if (reduit) return;
    const traces = Array.from(this.el.querySelectorAll('path, circle, rect')) as SVGGeometryElement[];
    traces.forEach((t) => {
      const l = Math.ceil(t.getTotalLength?.() ?? 60) + 2;
      gsap.set(t, { strokeDasharray: l, strokeDashoffset: l });
    });
    gsap.to(traces, { strokeDashoffset: 0, duration: 1.1, ease: 'power2.inOut', stagger: 0.12, scrollTrigger: this.declencheur() });
  }
}

/** Léger décalage au défilement (parallaxe) : l'élément glisse plus lentement que la page. */
@Directive({ selector: '[siteParallaxe]' })
export class Parallaxe extends Animation {
  readonly force = input(12, { alias: 'siteParallaxe', transform: (v: unknown) => Number(v) || 12 });

  protected animer(reduit: boolean): void {
    if (reduit) return;
    gsap.to(this.el, { yPercent: this.force(), ease: 'none',
      scrollTrigger: { trigger: this.el.parentElement ?? this.el, start: 'top top', end: 'bottom top', scrub: true } });
  }
}

/** Bouton « aimanté » : il suit légèrement le pointeur (souris uniquement), puis revient en place. */
@Directive({ selector: '[siteAimant]' })
export class Aimant extends Animation {
  private retirer: (() => void) | null = null;

  protected animer(reduit: boolean): void {
    if (reduit || !matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    const versX = gsap.quickTo(this.el, 'x', { duration: 0.4, ease: COURBE });
    const versY = gsap.quickTo(this.el, 'y', { duration: 0.4, ease: COURBE });
    const bouge = (e: PointerEvent) => {
      const r = this.el.getBoundingClientRect();
      versX((e.clientX - r.left - r.width / 2) * 0.25);
      versY((e.clientY - r.top - r.height / 2) * 0.35);
    };
    const quitte = () => { versX(0); versY(0); };
    this.el.addEventListener('pointermove', bouge);
    this.el.addEventListener('pointerleave', quitte);
    this.retirer = () => {
      this.el.removeEventListener('pointermove', bouge);
      this.el.removeEventListener('pointerleave', quitte);
    };
  }

  override ngOnDestroy(): void {
    this.retirer?.();
    super.ngOnDestroy();
  }
}

/**
 * Inclinaison 3D d'une carte qui suit la souris, avec un reflet qui se déplace (souris
 * uniquement : rien sur écran tactile). Seules transform et opacité sont animées.
 */
@Directive({ selector: '[siteInclinaison]' })
export class Inclinaison extends Animation {
  readonly force = input(7, { alias: 'siteInclinaison', transform: (v: unknown) => Number(v) || 7 });
  private retirer: (() => void) | null = null;

  protected animer(reduit: boolean): void {
    if (reduit || !matchMedia('(hover: hover) and (pointer: fine)').matches) return;
    const cible = (this.el.firstElementChild as HTMLElement | null) ?? this.el;
    const reflet = document.createElement('span');
    reflet.className = 'reflet-carte';
    reflet.setAttribute('aria-hidden', 'true');
    cible.appendChild(reflet);
    gsap.set(this.el, { perspective: 900 });
    gsap.set(cible, { transformStyle: 'preserve-3d' });
    const rx = gsap.quickTo(cible, 'rotationX', { duration: 0.5, ease: COURBE });
    const ry = gsap.quickTo(cible, 'rotationY', { duration: 0.5, ease: COURBE });
    const bouge = (e: PointerEvent) => {
      const r = cible.getBoundingClientRect();
      const px = (e.clientX - r.left) / r.width, py = (e.clientY - r.top) / r.height;
      ry((px - 0.5) * this.force() * 2);
      rx((0.5 - py) * this.force() * 2);
      gsap.to(reflet, { opacity: 1, x: (px - 0.5) * r.width * 0.6, y: (py - 0.5) * r.height * 0.6, duration: 0.4, ease: COURBE });
    };
    const quitte = () => {
      rx(0);
      ry(0);
      gsap.to(reflet, { opacity: 0, duration: 0.4 });
    };
    this.el.addEventListener('pointermove', bouge);
    this.el.addEventListener('pointerleave', quitte);
    this.retirer = () => {
      this.el.removeEventListener('pointermove', bouge);
      this.el.removeEventListener('pointerleave', quitte);
      reflet.remove();
    };
  }

  override ngOnDestroy(): void {
    this.retirer?.();
    super.ngOnDestroy();
  }
}

/**
 * Entrée des cartes au défilement, par lots (ScrollTrigger.batch) : chaque groupe de cartes qui
 * entre ensemble à l'écran monte en cascade (60 ms d'écart, § 6.1), une seule fois.
 */
@Directive({ selector: '[siteLot]' })
export class Lot extends Animation {
  private declencheurs: ScrollTrigger[] = [];

  protected animer(reduit: boolean): void {
    const enfants = Array.from(this.el.children) as HTMLElement[];
    if (!enfants.length) return;
    gsap.set(enfants, reduit ? { opacity: 0 } : { opacity: 0, y: 40, scale: 0.97 });
    this.declencheurs = ScrollTrigger.batch(enfants, {
      start: 'top 92%',
      once: true,
      onEnter: (lot) => gsap.to(lot, reduit
        ? { opacity: 1, duration: 0.3 }
        : { opacity: 1, y: 0, scale: 1, duration: 0.7, ease: COURBE, stagger: 0.06, overwrite: true, clearProps: 'transform' }),
    });
  }

  override ngOnDestroy(): void {
    this.declencheurs.forEach((d) => d.kill());
    super.ngOnDestroy();
  }
}

/**
 * Grand titre de section : découpé en mots qui montent derrière un masque, puis un
 * soulignement bleu vif qui se trace. Joue une seule fois (le découpage est fait par nous :
 * SplitText n'est pas dans la version libre de GSAP utilisée).
 */
@Directive({ selector: '[siteTitre]' })
export class Titre extends Animation {
  /** Faux : pas de soulignement (titres des bandeaux sombres). */
  readonly souligne = input(true, { alias: 'siteTitre', transform: (v: unknown) => v !== false && v !== 'false' });

  protected animer(reduit: boolean): void {
    if (this.el.dataset['decoupe']) return;
    this.el.dataset['decoupe'] = '1';
    const texte = this.el.textContent?.trim() ?? '';
    if (!texte) return;
    this.el.setAttribute('aria-label', texte);
    this.el.textContent = '';
    const mots: HTMLElement[] = [];
    texte.split(/\s+/).forEach((m, i, l) => {
      const masque = document.createElement('span');
      masque.className = 'masque-mot';
      masque.setAttribute('aria-hidden', 'true');
      const mot = document.createElement('span');
      mot.className = 'mot-titre';
      mot.textContent = m;
      masque.appendChild(mot);
      this.el.appendChild(masque);
      if (i < l.length - 1) this.el.appendChild(document.createTextNode(' '));
      mots.push(mot);
    });
    let trait: HTMLElement | null = null;
    if (this.souligne()) {
      trait = document.createElement('span');
      trait.className = 'trait-titre';
      trait.setAttribute('aria-hidden', 'true');
      this.el.appendChild(trait);
    }
    if (reduit) {
      gsap.from(this.el, { opacity: 0, duration: 0.3, scrollTrigger: this.declencheur() });
      return;
    }
    const tl = gsap.timeline({ scrollTrigger: this.declencheur() });
    tl.from(mots, { yPercent: 115, rotate: 3, duration: 0.75, ease: COURBE, stagger: 0.06 });
    if (trait) tl.from(trait, { scaleX: 0, duration: 0.6, ease: 'power2.inOut' }, '-=0.35');
  }
}

/**
 * Un nombre qui passe d'une valeur à l'autre en s'animant (prix, totaux), au format français.
 * <span [siteNombre]="b.prix" suffixe=" FCFA / mois"></span>
 */
@Directive({ selector: '[siteNombre]' })
export class Nombre implements OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
  readonly valeur = input.required<number>({ alias: 'siteNombre' });
  readonly suffixe = input('');
  private affiche: number | null = null;
  private tween: gsap.core.Tween | null = null;

  constructor() {
    effect(() => {
      const v = this.valeur();
      const s = this.suffixe();
      const ecrire = (n: number) => (this.el.textContent = Math.round(n).toLocaleString('fr-FR').replace(/[  ]/g, ' ') + s);
      this.tween?.kill();
      if (this.affiche === null || mouvementReduit()) {
        ecrire(v);
        this.affiche = v;
        return;
      }
      const etat = { n: this.affiche };
      this.tween = gsap.to(etat, { n: v, duration: 0.6, ease: 'power2.out', onUpdate: () => { ecrire(etat.n); }, onComplete: () => { this.affiche = v; } });
    });
  }

  ngOnDestroy(): void {
    this.tween?.kill();
  }
}


export const MOUVEMENT = [Revele, Cascade, Compteur, Dessin, Parallaxe, Aimant, Inclinaison, Lot, Titre, Nombre] as const;

