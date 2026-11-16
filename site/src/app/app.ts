import { Component, ElementRef, NgZone, OnDestroy, OnInit, inject, signal, viewChild } from '@angular/core';
import { NavigationEnd, NavigationStart, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { filter } from 'rxjs';
import { Icone } from './composants/icone';
import { Logo } from './composants/logo';
import { Prechargeur } from './composants/prechargeur';
import { AGENCE, METIERS } from './noyau/donnees';
import { FavorisService } from './noyau/favoris.service';
import { COURBE, mouvementReduit } from './noyau/mouvement';

/**
 * La coque du site : barre de navigation (elle se réduit et prend un fond plein après 80 px de
 * défilement, § 6.2), menu plein écran sur téléphone, pied de page, bouton WhatsApp flottant.
 */
@Component({
  selector: 'site-racine',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icone, Logo, Prechargeur],
  host: { '(window:scroll)': 'defile()', '(document:keydown.escape)': 'fermerMenu()' },
  template: `
    <site-prechargeur />
    <a class="evitement" href="#contenu">Aller au contenu</a>
    <header class="barre" [class.pleine]="pleine() || menu()">
      <div class="interieur">
        <a routerLink="/" class="marque" aria-label="FACT Immobilier, accueil" (click)="fermerMenu()">
          <site-logo [variante]="pleine() || menu() ? 'clair' : 'sombre'" largeur="100%" />
        </a>
        <nav class="liens" aria-label="Navigation principale">
          @for (l of liens; track l.lien) {
            <a [routerLink]="l.lien" routerLinkActive="actif">{{ l.libelle }}</a>
          }
        </nav>
        <a class="favoris-lien" routerLink="/favoris" routerLinkActive="actif" [attr.aria-label]="'Mes favoris : ' + favoris.nombre()" title="Mes favoris">
          <fact-icone nom="coeur" [taille]="22" />
          @if (favoris.nombre()) { <span class="pastille-nb">{{ favoris.nombre() }}</span> }
        </a>
        <a class="btn accent cta" routerLink="/confier-mon-bien">Confier mon bien</a>
        <button type="button" class="burger" [class.ouvert]="menu()" (click)="basculerMenu()"
                [attr.aria-expanded]="menu()" aria-controls="menu-mobile" aria-label="Menu">
          <span></span><span></span><span></span>
        </button>
      </div>
    </header>

    <div #menuMobile id="menu-mobile" class="menu-mobile" [class.visible]="menu()" [attr.aria-hidden]="!menu()">
      <nav aria-label="Menu">
        @for (l of liensMobile; track l.lien) {
          <a class="item" [routerLink]="l.lien" (click)="fermerMenu()">{{ l.libelle }}</a>
        }
      </nav>
      <div class="item coordonnees">
        <a [href]="agence.telephones[0].lien"><fact-icone nom="telephone" [taille]="18" /> {{ agence.telephones[0].libelle }}</a>
        <a [href]="'mailto:' + agence.email"><fact-icone nom="mail" [taille]="18" /> {{ agence.email }}</a>
      </div>
    </div>

    <main id="contenu" tabindex="-1">
      <router-outlet />
    </main>

    <footer class="pied">
      <div class="colonnes">
        <div>
          <a routerLink="/" class="marque claire" aria-label="FACT Immobilier, accueil"><site-logo variante="sombre" largeur="100%" /></a>
          <p>Agence immobilière à Abidjan depuis {{ agence.demarrage }}. Gestion locative, transactions, construction et travaux.</p>
          <p class="cdaim"><fact-icone nom="bouclier" [taille]="18" /> Membre de la CDAIM</p>
        </div>
        <div>
          <h2>Nos services</h2>
          <ul>@for (m of metiers; track m.slug) { <li><a [routerLink]="['/services', m.slug]">{{ m.titre }}</a></li> }</ul>
        </div>
        <div>
          <h2>Le site</h2>
          <ul>
            <li><a routerLink="/louer">Biens à louer</a></li>
            <li><a routerLink="/acheter">Biens à vendre</a></li>
            <li><a routerLink="/agence">L’agence</a></li>
            <li><a routerLink="/devis">Devis et estimation</a></li>
            <li><a routerLink="/mentions-legales">Mentions légales</a></li>
          </ul>
        </div>
        <div>
          <h2>Nous joindre</h2>
          <address>
            {{ agence.adresse }}<br>{{ agence.ville }}<br>
            @for (t of agence.telephones; track t.lien) { <a [href]="t.lien">{{ t.libelle }}</a><br> }
            <a [href]="'mailto:' + agence.email">{{ agence.email }}</a>
          </address>
        </div>
      </div>
      <p class="copyright">© {{ annee }} FACT Immobilier · Abidjan, Côte d’Ivoire</p>
    </footer>

    <a #whatsapp class="whatsapp" [href]="agence.whatsapp" target="_blank" rel="noopener" aria-label="Écrire à l’agence sur WhatsApp">
      <fact-icone nom="whatsapp" [taille]="28" />
    </a>`,
  styleUrl: './app.scss',
})
export class App implements OnInit, OnDestroy {
  private readonly router = inject(Router);
  private readonly zone = inject(NgZone);
  private readonly menuMobile = viewChild.required<ElementRef<HTMLElement>>('menuMobile');
  private readonly whatsapp = viewChild.required<ElementRef<HTMLElement>>('whatsapp');

  protected readonly agence = AGENCE;
  protected readonly favoris = inject(FavorisService);
  protected readonly metiers = METIERS;
  protected readonly annee = new Date().getFullYear();
  protected readonly pleine = signal(false);
  protected readonly menu = signal(false);
  protected readonly liens = [
    { lien: '/louer', libelle: 'Louer' },
    { lien: '/acheter', libelle: 'Acheter' },
    { lien: '/services', libelle: 'Nos services' },
    { lien: '/agence', libelle: 'L’agence' },
    { lien: '/contact', libelle: 'Contact' },
  ];
  protected readonly liensMobile = [{ lien: '/', libelle: 'Accueil' }, ...this.liens, { lien: '/favoris', libelle: 'Mes favoris' },
    { lien: '/confier-mon-bien', libelle: 'Confier mon bien' }];

  private contexte: gsap.Context | null = null;

  ngOnInit(): void {
    this.defile();
    // À chaque page, les déclencheurs de défilement sont recalculés (hauteurs nouvelles).
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      setTimeout(() => ScrollTrigger.refresh(), 400);
    });
    // Sens de la transition de page : en avant vers une page nouvelle, en arrière au retour.
    this.router.events.pipe(filter((e): e is NavigationStart => e instanceof NavigationStart)).subscribe((e) => {
      document.documentElement.dataset['sens'] = e.navigationTrigger === 'popstate' ? 'arriere' : 'avant';
    });
    // Le bouton WhatsApp apparaît par agrandissement après 3 secondes, puis pulse toutes les 10 secondes.
    this.zone.runOutsideAngular(() => {
      this.contexte = gsap.context(() => {
        const b = this.whatsapp().nativeElement;
        if (mouvementReduit()) {
          gsap.from(b, { opacity: 0, duration: 0.3, delay: 3 });
          return;
        }
        gsap.from(b, { scale: 0, opacity: 0, duration: 0.6, delay: 3, ease: 'back.out(2)' });
        gsap.timeline({ delay: 13, repeat: -1, repeatDelay: 10 })
          .to(b, { scale: 1.12, duration: 0.25, yoyo: true, repeat: 1, ease: 'power1.inOut' });
      });
    });
  }

  ngOnDestroy(): void {
    this.contexte?.revert();
  }

  protected defile(): void {
    const p = window.scrollY > 80;
    if (p !== this.pleine()) this.pleine.set(p);
  }

  protected basculerMenu(): void {
    this.menu() ? this.fermerMenu() : this.ouvrirMenu();
  }

  protected ouvrirMenu(): void {
    this.menu.set(true);
    document.body.style.overflow = 'hidden';
    if (mouvementReduit()) return;
    const items = this.menuMobile().nativeElement.querySelectorAll('.item');
    gsap.fromTo(items, { y: 28, opacity: 0 }, { y: 0, opacity: 1, duration: 0.45, ease: COURBE, stagger: 0.05, delay: 0.08 });
  }

  protected fermerMenu(): void {
    if (!this.menu()) return;
    this.menu.set(false);
    document.body.style.overflow = '';
  }
}
