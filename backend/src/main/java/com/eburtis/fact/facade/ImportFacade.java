package com.eburtis.fact.facade;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eburtis.fact.domain.Commune;
import com.eburtis.fact.domain.Quartier;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.domain.Valeur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.ReferentielService;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import initial du patrimoine depuis Excel (SIG-10, § 7.3) : un classeur à trois feuilles —
 * Propriétaires, Bâtiments, Lots. Le fichier est d'abord contrôlé en entier ; rien n'est écrit
 * tant qu'il reste une erreur. Les fiches déjà présentes sont reconnues et laissées telles
 * quelles (contrôle des doublons). Les bâtiments sans coordonnées entrent « à localiser », et
 * tous entrent « à valider » par leur gestionnaire.
 */
@Service
public class ImportFacade {

    private static final int LIGNES_MAX = 5000;
    private static final String F_PROPRIETAIRES = "Propriétaires";
    private static final String F_BATIMENTS = "Bâtiments";
    private static final String F_LOTS = "Lots";

    private static final String[] C_PROPRIETAIRES = {"Nom ou raison sociale *", "Prénoms", "Type", "Téléphone", "E-mail", "Adresse"};
    private static final String[] C_BATIMENTS = {"Nom du bâtiment *", "Type *", "Propriétaire *", "Commune *", "Quartier", "Adresse",
            "Latitude", "Longitude", "Niveaux", "Année de construction"};
    private static final String[] C_LOTS = {"Bâtiment *", "Numéro de porte *", "Étage", "Type *", "Surface (m²)", "Pièces",
            "Loyer (FCFA)", "Charges (FCFA)", "Statut"};

    /** Une ligne du fichier qui ne peut pas être importée, et pourquoi. */
    public record Erreur(String feuille, int ligne, String message) {
    }

    private record ProprietaireLu(String cle, ProprietaireSaisieDto saisie, Integer idExistant) {
    }

    private record BatimentLu(String cle, String cleProprietaire, BatimentSaisieDto saisie, Integer idExistant) {
    }

    private record LotLu(String cleBatiment, Integer idBatimentExistant, LotSaisieDto saisie, boolean existant) {
    }

    private final ProprietaireRepository proprietaireRepository;
    private final BatimentRepository batimentRepository;
    private final LotRepository lotRepository;
    private final ReferentielService referentielService;
    private final JournalService journalService;
    private final DataFormatter format = new DataFormatter(java.util.Locale.FRANCE);

    public ImportFacade(ProprietaireRepository proprietaireRepository, BatimentRepository batimentRepository,
                        LotRepository lotRepository, ReferentielService referentielService, JournalService journalService) {
        this.proprietaireRepository = proprietaireRepository;
        this.batimentRepository = batimentRepository;
        this.lotRepository = lotRepository;
        this.referentielService = referentielService;
        this.journalService = journalService;
    }

    // ------------------------------------------------------------------ import
    /**
     * Contrôle le classeur et, si {@code simuler} est faux et qu'il ne contient aucune erreur,
     * l'importe. Rend le compte rendu dans les deux cas.
     */
    @Transactional
    public Map<String, Object> importer(InputStream fichier, String nomFichier, boolean simuler, Utilisateur auteur) {
        List<Erreur> erreurs = new ArrayList<>();
        Map<String, ProprietaireLu> proprietaires = new LinkedHashMap<>();
        Map<String, BatimentLu> batiments = new LinkedHashMap<>();
        List<LotLu> lots = new ArrayList<>();

        try (Workbook classeur = WorkbookFactory.create(fichier)) {
            lireProprietaires(feuille(classeur, F_PROPRIETAIRES), proprietaires, erreurs);
            lireBatiments(feuille(classeur, F_BATIMENTS), proprietaires, batiments, erreurs);
            lireLots(feuille(classeur, F_LOTS), batiments, lots, erreurs);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ErreurMetier metier) {
                throw metier;
            }
            throw ErreurMetier.invalide("Ce fichier n'est pas un classeur Excel lisible (.xlsx). Repartez du modèle fourni.");
        }
        if (proprietaires.isEmpty() && batiments.isEmpty() && lots.isEmpty() && erreurs.isEmpty()) {
            throw ErreurMetier.invalide("Le classeur ne contient aucune ligne à importer.");
        }

        long proNouveaux = proprietaires.values().stream().filter(p -> p.idExistant() == null).count();
        long batNouveaux = batiments.values().stream().filter(b -> b.idExistant() == null).count();
        long aLocaliser = batiments.values().stream().filter(b -> b.idExistant() == null && b.saisie().latitude() == null).count();
        long lotsNouveaux = lots.stream().filter(l -> !l.existant()).count();

        boolean importe = false;
        if (!simuler && erreurs.isEmpty()) {
            ecrire(proprietaires, batiments, lots, auteur);
            importe = true;
            Map<String, Object> bilan = new LinkedHashMap<>();
            bilan.put("fichier", nomFichier);
            bilan.put("proprietaires", proNouveaux);
            bilan.put("batiments", batNouveaux);
            bilan.put("lots", lotsNouveaux);
            journalService.consigner(auteur.signature(), "IMPORT", null, "Import du patrimoine depuis Excel", null, bilan);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("simulation", simuler);
        r.put("importe", importe);
        r.put("proprietaires", Map.of("nouveaux", proNouveaux, "existants", proprietaires.size() - proNouveaux));
        r.put("batiments", Map.of("nouveaux", batNouveaux, "existants", batiments.size() - batNouveaux, "aLocaliser", aLocaliser));
        r.put("lots", Map.of("nouveaux", lotsNouveaux, "existants", lots.size() - lotsNouveaux));
        r.put("erreurs", erreurs);
        return r;
    }

    private void ecrire(Map<String, ProprietaireLu> proprietaires, Map<String, BatimentLu> batiments, List<LotLu> lots,
                        Utilisateur auteur) {
        String signature = auteur.signature();
        Map<String, Integer> idsProprietaires = new LinkedHashMap<>();
        for (ProprietaireLu p : proprietaires.values()) {
            idsProprietaires.put(p.cle(), p.idExistant() != null ? p.idExistant()
                    : proprietaireRepository.creer(p.saisie(), "IMPORT", signature));
        }
        Map<String, Integer> idsBatiments = new LinkedHashMap<>();
        for (BatimentLu b : batiments.values()) {
            if (b.idExistant() != null) {
                idsBatiments.put(b.cle(), b.idExistant());
                continue;
            }
            BatimentSaisieDto s = b.saisie();
            Integer idProprietaire = s.idProprietaire() != null ? s.idProprietaire() : idsProprietaires.get(b.cleProprietaire());
            BatimentSaisieDto complet = new BatimentSaisieDto(null, s.nom(), s.type(), s.adresse(), s.idCommune(), s.idQuartier(),
                    s.latitude(), s.longitude(), null, s.niveaux(), s.anneeConstruction(), null, idProprietaire, null, null);
            // § 7.3 : le patrimoine importé est validé ensuite par les gestionnaires, bâtiment par bâtiment.
            idsBatiments.put(b.cle(), batimentRepository.creer(complet, "A_VALIDER", "IMPORT", signature));
        }
        for (LotLu l : lots) {
            if (l.existant()) {
                continue;
            }
            LotSaisieDto s = l.saisie();
            int idBatiment = l.idBatimentExistant() != null ? l.idBatimentExistant() : idsBatiments.get(l.cleBatiment());
            lotRepository.creer(new LotSaisieDto(null, idBatiment, s.numero(), s.etage(), s.type(), s.surface(), s.pieces(),
                    null, null, List.of(), s.loyerReference(), s.charges(), s.statut(), null), signature);
        }
    }

    // ------------------------------------------------------------------ lecture des feuilles
    private void lireProprietaires(Sheet feuille, Map<String, ProprietaireLu> lus, List<Erreur> erreurs) {
        if (feuille == null) {
            return;
        }
        Map<String, Integer> col = colonnes(feuille, C_PROPRIETAIRES, F_PROPRIETAIRES, erreurs);
        for (Row ligne : lignes(feuille, F_PROPRIETAIRES)) {
            int n = ligne.getRowNum() + 1;
            String nom = texte(ligne, col, 0);
            if (nom == null) {
                erreurs.add(new Erreur(F_PROPRIETAIRES, n, "Le nom, ou la raison sociale, est obligatoire."));
                continue;
            }
            String prenoms = texte(ligne, col, 1);
            String cle = ReferentielService.cle(nom + " " + (prenoms == null ? "" : prenoms));
            if (lus.containsKey(cle)) {
                erreurs.add(new Erreur(F_PROPRIETAIRES, n, "Ce propriétaire figure déjà plus haut dans la feuille."));
                continue;
            }
            String type = "PHYSIQUE";
            String typeLu = texte(ligne, col, 2);
            if (typeLu != null) {
                Optional<String> code = referentielService.code("TYPE_PERSONNE", typeLu);
                if (code.isEmpty() && ReferentielService.cle(typeLu).contains("morale")) {
                    code = Optional.of("MORALE");
                } else if (code.isEmpty() && ReferentielService.cle(typeLu).contains("physique")) {
                    code = Optional.of("PHYSIQUE");
                }
                if (code.isEmpty()) {
                    erreurs.add(new Erreur(F_PROPRIETAIRES, n, "Type inconnu : « " + typeLu + " ». Attendu : " + choix("TYPE_PERSONNE") + "."));
                    continue;
                }
                type = code.get();
            }
            String telephone = texte(ligne, col, 3);
            if (telephone != null && !telephone.matches("\\+?[0-9 ]{8,19}")) {
                erreurs.add(new Erreur(F_PROPRIETAIRES, n, "Téléphone invalide : 8 à 19 chiffres, espaces permis, « + » en tête."));
                continue;
            }
            String email = texte(ligne, col, 4);
            if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[a-zA-Z]{2,}")) {
                erreurs.add(new Erreur(F_PROPRIETAIRES, n, "Adresse e-mail invalide."));
                continue;
            }
            lus.put(cle, new ProprietaireLu(cle, new ProprietaireSaisieDto(null, type, nom, prenoms, telephone, null, email,
                    texte(ligne, col, 5), null, null, null, null, null, null), proprietaireRepository.parNom(cle).orElse(null)));
        }
    }

    private void lireBatiments(Sheet feuille, Map<String, ProprietaireLu> proprietaires, Map<String, BatimentLu> lus,
                               List<Erreur> erreurs) {
        if (feuille == null) {
            return;
        }
        Map<String, Integer> col = colonnes(feuille, C_BATIMENTS, F_BATIMENTS, erreurs);
        for (Row ligne : lignes(feuille, F_BATIMENTS)) {
            int n = ligne.getRowNum() + 1;
            int avant = erreurs.size();
            String nom = texte(ligne, col, 0);
            if (nom == null) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Le nom du bâtiment est obligatoire."));
            }
            String typeLu = texte(ligne, col, 1);
            Optional<String> type = typeLu == null ? Optional.empty() : referentielService.code("TYPE_BATIMENT", typeLu);
            if (type.isEmpty()) {
                erreurs.add(new Erreur(F_BATIMENTS, n, (typeLu == null ? "Le type est obligatoire" : "Type inconnu : « " + typeLu + " »")
                        + ". Attendu : " + choix("TYPE_BATIMENT") + "."));
            }
            // Le propriétaire : d'abord une ligne de la feuille Propriétaires, sinon une fiche déjà en base.
            String proLu = texte(ligne, col, 2);
            String clePro = ReferentielService.cle(proLu);
            Integer idPro = null;
            if (proLu == null) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Le propriétaire est obligatoire : un bâtiment ne s'enregistre pas sans propriétaire."));
            } else if (proprietaires.containsKey(clePro)) {
                idPro = proprietaires.get(clePro).idExistant();
            } else {
                idPro = proprietaireRepository.parNom(clePro).orElse(null);
                if (idPro == null) {
                    erreurs.add(new Erreur(F_BATIMENTS, n, "Propriétaire introuvable : « " + proLu
                            + " ». Écrivez son nom exactement comme dans la feuille Propriétaires."));
                }
            }
            String communeLue = texte(ligne, col, 3);
            Optional<Commune> commune = communeLue == null ? Optional.empty() : referentielService.commune(communeLue);
            if (commune.isEmpty()) {
                erreurs.add(new Erreur(F_BATIMENTS, n, communeLue == null ? "La commune est obligatoire." : "Commune inconnue : « " + communeLue + " »."));
            }
            Integer idQuartier = null;
            String quartierLu = texte(ligne, col, 4);
            if (quartierLu != null && commune.isPresent()) {
                Optional<Quartier> quartier = referentielService.quartier(commune.get().id(), quartierLu);
                if (quartier.isEmpty()) {
                    erreurs.add(new Erreur(F_BATIMENTS, n, "Quartier inconnu à " + commune.get().libelle() + " : « " + quartierLu
                            + " ». Laissez la cellule vide, ou faites ajouter le quartier par l'administrateur."));
                } else {
                    idQuartier = quartier.get().id();
                }
            }
            Double latitude = nombre(ligne, col, 6, F_BATIMENTS, "Latitude", erreurs);
            Double longitude = nombre(ligne, col, 7, F_BATIMENTS, "Longitude", erreurs);
            if ((latitude == null) != (longitude == null)) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Latitude et longitude vont ensemble : renseignez les deux, ou aucune."));
            } else if (latitude != null && (latitude < 4.2 || latitude > 10.8 || longitude < -8.7 || longitude > -2.4)) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Position hors de Côte d'Ivoire : latitude et longitude sont peut-être inversées."));
            }
            Integer niveaux = entier(ligne, col, 8, F_BATIMENTS, "Niveaux", erreurs);
            Integer annee = entier(ligne, col, 9, F_BATIMENTS, "Année de construction", erreurs);
            if (niveaux != null && (niveaux < 0 || niveaux > 60)) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Le nombre de niveaux doit être compris entre 0 et 60."));
            }
            if (annee != null && (annee < 1900 || annee > 2100)) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "L'année de construction n'est pas vraisemblable."));
            }
            if (erreurs.size() > avant) {
                continue;
            }
            String cle = ReferentielService.cle(nom);
            if (lus.containsKey(cle)) {
                erreurs.add(new Erreur(F_BATIMENTS, n, "Un bâtiment de ce nom figure déjà plus haut dans la feuille : distinguez-les (« Résidence X - bloc B »)."));
                continue;
            }
            Integer existant = idPro == null ? null : batimentRepository.parNom(cle, commune.get().id(), idPro).orElse(null);
            lus.put(cle, new BatimentLu(cle, clePro, new BatimentSaisieDto(null, nom, type.get(), texte(ligne, col, 5),
                    commune.get().id(), idQuartier, latitude, longitude, null, niveaux, annee, null, idPro, null, null), existant));
        }
    }

    private void lireLots(Sheet feuille, Map<String, BatimentLu> batiments, List<LotLu> lus, List<Erreur> erreurs) {
        if (feuille == null) {
            return;
        }
        Map<String, Integer> col = colonnes(feuille, C_LOTS, F_LOTS, erreurs);
        Map<String, Boolean> vus = new LinkedHashMap<>();
        for (Row ligne : lignes(feuille, F_LOTS)) {
            int n = ligne.getRowNum() + 1;
            int avant = erreurs.size();
            String batLu = texte(ligne, col, 0);
            String cleBat = ReferentielService.cle(batLu);
            Integer idBatiment = null;
            if (batLu == null) {
                erreurs.add(new Erreur(F_LOTS, n, "Le bâtiment est obligatoire."));
            } else if (batiments.containsKey(cleBat)) {
                idBatiment = batiments.get(cleBat).idExistant();
            } else {
                List<Integer> trouves = batimentRepository.parNomSeul(cleBat);
                if (trouves.size() == 1) {
                    idBatiment = trouves.get(0);
                } else {
                    erreurs.add(new Erreur(F_LOTS, n, trouves.isEmpty()
                            ? "Bâtiment introuvable : « " + batLu + " ». Écrivez son nom exactement comme dans la feuille Bâtiments."
                            : "Plusieurs bâtiments s'appellent « " + batLu + " » : ajoutez-le à la feuille Bâtiments pour lever le doute."));
                }
            }
            String numero = texte(ligne, col, 1);
            if (numero == null) {
                erreurs.add(new Erreur(F_LOTS, n, "Le numéro de porte est obligatoire."));
            }
            String typeLu = texte(ligne, col, 3);
            Optional<String> type = typeLu == null ? Optional.empty() : referentielService.code("TYPE_LOT", typeLu);
            if (type.isEmpty()) {
                erreurs.add(new Erreur(F_LOTS, n, (typeLu == null ? "Le type est obligatoire" : "Type inconnu : « " + typeLu + " »")
                        + ". Attendu : " + choix("TYPE_LOT") + "."));
            }
            String statut = "VACANT";
            String statutLu = texte(ligne, col, 8);
            if (statutLu != null) {
                Optional<String> code = referentielService.code("STATUT_LOT", statutLu);
                if (code.isEmpty()) {
                    erreurs.add(new Erreur(F_LOTS, n, "Statut inconnu : « " + statutLu + " ». Attendu : " + choix("STATUT_LOT") + "."));
                } else {
                    statut = code.get();
                }
            }
            Integer etage = entier(ligne, col, 2, F_LOTS, "Étage", erreurs);
            Double surface = nombre(ligne, col, 4, F_LOTS, "Surface", erreurs);
            Integer pieces = entier(ligne, col, 5, F_LOTS, "Pièces", erreurs);
            Integer loyer = entier(ligne, col, 6, F_LOTS, "Loyer", erreurs);
            Integer charges = entier(ligne, col, 7, F_LOTS, "Charges", erreurs);
            if (etage != null && (etage < -5 || etage > 60)) {
                erreurs.add(new Erreur(F_LOTS, n, "L'étage doit être compris entre -5 et 60 (0 pour le rez-de-chaussée)."));
            }
            if (surface != null && surface <= 0) {
                erreurs.add(new Erreur(F_LOTS, n, "La surface doit être supérieure à zéro."));
            }
            for (Integer v : new Integer[]{pieces, loyer, charges}) {
                if (v != null && v < 0) {
                    erreurs.add(new Erreur(F_LOTS, n, "Aucune valeur ne peut être négative."));
                    break;
                }
            }
            if (erreurs.size() > avant) {
                continue;
            }
            // « Le numéro de porte est unique dans un bâtiment » (§ 4.2) : dans le fichier comme en base.
            if (vus.put(cleBat + "|" + numero.toLowerCase(), true) != null) {
                erreurs.add(new Erreur(F_LOTS, n, "Le numéro de porte « " + numero + " » figure deux fois pour ce bâtiment."));
                continue;
            }
            boolean existant = idBatiment != null && lotRepository.numeroPris(idBatiment, numero);
            lus.add(new LotLu(cleBat, idBatiment, new LotSaisieDto(null, idBatiment, numero, etage == null ? 0 : etage, type.get(),
                    surface, pieces, null, null, List.of(), loyer, charges, statut, null), existant));
        }
    }

    // ------------------------------------------------------------------ outils de lecture
    private Sheet feuille(Workbook classeur, String nom) {
        String cle = ReferentielService.cle(nom);
        for (Sheet f : classeur) {
            if (ReferentielService.cle(f.getSheetName()).equals(cle)) {
                return f;
            }
        }
        return null;
    }

    /** La position de chaque colonne attendue, retrouvée par son titre (sans l'astérisque ni l'unité). */
    private Map<String, Integer> colonnes(Sheet feuille, String[] attendues, String nomFeuille, List<Erreur> erreurs) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        Row entete = feuille.getRow(0);
        for (String attendue : attendues) {
            String cle = cleTitre(attendue);
            Integer trouvee = null;
            if (entete != null) {
                for (Cell c : entete) {
                    if (cleTitre(format.formatCellValue(c)).equals(cle)) {
                        trouvee = c.getColumnIndex();
                        break;
                    }
                }
            }
            if (trouvee == null && attendue.endsWith("*")) {
                erreurs.add(new Erreur(nomFeuille, 1, "Colonne obligatoire absente : « " + attendue.replace(" *", "") + " ». Repartez du modèle fourni."));
            }
            positions.put(attendue, trouvee);
        }
        return positions;
    }

    private static String cleTitre(String titre) {
        return ReferentielService.cle(titre == null ? "" : titre.replaceAll("\\(.*?\\)", ""));
    }

    /** Les lignes de données non vides, sous l'en-tête. */
    private List<Row> lignes(Sheet feuille, String nomFeuille) {
        List<Row> r = new ArrayList<>();
        for (Row ligne : feuille) {
            if (ligne.getRowNum() == 0) {
                continue;
            }
            boolean vide = true;
            for (Cell c : ligne) {
                if (!format.formatCellValue(c).isBlank()) {
                    vide = false;
                    break;
                }
            }
            if (!vide) {
                r.add(ligne);
            }
        }
        if (r.size() > LIGNES_MAX) {
            throw ErreurMetier.invalide("La feuille « " + nomFeuille + " » dépasse " + LIGNES_MAX + " lignes : scindez le fichier.");
        }
        return r;
    }

    private Cell cellule(Row ligne, Map<String, Integer> col, int rang) {
        Integer position = new ArrayList<>(col.values()).get(rang);
        return position == null ? null : ligne.getCell(position);
    }

    private String texte(Row ligne, Map<String, Integer> col, int rang) {
        Cell c = cellule(ligne, col, rang);
        if (c == null) {
            return null;
        }
        String t = format.formatCellValue(c).trim();
        return t.isEmpty() ? null : t;
    }

    private Double nombre(Row ligne, Map<String, Integer> col, int rang, String feuille, String quoi, List<Erreur> erreurs) {
        Cell c = cellule(ligne, col, rang);
        if (c == null) {
            return null;
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return c.getNumericCellValue();
        }
        String t = format.formatCellValue(c).replaceAll("[\\s\\u00a0\\u202f]", "").replace(',', '.').replaceAll("(?i)(fcfa|m²|m2)$", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            erreurs.add(new Erreur(feuille, ligne.getRowNum() + 1, quoi + " : « " + format.formatCellValue(c) + " » n'est pas un nombre."));
            return null;
        }
    }

    private Integer entier(Row ligne, Map<String, Integer> col, int rang, String feuille, String quoi, List<Erreur> erreurs) {
        Double d = nombre(ligne, col, rang, feuille, quoi, erreurs);
        if (d == null) {
            return null;
        }
        if (d != Math.rint(d) || Math.abs(d) > 2_000_000_000d) {
            erreurs.add(new Erreur(feuille, ligne.getRowNum() + 1, quoi + " : un nombre entier est attendu."));
            return null;
        }
        return (int) Math.rint(d);
    }

    private String choix(String domaine) {
        return String.join(", ", referentielService.valeurs(domaine).stream().map(Valeur::libelle).toList());
    }

    // ------------------------------------------------------------------ modèle
    /** Le classeur modèle : les trois feuilles avec leurs titres, et une feuille d'explications. */
    public byte[] modele() {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            CellStyle titre = classeur.createCellStyle();
            Font gras = classeur.createFont();
            gras.setBold(true);
            gras.setColor(IndexedColors.WHITE.getIndex());
            titre.setFont(gras);
            titre.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            titre.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            poserFeuille(classeur, F_PROPRIETAIRES, C_PROPRIETAIRES, titre);
            poserFeuille(classeur, F_BATIMENTS, C_BATIMENTS, titre);
            poserFeuille(classeur, F_LOTS, C_LOTS, titre);

            Sheet aide = classeur.createSheet("Lisez-moi");
            String[] lignes = {
                    "FACT Immobilier - modèle d'import du patrimoine",
                    "",
                    "Remplissez les trois feuilles, une ligne par fiche, sans toucher aux titres. Les colonnes marquées * sont obligatoires.",
                    "Le fichier est contrôlé en entier avant tout import : s'il reste une erreur, rien n'est enregistré.",
                    "Les fiches déjà présentes dans l'application sont reconnues et laissées telles quelles.",
                    "",
                    "Feuille Bâtiments - « Propriétaire » : son nom exactement comme dans la feuille Propriétaires (nom puis prénoms).",
                    "Feuille Bâtiments - Latitude et longitude en degrés décimaux (5,3516 et -3,9869). Vides : le bâtiment entre dans la liste « À localiser ».",
                    "Feuille Lots - « Bâtiment » : son nom exactement comme dans la feuille Bâtiments. Étage : 0 pour le rez-de-chaussée.",
                    "Montants en francs CFA, sans décimale.",
                    "",
                    "Types de bâtiment : " + choix("TYPE_BATIMENT"),
                    "Types de lot : " + choix("TYPE_LOT"),
                    "Statuts de lot : " + choix("STATUT_LOT") + " (vide : Vacant)",
                    "Types de propriétaire : " + choix("TYPE_PERSONNE") + " (vide : Personne physique)",
                    "Communes : " + String.join(", ", referentielService.communes()),
            };
            for (int i = 0; i < lignes.length; i++) {
                aide.createRow(i).createCell(0).setCellValue(lignes[i]);
            }
            aide.getRow(0).getCell(0).setCellStyle(titre);
            aide.setColumnWidth(0, 120 * 256);

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Modèle d'import impossible à produire", e);
        }
    }

    private static void poserFeuille(Workbook classeur, String nom, String[] colonnes, CellStyle titre) {
        Sheet f = classeur.createSheet(nom);
        Row entete = f.createRow(0);
        for (int i = 0; i < colonnes.length; i++) {
            Cell c = entete.createCell(i);
            c.setCellValue(colonnes[i]);
            c.setCellStyle(titre);
            f.setColumnWidth(i, Math.max(16, colonnes[i].length() + 6) * 256);
        }
        f.createFreezePane(0, 1);
    }
}
