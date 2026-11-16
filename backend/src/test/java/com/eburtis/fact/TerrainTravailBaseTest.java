package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.EtatDesLieuxFacade;
import com.eburtis.fact.facade.LocationFacade;
import com.eburtis.fact.facade.TourneeFacade;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.EtatDesLieuxDto;
import com.eburtis.fact.presentation.dto.TourneeSaisieDto;
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
 * Restes de l'étape 8 contre la base réelle, dans une transaction annulée : la tournée du jour,
 * l'encaissement sur place rejouable avec son reçu, l'état des lieux signé et son PDF.
 * Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class TerrainTravailBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);

    @Autowired private TourneeFacade tournees;
    @Autowired private LocationFacade location;
    @Autowired private EtatDesLieuxFacade etats;
    @Autowired private JdbcTemplate jdbc;

    private Utilisateur agent;
    private Utilisateur autreAgent;
    private int bail;

    private Utilisateur compte(String identifiant) {
        int id = jdbc.queryForObject("""
                INSERT INTO app.compte (identifiant, nom, profil, mot_de_passe, actif, doit_changer)
                VALUES (?, ?, 'AGENT', 'x', true, false) RETURNING id""", Integer.class, identifiant, "Zztest " + identifiant);
        return new Utilisateur(id, identifiant, "Zztest " + identifiant, null, null, Profil.AGENT, Set.of(), false, 0);
    }

    @BeforeEach
    void preparer() {
        agent = compte("zztest.agent1");
        autreAgent = compte("zztest.agent2");
        List<Integer> b = jdbc.queryForList("""
                SELECT vb.id FROM loc.v_bail vb WHERE vb.statut = 'ACTIF'
                  AND NOT EXISTS (SELECT 1 FROM loc.etat_des_lieux d WHERE d.id_bail = vb.id) ORDER BY vb.id LIMIT 1""", Integer.class);
        assumeTrue(!b.isEmpty(), "il faut un bail en cours sans état des lieux");
        bail = b.get(0);
    }

    private List<Integer> batiments(int n) {
        return jdbc.queryForList("SELECT id FROM pat.batiment WHERE archive_le IS NULL AND geom IS NOT NULL ORDER BY id LIMIT ?", Integer.class, n);
    }

    @Test
    @DisplayName("Tournée : préparée dans l'ordre le plus court, pointée sur place, terminée avec son compte rendu")
    void tourneeDuJour() {
        List<Integer> ids = batiments(4);
        Map<String, Object> t = tournees.enregistrer(new TourneeSaisieDto(agent.id(), LocalDate.now(), "Zztest",
                ids.stream().map((i) -> new TourneeSaisieDto.EtapeSaisie(i, "VISITE", null)).toList()), ADMIN);
        int id = ((JsonNode) t.get("id")).asInt();
        JsonNode etapes = (JsonNode) t.get("etapesDetail");
        assertEquals(4, etapes.size());
        // Un agent ne prépare pas la tournée d'un autre, ni ne pointe ses étapes.
        assertThrows(ErreurMetier.class, () -> tournees.enregistrer(new TourneeSaisieDto(agent.id(), LocalDate.now(), null,
                List.of(new TourneeSaisieDto.EtapeSaisie(ids.get(0), "VISITE", null))), autreAgent));
        JsonNode e = etapes.get(0);
        assertThrows(ErreurMetier.class, () -> tournees.pointer(id, e.get("id").asInt(),
                new TourneeSaisieDto.PointageDto(null, null, null, null, "FAIT", null), autreAgent));
        // Sur place, à une trentaine de mètres : passage prouvé.
        Map<String, Object> p = tournees.pointer(id, e.get("id").asInt(), new TourneeSaisieDto.PointageDto(null,
                e.get("latitude").asDouble() + 0.0003, e.get("longitude").asDouble(), 12.0, "FAIT", "RAS"), agent);
        assertTrue((Integer) p.get("distanceM") < 60);
        assertEquals(false, p.get("eloigne"));
        // Une visite manquée se justifie.
        assertThrows(ErreurMetier.class, () -> tournees.pointer(id, etapes.get(1).get("id").asInt(),
                new TourneeSaisieDto.PointageDto(null, null, null, null, "ABSENT", " "), agent));
        assertThrows(ErreurMetier.class, () -> tournees.terminer(id, " ", agent));
        Map<String, Object> fin = tournees.terminer(id, "Une étape faite", agent);
        assertEquals("TERMINEE", ((JsonNode) fin.get("statut")).asText());
        assertThrows(ErreurMetier.class, () -> tournees.terminer(id, "Encore", agent));
    }

    @Test
    @DisplayName("L'ordre de passage va de proche en proche depuis l'agence")
    void ordreDePassage() {
        List<Integer> ids = batiments(6);
        Map<String, Object> t = tournees.enregistrer(new TourneeSaisieDto(agent.id(), LocalDate.now().plusDays(1), null,
                ids.stream().map((i) -> new TourneeSaisieDto.EtapeSaisie(i, null, null)).toList()), agent);
        JsonNode etapes = (JsonNode) t.get("etapesDetail");
        // La première étape est le bâtiment le plus proche de l'agence, puis chaque étape le plus proche de la précédente.
        double lat = 5.36, lon = -3.96;
        java.util.Set<Integer> restants = new java.util.HashSet<>(ids);
        for (JsonNode e : etapes) {
            double ici = dist(lat, lon, e.get("latitude").asDouble(), e.get("longitude").asDouble());
            for (Integer i : restants) {
                double[] p = jdbc.queryForObject("SELECT ST_Y(geom), ST_X(geom) FROM pat.batiment WHERE id = ?",
                        (rs, n) -> new double[]{rs.getDouble(1), rs.getDouble(2)}, i);
                assertTrue(ici <= dist(lat, lon, p[0], p[1]) + 1e-9, "étape " + e.get("ordre").asInt() + " pas la plus proche");
            }
            restants.remove(e.get("idBatiment").asInt());
            lat = e.get("latitude").asDouble();
            lon = e.get("longitude").asDouble();
        }
    }

    private static double dist(double a, double b, double c, double d) {
        return Math.hypot(a - c, (b - d) * Math.cos(Math.toRadians(a)));
    }

    @Test
    @DisplayName("Encaissement sur place : un envoi rejoué ne crée pas deux paiements, et le reçu part au locataire")
    void encaissementRejoue() {
        UUID cle = UUID.randomUUID();
        EncaissementSaisieDto s = new EncaissementSaisieDto(bail, LocalDate.now(), 15000, "ESPECES", null, null, cle, 5.35, -3.99, true);
        Map<String, Object> a = location.encaisser(s, ADMIN);
        Map<String, Object> b = location.encaisser(s, ADMIN);
        assertEquals(((JsonNode) a.get("encaissement")).get("numero").asText(), ((JsonNode) b.get("encaissement")).get("numero").asText());
        assertEquals(true, b.get("deja"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM loc.encaissement WHERE cle = ?", Integer.class, cle));
        assertEquals("TERRAIN", ((JsonNode) a.get("encaissement")).get("origine").asText());
        long id = ((JsonNode) a.get("encaissement")).get("id").asLong();
        boolean telephone = Boolean.TRUE.equals(jdbc.queryForObject("SELECT telephone_occupant IS NOT NULL FROM loc.v_bail WHERE id = ?", Boolean.class, bail));
        assertEquals(telephone, jdbc.queryForObject("SELECT count(*) FROM web.message WHERE motif = 'RECU' AND canal = 'WHATSAPP' AND id_encaissement = ?",
                Integer.class, id) == 1);
        UUID jeton = jdbc.queryForObject("SELECT jeton_recu FROM loc.encaissement WHERE id = ?", UUID.class, id);
        var recu = location.recuPublic(jeton);
        assertTrue(recu.getValue().length > 1000);
        assertThrows(ErreurMetier.class, () -> location.recuPublic(UUID.randomUUID()));
    }

    private static MockMultipartFile png(String nom) throws Exception {
        BufferedImage i = new BufferedImage(200, 80, BufferedImage.TYPE_INT_RGB);
        i.getGraphics().drawLine(10, 40, 190, 40);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(i, "png", o);
        return new MockMultipartFile(nom, nom + ".png", "image/png", o.toByteArray());
    }

    @Test
    @DisplayName("État des lieux : signé des deux parties, rejouable, un seul d'entrée par bail, PDF produit")
    void etatDesLieux() throws Exception {
        UUID cle = UUID.randomUUID();
        EtatDesLieuxDto d = new EtatDesLieuxDto(cle, bail, "ENTREE", null,
                List.of(new EtatDesLieuxDto.Piece("Séjour", List.of(new EtatDesLieuxDto.Element("Murs", "BON", null),
                        new EtatDesLieuxDto.Element("Sol", "MAUVAIS", "Carreau fendu")), null)),
                Map.of("eau", "123"), 2, null, "Zztest Occupant", null, null);
        // Sans signature de l'occupant : refusé.
        assertThrows(ErreurMetier.class, () -> etats.recevoir(d, null, png("signatureAgent"), null, agent));
        Map<String, Object> r = etats.recevoir(d, png("signatureOccupant"), png("signatureAgent"), null, agent);
        JsonNode e = (JsonNode) r.get("etatDesLieux");
        assertNotNull(e.get("reference"));
        assertEquals(false, r.get("deja"));
        assertEquals(true, etats.recevoir(d, png("signatureOccupant"), png("signatureAgent"), null, agent).get("deja"));
        // Un second état des lieux d'entrée pour ce bail : refusé.
        EtatDesLieuxDto autre = new EtatDesLieuxDto(UUID.randomUUID(), bail, "ENTREE", null, d.pieces(), null, null, null, "X", null, null);
        assertThrows(ErreurMetier.class, () -> etats.recevoir(autre, png("o"), png("a"), null, agent));
        // Un état sans valeur connue : refusé.
        EtatDesLieuxDto faux = new EtatDesLieuxDto(UUID.randomUUID(), bail, "SORTIE", null,
                List.of(new EtatDesLieuxDto.Piece("Cuisine", List.of(new EtatDesLieuxDto.Element("Évier", "SUPERBE", null)), null)),
                null, null, null, "X", null, null);
        assertThrows(ErreurMetier.class, () -> etats.recevoir(faux, png("o"), png("a"), null, agent));
        var pdf = etats.pdf(e.get("id").asInt(), ADMIN);
        assertTrue(pdf.getValue().length > 2000);
        assertFalse(etats.duBail(bail, ADMIN).isEmpty());
        // Le propriétaire n'y a pas accès.
        Utilisateur proprietaire = new Utilisateur(-9, "test.pro", "Test Pro", null, null, Profil.PROPRIETAIRE, Set.of(), false, 0);
        assertThrows(ErreurMetier.class, () -> etats.etatDesLieux(e.get("id").asInt(), proprietaire));
    }
}
