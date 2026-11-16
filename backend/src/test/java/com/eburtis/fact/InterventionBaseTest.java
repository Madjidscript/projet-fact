package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.InterventionFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.EtapeInterventionDto;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.PrestataireSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le cycle d'une intervention (§ 4.5) joué contre la base réelle, dans une transaction annulée.
 * Ne tourne que sur demande : {@code set FACT_TESTS_BASE=1} puis {@code gradlew test}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class InterventionBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur AGENT = new Utilisateur(-4, "test.agent", "Test Agent", null, null, Profil.AGENT, Set.of(), false, 0);
    private static final Utilisateur TRAVAUX = new Utilisateur(-6, "test.travaux", "Test Travaux", null, null, Profil.TRAVAUX, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private InterventionFacade interventions;
    @Autowired private JdbcTemplate jdbc;

    private int idBatiment;
    private int idLot;
    private int idPrestataire;

    @BeforeEach
    void preparer() {
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        int pro = proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", "Zztest Bailleur", null, null, null, null, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
        idBatiment = batiments.enregistrer(new BatimentSaisieDto(null, "Zztest Immeuble", "IMMEUBLE", null, cocody, null, 5.35, -3.98,
                null, 3, null, null, pro, null, null), ADMIN).get("id").asInt();
        idLot = batiments.enregistrerLot(new LotSaisieDto(null, idBatiment, "A1", 0, "F3", 80.0, 3, 2, 1, List.of(), 300000, 0,
                "VACANT", null), ADMIN).get("id").asInt();
        idPrestataire = interventions.enregistrerPrestataire(new PrestataireSaisieDto(null, "Zztest Plomberie Express", "PLOMBERIE",
                "07 11 22 33 44", null, null, true), TRAVAUX).get("id").asInt();
    }

    private int signaler(String urgence) {
        return interventions.signaler(new InterventionSaisieDto(idBatiment, idLot, "PLOMBERIE", urgence, "Fuite sous l'évier",
                "L'eau coule sous l'évier de la cuisine."), AGENT).get("id").asInt();
    }

    private static EtapeInterventionDto etape() {
        return new EtapeInterventionDto(null, null, null, null, null, null, null, null);
    }

    private String statut(int id) {
        return ((JsonNode) interventions.fiche(id, ADMIN).get("intervention")).get("statut").asText();
    }

    @Test
    @DisplayName("Cycle complet : signalée, affectée, en cours avec devis, validée, terminée, clôturée")
    void cycleComplet() {
        int id = signaler("HAUTE");
        assertEquals("SIGNALEE", statut(id));
        JsonNode b = (JsonNode) batiments.fiche(idBatiment, ADMIN).get("batiment");
        assertEquals(1, b.get("interventionsOuvertes").asInt());
        assertEquals(1, b.get("interventionsUrgentes").asInt());

        interventions.etape(id, "affecter", new EtapeInterventionDto(idPrestataire, java.time.LocalDate.now().plusDays(2), null, null, null,
                null, null, null), TRAVAUX);
        assertEquals("AFFECTEE", statut(id));
        interventions.etape(id, "demarrer", new EtapeInterventionDto(null, null, "Siphon fissuré, à remplacer", 220000, null, null, null, null), TRAVAUX);
        assertEquals("EN_COURS", statut(id));

        // Le devis dépasse le seuil : sans l'accord du propriétaire, la validation est refusée.
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "valider", etape(), ADMIN));
        interventions.etape(id, "valider", new EtapeInterventionDto(null, null, null, null, true, null, null, "Accord donné par téléphone"), ADMIN);
        assertEquals("VALIDEE", statut(id));

        interventions.etape(id, "terminer", new EtapeInterventionDto(null, null, null, null, null, 210000, null, "Siphon remplacé"), TRAVAUX);
        assertEquals("TERMINEE", statut(id));
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "annuler", new EtapeInterventionDto(null, null, null, null, null,
                null, null, "trop tard"), ADMIN), "une intervention terminée ne s'annule plus");

        Map<String, Object> f = interventions.etape(id, "cloturer", new EtapeInterventionDto(null, null, null, null, null, null,
                "PROPRIETAIRE", "Contrôlé sur place"), ADMIN);
        JsonNode i = (JsonNode) f.get("intervention");
        assertEquals("CLOTUREE", i.get("statut").asText());
        assertEquals(210000, i.get("coutReel").asInt());
        assertFalse(i.get("ouverte").asBoolean());
        assertTrue(i.hasNonNull("delaiJours"));
        assertTrue(((JsonNode) f.get("historique")).size() >= 6, "chaque étape est tracée");
        assertEquals(0, ((JsonNode) batiments.fiche(idBatiment, ADMIN).get("batiment")).get("interventionsOuvertes").asInt());
    }

    @Test
    @DisplayName("Sans devis, le responsable travaux termine directement ; chaque étape revient au bon profil")
    void droits() {
        int id = signaler("NORMALE");
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "affecter",
                new EtapeInterventionDto(idPrestataire, null, null, null, null, null, null, null), AGENT), "l'agent n'affecte pas");
        assertThrows(ErreurMetier.class, () -> interventions.fiche(id, GESTIONNAIRE), "hors de son portefeuille");
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "terminer", etape(), TRAVAUX), "il faut d'abord l'affecter");
        interventions.etape(id, "affecter", new EtapeInterventionDto(idPrestataire, null, null, null, null, null, null, null), TRAVAUX);
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "demarrer", etape(), TRAVAUX), "le constat est obligatoire");
        interventions.etape(id, "demarrer", new EtapeInterventionDto(null, null, "Joint changé sur place", null, null, null, null, null), TRAVAUX);
        List<String> actions = (List<String>) interventions.fiche(id, TRAVAUX).get("actions");
        assertTrue(actions.contains("terminer") && !actions.contains("valider"), "sans devis, pas de validation : " + actions);
        interventions.etape(id, "terminer", new EtapeInterventionDto(null, null, null, null, null, 5000, null, null), TRAVAUX);
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "cloturer",
                new EtapeInterventionDto(null, null, null, null, null, null, "LOCATAIRE", null), TRAVAUX), "la clôture revient au gestionnaire");
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "cloturer", etape(), ADMIN), "l'imputation est obligatoire");
    }

    @Test
    @DisplayName("Annulation avec motif ; photos : une image est rangée et relue, un autre fichier est refusé")
    void annulationEtPhotos() throws Exception {
        int id = signaler("CRITIQUE");
        BufferedImage img = new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x1F, 0x3A, 0x5F));
        g.fillRect(0, 0, 3000, 2000);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        JsonNode photos = interventions.ajouterPhotos(id, "AVANT",
                List.of(new MockMultipartFile("fichiers", "fuite.png", "image/png", png.toByteArray())), AGENT);
        assertEquals(1, photos.size());
        assertEquals(2048, photos.get(0).get("largeur").asInt(), "réduite à 2 048 pixels");
        byte[] relue = interventions.photo(photos.get(0).get("id").asInt(), ADMIN);
        assertTrue(relue.length > 100 && (relue[0] & 0xFF) == 0xFF && (relue[1] & 0xFF) == 0xD8, "réécrite en JPEG");
        assertThrows(ErreurMetier.class, () -> interventions.ajouterPhotos(id, "AVANT",
                List.of(new MockMultipartFile("fichiers", "devis.pdf", "image/png", "%PDF-1.4 pas une image".getBytes())), AGENT));
        assertThrows(ErreurMetier.class, () -> interventions.etape(id, "annuler", etape(), TRAVAUX), "le motif est obligatoire");
        interventions.etape(id, "annuler", new EtapeInterventionDto(null, null, null, null, null, null, null, "Signalement en double"), TRAVAUX);
        assertEquals("ANNULEE", statut(id));
        assertThrows(ErreurMetier.class, () -> interventions.ajouterPhotos(id, "APRES",
                List.of(new MockMultipartFile("fichiers", "x.png", "image/png", png.toByteArray())), AGENT), "close : plus de photos");
        JsonNode s = (JsonNode) interventions.synthese(ADMIN).get("synthese");
        assertTrue(s.get("parStatut").get("ANNULEE").asInt() >= 1);
    }
}
