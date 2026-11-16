import { Component, inject, signal } from '@angular/core';
import { CarteIndicateur } from '../../composants/carte-indicateur';
import { Confirmation } from '../../composants/confirmation';
import { EtatVide, Squelette } from '../../composants/etat-vide';
import { Etapes, Synchro } from '../../composants/etapes';
import { Evenement, FilHistorique } from '../../composants/fil-historique';
import { Anneau, Barres, Part } from '../../composants/graphiques';
import { Icone, NOMS_ICONES } from '../../composants/icone';
import { Apparition, Compteur } from '../../composants/mouvement';
import { PanneauLateral } from '../../composants/panneau-lateral';
import { PastilleStatut } from '../../composants/pastille-statut';
import { SelecteurThematique } from '../../composants/selecteur-thematique';
import { Colonne, Ligne, TableDonnees } from '../../composants/table-donnees';
import { LotEtage, VueEtages } from '../../composants/vue-etages';
import { AvisService } from '../../noyau/avis.service';
import { ThemeService } from '../../noyau/theme.service';

/**
 * Catalogue des composants (étape 2 du plan de réalisation) : chaque brique du § 5.4 des
 * spécifications, présentée dans tous ses états, en thème clair et sombre.
 * Toutes les données affichées ici sont des exemples fictifs, écrits dans ce fichier.
 */
@Component({
  selector: 'fact-catalogue',
  imports: [Icone, PastilleStatut, CarteIndicateur, Anneau, Barres, TableDonnees, Confirmation, EtatVide, Squelette,
    FilHistorique, VueEtages, SelecteurThematique, PanneauLateral, Etapes, Synchro, Apparition, Compteur],
  templateUrl: './catalogue.html',
  styleUrl: './catalogue.scss',
})
export class Catalogue {
  protected readonly avis = inject(AvisService);
  protected readonly theme = inject(ThemeService);

  protected readonly icones = NOMS_ICONES;

  protected readonly couleurs = [
    { nom: 'Bleu nuit', variable: '--nuit', usage: 'En-têtes, boutons principaux, liens' },
    { nom: 'Ocre', variable: '--ocre', usage: "Appels à l'action, éléments à la une" },
    { nom: 'Vert', variable: '--bon', usage: 'Occupé, à jour, validé' },
    { nom: 'Orange', variable: '--attention', usage: 'Vacance partielle, retard, à valider' },
    { nom: 'Rouge', variable: '--critique', usage: 'Impayés, urgence, erreur' },
    { nom: 'Bleu', variable: '--info', usage: 'Information, en cours' },
  ];

  protected readonly statutsLot = [
    { code: 'VACANT', libelle: 'Vacant' }, { code: 'A_LOUER', libelle: 'À louer' }, { code: 'RESERVE', libelle: 'Réservé' },
    { code: 'OCCUPE', libelle: 'Occupé' }, { code: 'EN_PREAVIS', libelle: 'En préavis' }, { code: 'EN_TRAVAUX', libelle: 'En travaux' },
    { code: 'HORS_GESTION', libelle: 'Hors gestion' },
  ];
  protected readonly statutsPaiement = [
    { code: 'A_JOUR', libelle: 'À jour' }, { code: 'PARTIEL', libelle: 'Paiement partiel' }, { code: 'IMPAYE', libelle: 'Impayé' },
  ];
  protected readonly statutsIntervention = [
    { code: 'SIGNALEE', libelle: 'Signalée' }, { code: 'AFFECTEE', libelle: 'Affectée' }, { code: 'EN_COURS', libelle: 'En cours' },
    { code: 'TERMINEE', libelle: 'Terminée' }, { code: 'CLOTUREE', libelle: 'Clôturée' },
  ];

  // ------------------------------------------------------------ boutons et champs
  protected readonly occupe = signal(false);
  protected readonly erreurChamp = signal(false);

  protected enregistrer(): void {
    this.occupe.set(true);
    setTimeout(() => {
      this.occupe.set(false);
      this.avis.succes('Enregistré.');
    }, 1400);
  }

  protected secouer(): void {
    // Retirer puis remettre la classe rejoue la secousse.
    this.erreurChamp.set(false);
    setTimeout(() => this.erreurChamp.set(true));
  }

  // ------------------------------------------------------------------ indicateurs
  protected readonly periode = signal<'mois' | 'trimestre'>('mois');
  protected readonly occupation: Part[] = [
    { libelle: 'Occupés', valeur: 148, ton: 'bon' },
    { libelle: 'Vacants', valeur: 9, ton: 'attention' },
    { libelle: 'En travaux', valeur: 3, ton: 'info' },
  ];
  protected readonly vacance: Part[] = [
    { libelle: 'Cocody', valeur: 4, ton: 'attention' },
    { libelle: 'Yopougon', valeur: 3, ton: 'attention' },
    { libelle: 'Marcory', valeur: 1, ton: 'bon' },
    { libelle: 'Bingerville', valeur: 1, ton: 'bon' },
  ];

  // ---------------------------------------------------------------------- tableau
  protected readonly colonnes: Colonne[] = [
    { cle: 'reference', libelle: 'Référence', format: 'reference' },
    { cle: 'batiment', libelle: 'Bâtiment' },
    { cle: 'type', libelle: 'Type' },
    { cle: 'surface', libelle: 'Surface', format: 'm2' },
    { cle: 'loyer', libelle: 'Loyer', format: 'fcfa' },
    { cle: 'statut', libelle: 'Statut', format: 'statut', cleLibelle: 'libelleStatut' },
    { cle: 'echeance', libelle: 'Fin de bail', format: 'date' },
  ];
  protected readonly lignes: Ligne[] = [
    ['EX-001', 'Résidence Exemple A', 'F3', 85, 375000, 'OCCUPE', 'Occupé', '2027-03-31'],
    ['EX-002', 'Résidence Exemple A', 'F2', 60, 250000, 'OCCUPE', 'Occupé', '2026-12-31'],
    ['EX-003', 'Résidence Exemple A', 'Studio', 32, 150000, 'VACANT', 'Vacant', null],
    ['EX-004', 'Immeuble Exemple B', 'F4', 120, 550000, 'EN_PREAVIS', 'En préavis', '2026-11-30'],
    ['EX-005', 'Immeuble Exemple B', 'Magasin', 45, 300000, 'OCCUPE', 'Occupé', '2028-01-31'],
    ['EX-006', 'Immeuble Exemple B', 'F3', 90, 400000, 'A_LOUER', 'À louer', null],
    ['EX-007', 'Villa Exemple C', 'Villa', 320, 2500000, 'OCCUPE', 'Occupé', '2027-08-31'],
    ['EX-008', 'Immeuble Exemple D', 'F2', 58, 220000, 'EN_TRAVAUX', 'En travaux', null],
    ['EX-009', 'Immeuble Exemple D', 'F3', 82, 285000, 'RESERVE', 'Réservé', null],
    ['EX-010', 'Immeuble Exemple D', 'Bureau', 70, 450000, 'OCCUPE', 'Occupé', '2027-06-30'],
    ['EX-011', 'Immeuble Exemple D', 'F2', 55, 210000, 'OCCUPE', 'Occupé', '2027-02-28'],
    ['EX-012', 'Immeuble Exemple D', 'Studio', 30, 140000, 'VACANT', 'Vacant', null],
  ].map(([reference, batiment, type, surface, loyer, statut, libelleStatut, echeance]) =>
    ({ reference, batiment, type, surface, loyer, statut, libelleStatut, echeance }));
  protected readonly chargementTable = signal(false);
  protected readonly ligneModifiee = signal<unknown>(null);

  protected rechargerTable(): void {
    this.chargementTable.set(true);
    setTimeout(() => this.chargementTable.set(false), 1500);
  }

  protected ouvrirLigne(l: Ligne): void {
    this.ligneModifiee.set(null);
    setTimeout(() => this.ligneModifiee.set(l['reference']));
    this.avis.info(`Ligne ${l['reference']} : la fiche s'ouvrira ici.`);
  }

  // ----------------------------------------------------------------- vue par étage
  protected readonly lots: LotEtage[] = [
    [1, 'A01', 0, 'Magasin', 'OCCUPE', 'Occupé'], [2, 'A02', 0, 'Magasin', 'A_LOUER', 'À louer'],
    [3, 'A11', 1, 'F3', 'OCCUPE', 'Occupé'], [4, 'A12', 1, 'F2', 'OCCUPE', 'Occupé'], [5, 'A13', 1, 'Studio', 'VACANT', 'Vacant'],
    [6, 'A21', 2, 'F3', 'OCCUPE', 'Occupé'], [7, 'A22', 2, 'F2', 'EN_PREAVIS', 'En préavis'], [8, 'A23', 2, 'Studio', 'OCCUPE', 'Occupé'],
    [9, 'A31', 3, 'F4', 'EN_TRAVAUX', 'En travaux'], [10, 'A32', 3, 'F3', 'RESERVE', 'Réservé'],
  ].map(([id, numero, etage, type, statut, libelleStatut]) => ({ id, numero, etage, type, statut, libelleStatut }) as LotEtage);
  protected readonly lotChoisi = signal<number | null>(3);

  // ------------------------------------------------------- thématique et panneau
  protected readonly thematique = signal('occupation');
  protected readonly panneauOuvert = signal(true);

  // -------------------------------------------------------------------- historique
  protected readonly evenements: Evenement[] = [
    { le: '2026-11-12T09:40:00', auteur: 'Gestionnaire (exemple)', action: 'Encaissement saisi', detail: 'Loyer de novembre : 375 000 FCFA, mobile money', ton: 'bon' },
    { le: '2026-11-06T16:05:00', auteur: 'Système', action: 'Loyer en retard', detail: 'Non soldé cinq jours après son exigibilité', ton: 'critique' },
    { le: '2026-10-20T11:12:00', auteur: 'Agent de terrain (exemple)', action: 'Intervention signalée', detail: 'Plomberie : fuite sous évier', ton: 'attention' },
    { le: '2026-04-01T08:30:00', auteur: 'Gestionnaire (exemple)', action: 'Bail créé', detail: 'Statut du lot : Vacant → Occupé', ton: 'info' },
  ];

  // ----------------------------------------------------------------------- étapes
  protected readonly etapesRecensement = ['Position', 'Identité', 'Lots', 'Photos'];
  protected readonly etape = signal(1);

  // ----------------------------------------------------------------- confirmation
  protected readonly confirmation = signal<'danger' | 'simple' | null>(null);

  protected confirmer(): void {
    const danger = this.confirmation() === 'danger';
    this.confirmation.set(null);
    this.avis.succes(danger ? 'Bâtiment archivé (exemple) : il reste consultable.' : 'Cinq bâtiments réaffectés (exemple).');
  }
}
