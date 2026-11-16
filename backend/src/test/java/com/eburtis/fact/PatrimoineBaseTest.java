package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.ImportFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.fasterxml.jackson.databind.JsonNode;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les règles du patrimoine (§ 4.2) et l'import Excel (§ 7.3), joués contre la base réelle.
 * Chaque test tourne dans une transaction annulée à la fin : rien ne reste en base.
 * <p>
 * Ces tests demandent une base joignable ; ils ne tournent que sur demande :
 * {@code set FACT_TESTS_BASE=1} puis {@code gradlew test}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class PatrimoineBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);
    private static final Utilisateur PROPRIETAIRE = new Utilisateur(-3, "test.pro", "Test Propriétaire", null, null, Profil.PROPRIETAIRE, Set.of(), false, 0);
    private static final Utilisateur AGENT = new Utilisateur(-4, "test.agent", "Test Agent", null, null, Profil.AGENT, Set.of(), false, 0);

    @Autowired
    private ProprietaireFacade proprietaires;
    @Autowired
    private BatimentFacade batiments;
    @Autowired
    private ImportFacade imports;
    @Autowired
    private JdbcTemplate jdbc;

    private int cocody() {
        return jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
    }

    private int proprietaire(String nom) {
        return proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", nom, "Essai", "07 00 00 00 01", null,
                "essai@exemple.test", null, null, null, null, null, null, null), ADMIN).get("id").asInt();
    }

    private BatimentSaisieDto batiment(String nom, Integer idProprietaire, Double lat, Double lon) {
        return new BatimentSaisieDto(null, nom, "IMMEUBLE", "Rue d'essai", cocody(), null, lat, lon, 8.0, 4, 2015, null,
                idProprietaire, null, null);
    }

    private LotSaisieDto lot(int idBatiment, String numero, Integer loyer) {
        return new LotSaisieDto(null, idBatiment, numero, 1, "F3", 85.0, 3, 2, 1, List.of("PARKING", "GARDIENNAGE"), loyer, 20000,
                "VACANT", null);
    }

    @Test
    @DisplayName("Les trois annonces du site actuel sont reprises, à valider et à localiser")
    void repriseDuSite() {
        JsonNode liste = batiments.lister(ADMIN, false);
        long repris = 0;
        for (JsonNode b : liste) {
            if ("SITE".equals(b.get("origine").asText())) {
                repris++;
                assertEquals(1, b.get("lots").asInt());
                // Repris sans position : un bien repris n'est validé qu'une fois placé sur la carte.
                assertTrue(b.get("validation").asText().equals("A_VALIDER") || !b.get("latitude").isNull());
            }
        }
        assertEquals(3, repris);
    }

    @Test
    @DisplayName("Un bâtiment ne s'enregistre ni sans position ni sans propriétaire")
    void positionEtProprietaireObligatoires() {
        int pro = proprietaire("Zzessai Position");
        assertThrows(ErreurMetier.class, () -> batiments.enregistrer(batiment("Sans position", pro, null, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> batiments.enregistrer(batiment("Sans propriétaire", null, 5.35, -3.98), ADMIN));
        assertThrows(ErreurMetier.class, () -> batiments.enregistrer(batiment("Inversé", pro, -3.98, 5.35), ADMIN),
                "latitude et longitude inversées");
        JsonNode cree = batiments.enregistrer(batiment("Immeuble d'essai", pro, 5.3516, -3.9869), ADMIN);
        assertEquals(5.3516, cree.get("latitude").asDouble(), 1e-6);
        assertEquals(-3.9869, cree.get("longitude").asDouble(), 1e-6);
        assertEquals("VALIDE", cree.get("validation").asText());
        assertTrue(cree.get("reference").asText().startsWith("BAT-"));
    }

    @Test
    @DisplayName("Le numéro de porte est unique dans un bâtiment ; aucune valeur négative")
    void reglesDesLots() {
        int pro = proprietaire("Zzessai Lots");
        int bat = batiments.enregistrer(batiment("Immeuble aux lots", pro, 5.3516, -3.9869), ADMIN).get("id").asInt();
        JsonNode a11 = batiments.enregistrerLot(lot(bat, "A11", 375000), ADMIN);
        assertEquals("Appartement F3", a11.get("libelleType").asText());
        assertEquals(2, a11.get("equipements").size());
        assertThrows(ErreurMetier.class, () -> batiments.enregistrerLot(lot(bat, "a11", 300000), ADMIN), "doublon, sans égard à la casse");
        assertThrows(ErreurMetier.class, () -> batiments.enregistrerLot(lot(bat, "A12", -2), ADMIN), "loyer négatif");
        batiments.enregistrerLot(lot(bat, "A12", 250000), ADMIN);

        Map<String, Object> fiche = batiments.fiche(bat, ADMIN);
        JsonNode b = (JsonNode) fiche.get("batiment");
        assertEquals(2, b.get("lots").asInt());
        assertEquals(2, b.get("lotsVacants").asInt());
        assertEquals(625000, b.get("loyersReference").asInt());

        // Un lot ne passe pas « Occupé » à la main : c'est la signature du bail qui l'y met (§ 4.2).
        int idLot = a11.get("id").asInt();
        assertThrows(ErreurMetier.class, () -> batiments.enregistrerLot(new LotSaisieDto(idLot, bat, "A11", 1, "F3", 85.0, 3, 2, 1,
                List.of(), 375000, 20000, "OCCUPE", null), ADMIN), "pas d'occupation sans bail");
        JsonNode reserve = batiments.enregistrerLot(new LotSaisieDto(idLot, bat, "A11", 1, "F3", 85.0, 3, 2, 1, List.of(), 375000,
                20000, "RESERVE", null), ADMIN);
        assertEquals("Réservé", reserve.get("libelleStatut").asText());
        int idA12 = ((JsonNode) batiments.fiche(bat, ADMIN).get("lots")).get(1).get("id").asInt();
        batiments.archiverLot(idA12, true, ADMIN);
        batiments.enregistrerLot(lot(bat, "A12", 260000), ADMIN);

        assertTrue(batiments.historique(bat, ADMIN).size() >= 6, "bâtiment et lots sont tracés");
    }

    @Test
    @DisplayName("Un gestionnaire et un propriétaire ne voient que leur périmètre")
    void perimetre() {
        int pro = proprietaire("Zzessai Périmètre");
        int bat = batiments.enregistrer(batiment("Immeuble hors portefeuille", pro, 5.3516, -3.9869), ADMIN).get("id").asInt();
        assertEquals(0, batiments.lister(GESTIONNAIRE, false).size());
        assertEquals(0, batiments.lister(PROPRIETAIRE, false).size());
        assertThrows(ErreurMetier.class, () -> batiments.fiche(bat, GESTIONNAIRE));
        assertThrows(ErreurMetier.class, () -> batiments.fiche(bat, PROPRIETAIRE));
        assertThrows(ErreurMetier.class, () -> batiments.enregistrerLot(lot(bat, "B1", 100000), GESTIONNAIRE));
        assertThrows(ErreurMetier.class, () -> proprietaires.fiche(pro, PROPRIETAIRE));
        assertEquals(0, proprietaires.lister(PROPRIETAIRE, false).size());
        assertThrows(ErreurMetier.class, () -> batiments.archiver(bat, true, GESTIONNAIRE), "archiver revient à la Direction");
        assertThrows(ErreurMetier.class, () -> proprietaires.archiver(pro, true, ADMIN), "il possède encore un bâtiment actif");
    }

    @Test
    @DisplayName("Une fiche créée par un agent de terrain attend la validation du gestionnaire")
    void ficheDeTerrain() {
        int pro = proprietaire("Zzessai Terrain");
        JsonNode cree = batiments.enregistrer(batiment("Immeuble recensé", pro, 5.3516, -3.9869), AGENT);
        assertEquals("A_VALIDER", cree.get("validation").asText());
        assertEquals("TERRAIN", cree.get("origine").asText());
        int id = cree.get("id").asInt();
        assertThrows(ErreurMetier.class, () -> batiments.valider(id, AGENT));
        assertEquals("VALIDE", batiments.valider(id, ADMIN).get("validation").asText());
    }

    @Test
    @DisplayName("Import Excel : contrôle, import, puis reconnaissance des doublons")
    void importExcel() throws Exception {
        byte[] fichier = remplir(new String[][]{
                {"Zzimport Koné", "Awa", "Personne physique", "07 00 00 00 02", "awa@exemple.test", "Cocody"},
                {"Zzimport SCI Lagune", null, "Personne morale", null, null, null},
        }, new Object[][]{
                {"Zzimport Résidence A", "Immeuble", "Zzimport Koné Awa", "Cocody", "Angré", "Rue A", 5.3901, -3.9785, 4, 2012},
                {"Zzimport Villa B", "Villa", "zzimport sci lagune", "cocody", null, null, null, null, null, null},
                {"Zzimport Immeuble C", "immeuble", "Zzimport Koné Awa", "Plateau", null, null, 5.3247, -4.0219, 6, null},
        }, new Object[][]{
                {"Zzimport Résidence A", "A01", 0, "Magasin", 45, null, 300000, null, "Occupé"},
                {"Zzimport Résidence A", "A11", 1, "F3", 85, 3, 375000, 20000, null},
                {"Zzimport Villa B", "Villa", 0, "Villa", 320, 6, "2 500 000", null, "À louer"},
                {"Zzimport Immeuble C", "C21", 2, "Appartement F2", 60, 2, 250000, null, "vacant"},
        });

        Map<String, Object> simulation = imports.importer(new ByteArrayInputStream(fichier), "essai.xlsx", true, ADMIN);
        assertEquals(List.of(), simulation.get("erreurs"));
        assertEquals(false, simulation.get("importe"));
        assertEquals(Map.of("nouveaux", 2L, "existants", 0L), simulation.get("proprietaires"));
        assertEquals(Map.of("nouveaux", 3L, "existants", 0L, "aLocaliser", 1L), simulation.get("batiments"));
        assertEquals(Map.of("nouveaux", 4L, "existants", 0L), simulation.get("lots"));

        Map<String, Object> reel = imports.importer(new ByteArrayInputStream(fichier), "essai.xlsx", false, ADMIN);
        assertEquals(true, reel.get("importe"));
        Integer villa = jdbc.queryForObject("SELECT id FROM pat.batiment WHERE nom = 'Zzimport Villa B'", Integer.class);
        JsonNode b = (JsonNode) batiments.fiche(villa, ADMIN).get("batiment");
        assertEquals("A_VALIDER", b.get("validation").asText());
        assertEquals("IMPORT", b.get("origine").asText());
        assertTrue(b.get("latitude").isNull(), "sans coordonnées : à localiser");
        assertEquals(2500000, b.get("loyersReference").asInt());
        assertThrows(ErreurMetier.class, () -> batiments.valider(villa, ADMIN), "pas de validation avant localisation");

        Map<String, Object> rejoue = imports.importer(new ByteArrayInputStream(fichier), "essai.xlsx", false, ADMIN);
        assertEquals(Map.of("nouveaux", 0L, "existants", 2L), rejoue.get("proprietaires"));
        assertEquals(Map.of("nouveaux", 0L, "existants", 3L, "aLocaliser", 0L), rejoue.get("batiments"));
        assertEquals(Map.of("nouveaux", 0L, "existants", 4L), rejoue.get("lots"));
    }

    @Test
    @DisplayName("Import Excel : une seule erreur bloque tout le fichier")
    void importAvecErreurs() throws Exception {
        byte[] fichier = remplir(new String[][]{
                {"Zzerreur Diallo", "Moussa", null, "abc", null, null},
        }, new Object[][]{
                {"Zzerreur Immeuble", "Château", "Inconnu au bataillon", "Atlantide", null, null, 5.35, null, null, null},
        }, new Object[][]{
                {"Zzerreur Nulle part", "X1", 0, "F3", -4, null, null, null, null},
        });
        Map<String, Object> r = imports.importer(new ByteArrayInputStream(fichier), "erreurs.xlsx", false, ADMIN);
        assertEquals(false, r.get("importe"));
        List<?> erreurs = (List<?>) r.get("erreurs");
        assertTrue(erreurs.size() >= 6, erreurs.toString());
        assertFalse(jdbc.queryForObject("SELECT count(*) FROM pat.proprietaire WHERE nom LIKE 'Zzerreur%'", Integer.class) > 0);
        assertThrows(ErreurMetier.class, () -> imports.importer(new ByteArrayInputStream("pas un classeur".getBytes()), "x.xlsx", true, ADMIN));
    }

    /** Remplit le modèle fourni par l'application, comme le ferait l'agence. */
    private byte[] remplir(Object[][] pros, Object[][] bats, Object[][] lots) throws Exception {
        try (Workbook classeur = WorkbookFactory.create(new ByteArrayInputStream(imports.modele()));
             ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            ecrire(classeur.getSheet("Propriétaires"), pros);
            ecrire(classeur.getSheet("Bâtiments"), bats);
            ecrire(classeur.getSheet("Lots"), lots);
            classeur.write(sortie);
            return sortie.toByteArray();
        }
    }

    private static void ecrire(Sheet feuille, Object[][] lignes) {
        for (int i = 0; i < lignes.length; i++) {
            Row r = feuille.createRow(i + 1);
            for (int j = 0; j < lignes[i].length; j++) {
                Object v = lignes[i][j];
                if (v instanceof Number n) {
                    r.createCell(j).setCellValue(n.doubleValue());
                } else if (v != null) {
                    r.createCell(j).setCellValue(v.toString());
                }
            }
        }
    }
}
