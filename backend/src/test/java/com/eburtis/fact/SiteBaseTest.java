package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.AnnonceFacade;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.DemandeFacade;
import com.eburtis.fact.facade.LocationFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.facade.SiteFacade;
import com.eburtis.fact.presentation.dto.AbonnementDto;
import com.eburtis.fact.presentation.dto.AnnonceSaisieDto;
import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.DemandeSiteDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.repository.SiteRepository;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * Étapes 14 et 15 contre la base réelle, dans une transaction annulée : publication d'un lot,
 * retrait automatique à la signature du bail, demandes du site et alertes de recherche.
 * Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class SiteBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);
    private static final Utilisateur TRAVAUX = new Utilisateur(-6, "test.travaux", "Test Travaux", null, null, Profil.TRAVAUX, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private AnnonceFacade annonces;
    @Autowired private LocationFacade location;
    @Autowired private SiteFacade site;
    @Autowired private DemandeFacade demandes;
    @Autowired private JdbcTemplate jdbc;

    private int idLot;
    private int idCommune;

    @BeforeEach
    void preparer() {
        idCommune = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        int pro = proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", "Zztest Bailleur", null, null, null, null, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
        int bat = batiments.enregistrer(new BatimentSaisieDto(null, "Zztest Résidence du site", "IMMEUBLE", null, idCommune, null, 5.36, -3.97,
                null, 3, null, null, pro, null, null), ADMIN).get("id").asInt();
        idLot = batiments.enregistrerLot(new LotSaisieDto(null, bat, "A1", 1, "F3", 82.0, 3, 2, 1, List.of("PARKING"), 310000, 0,
                "VACANT", "Lot de test"), ADMIN).get("id").asInt();
    }

    private static List<MultipartFile> photos(int n) throws Exception {
        List<MultipartFile> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(40 * i, 90, 160));
            g.fillRect(0, 0, 800, 600);
            g.dispose();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", o);
            l.add(new MockMultipartFile("fichiers", "photo" + i + ".jpg", "image/jpeg", o.toByteArray()));
        }
        return l;
    }

    @SuppressWarnings("unchecked")
    private int publier() throws Exception {
        Map<String, Object> f = annonces.enregistrer(new AnnonceSaisieDto(null, idLot, "LOCATION", "Zztest Appartement F3 lumineux",
                "Séjour, deux chambres, parking.", 310000, 15000, 2, 2, false), ADMIN);
        int id = ((JsonNode) f.get("annonce")).get("id").asInt();
        ErreurMetier refus = assertThrows(ErreurMetier.class, () -> annonces.soumettre(id, ADMIN));
        assertTrue(refus.getMessage().contains("photo"), refus.getMessage());
        annonces.ajouterPhotos(id, photos(3), ADMIN);
        Map<String, Object> r = annonces.soumettre(id, ADMIN);
        assertEquals("PUBLIEE", ((JsonNode) r.get("annonce")).get("statut").asText(), "qui valide met en ligne directement");
        return id;
    }

    private String slug(int id) {
        return jdbc.queryForObject("SELECT slug FROM web.annonce WHERE id = ?", String.class, id);
    }

    private static SiteRepository.Criteres criteres(Integer commune) {
        return new SiteRepository.Criteres("LOCATION", List.of("Appartement"), commune, null, null, 400000L, 3, 80, List.of("PARKING"),
                null, null, null, null, null);
    }

    @Test
    @DisplayName("Un lot publié apparaît sur le site, puis disparaît à la signature du bail")
    void publicationPuisBail() throws Exception {
        int id = publier();
        assertEquals("A_LOUER", jdbc.queryForObject("SELECT statut FROM pat.lot WHERE id = ?", String.class, idLot));
        JsonNode trouves = site.rechercher(criteres(idCommune));
        assertTrue(trouves.findValuesAsText("slug").contains(slug(id)), "le bien est trouvé par la recherche");
        assertEquals(3, ((JsonNode) site.fiche(slug(id)).get("bien")).get("galerie").size());

        OccupantSaisieDto occ = new OccupantSaisieDto(null, "PHYSIQUE", "Zztest Locataire", "Test", "07 01 02 03 04", null, null, "CNI", "C1",
                null, null, null, null, null);
        location.creerBail(new BailSaisieDto(idLot, null, occ, LocalDate.now(), 12, 310000, 15000, 620000, 2, 5, false, "ESPECES", null), ADMIN);
        assertEquals("RETIREE", jdbc.queryForObject("SELECT statut FROM web.annonce WHERE id = ?", String.class, id));
        assertFalse(site.rechercher(criteres(idCommune)).findValuesAsText("slug").contains(slug(id)));
        assertThrows(ErreurMetier.class, () -> site.fiche(slug(id)), "la fiche n'est plus servie");
    }

    @Test
    @DisplayName("Une demande de visite se contrôle, se range et prévient l'agence ; le champ piège arrête les robots")
    void demandeDeVisite() throws Exception {
        int id = publier();
        LocalDate jour = LocalDate.now().plusDays(1);
        while (jour.getDayOfWeek() == DayOfWeek.SATURDAY || jour.getDayOfWeek() == DayOfWeek.SUNDAY) {
            jour = jour.plusDays(1);
        }
        LocalDate dimanche = LocalDate.now().plusDays(1);
        while (dimanche.getDayOfWeek() != DayOfWeek.SUNDAY) {
            dimanche = dimanche.plusDays(1);
        }
        LocalDate d = dimanche;
        assertThrows(ErreurMetier.class, () -> site.demander(new DemandeSiteDto("VISITE", slug(id), "Zztest Visiteur", "0712345678", null,
                d, "Matin", null, null, null, null, true, null), List.of(), "test-1"));
        assertThrows(ErreurMetier.class, () -> site.demander(new DemandeSiteDto("CONTACT", null, "Zztest", "12", null, null, null, null, null,
                null, null, true, null), List.of(), "test-1"), "téléphone invalide");
        int avant = jdbc.queryForObject("SELECT count(*) FROM web.demande", Integer.class);
        site.demander(new DemandeSiteDto("CONTACT", null, "Robot", "0712345678", null, null, null, "spam", null, null, null, true,
                "http://spam"), List.of(), "test-1");
        assertEquals(avant, jdbc.queryForObject("SELECT count(*) FROM web.demande", Integer.class), "le robot n'enregistre rien");

        Map<String, Object> r = site.demander(new DemandeSiteDto("VISITE", slug(id), "Zztest Visiteur", "07 12 34 56 78", "v@exemple.ci",
                jour, "Matin (8h - 12h)", "Disponible le matin", null, null, null, true, null), List.of(), "test-2");
        String ref = (String) r.get("reference");
        assertTrue(ref.startsWith("DEM-"));
        int idDemande = jdbc.queryForObject("SELECT id FROM web.demande WHERE reference = ?", Integer.class, ref);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM web.message WHERE id_demande = ? AND motif = 'ACCUSE' AND canal = 'EMAIL'",
                Integer.class, idDemande), "accusé de réception préparé");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM app.alerte WHERE type = 'DEMANDE_NOUVELLE' AND cle = ?", Integer.class,
                String.valueOf(idDemande)) > 0, "l'agence est prévenue");
        // Un gestionnaire étranger au bâtiment ne la voit pas ; le responsable travaux n'a pas accès
        assertThrows(ErreurMetier.class, () -> demandes.fiche(idDemande, GESTIONNAIRE));
        assertThrows(ErreurMetier.class, () -> demandes.lister(TRAVAUX));
    }

    @Test
    @DisplayName("Huit envois par heure et par appareil, pas un de plus")
    void limiteEnvois() {
        for (int i = 0; i < 8; i++) {
            site.demander(new DemandeSiteDto("CONTACT", null, "Zztest Contact " + i, "0712345678", null, null, null, "Bonjour", null,
                    null, null, true, null), List.of(), "test-limite");
        }
        ErreurMetier e = assertThrows(ErreurMetier.class, () -> site.demander(new DemandeSiteDto("CONTACT", null, "Zztest", "0712345678",
                null, null, null, null, null, null, null, true, null), List.of(), "test-limite"));
        assertEquals(429, e.getStatut().value());
    }

    @Test
    @DisplayName("Une alerte de recherche prépare un message à la publication d'un bien qui lui correspond")
    void alerteDeRecherche() throws Exception {
        site.abonner(new AbonnementDto("zztest.alerte@exemple.ci", Map.of("operation", "LOCATION", "types", List.of("Appartement"),
                "idCommune", idCommune, "prixMax", 400000), true, null), "test-3");
        site.abonner(new AbonnementDto("zztest.trop.cher@exemple.ci", Map.of("operation", "LOCATION", "prixMax", 100000), true, null), "test-3");
        int id = publier();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM web.message WHERE id_annonce = ? AND destinataire = 'zztest.alerte@exemple.ci'",
                Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM web.message WHERE id_annonce = ? AND destinataire = 'zztest.trop.cher@exemple.ci'",
                Integer.class, id), "un budget trop bas n'est pas prévenu");
        String jeton = jdbc.queryForObject("SELECT jeton FROM web.abonnement WHERE contact = 'zztest.alerte@exemple.ci'", String.class);
        assertEquals(true, site.desabonner(jeton).get("arrete"));
        assertEquals(false, site.desabonner(jeton).get("arrete"));
    }
}
