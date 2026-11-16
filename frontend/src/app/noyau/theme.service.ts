import { Injectable, signal } from '@angular/core';

export type Theme = 'clair' | 'sombre';

const CLE = 'fact-immobilier.theme';

/**
 * Thème clair par défaut, thème sombre disponible dans l'application de gestion (§ 5.1).
 * Le choix est gardé sur l'appareil ; il se pose sur <html data-theme="…">.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>('clair');

  constructor() {
    let garde: string | null = null;
    try {
      garde = localStorage.getItem(CLE);
    } catch {
      /* stockage indisponible : le thème reste en mémoire */
    }
    this.appliquer(garde === 'sombre' ? 'sombre' : 'clair');
  }

  basculer(): void {
    this.appliquer(this.theme() === 'clair' ? 'sombre' : 'clair');
  }

  private appliquer(theme: Theme): void {
    this.theme.set(theme);
    document.documentElement.setAttribute('data-theme', theme);
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', theme === 'sombre' ? '#0b1528' : '#1a3f7a');
    try {
      localStorage.setItem(CLE, theme);
    } catch {
      /* rien */
    }
  }
}
