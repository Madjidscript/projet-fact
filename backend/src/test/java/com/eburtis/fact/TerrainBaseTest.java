package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.TerrainFacade;
import com.eburtis.fact.presentation.dto.RecensementDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Étape 8 contre la base réelle, dans une transaction annulée : un recensement de terrain de
 * douze lots, rejoué sans doublon (recette du § 9). Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class TerrainBaseTest {

    private static final Utilisateur AGENT = new Utilisateur(-4, "test.agent", "Test Agent", null, null, Profil.AGENT, Set.of(), false, 0);
    private static final Utilisateur TRAVAUX = new Utilisateur(-6, "test.travaux", "Test Travaux", null, null, Profil.TRAVAUX, Set.of(), false, 0);

    @Autowired private TerrainFacade terrain;
    @Autowired private JdbcTemplate jdbc;

    private RecensementDto recensement(UUID cle, List<RecensementDto.LotTerrain> lots) {
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        return new RecensementDto(cle, OffsetDateTime.now().minusHours(3), "Zztest Immeuble du terrain", "IMMEUBLE", "Près du marché",
                cocody, null, 5.371, -3.991, 9.0, 4, null, "Gardien présent", lots);
    }

    private static List<RecensementDto.LotTerrain> douzeLots() {
        List<RecensementDto.LotTerrain> l = new ArrayList<>();
        for (int e = 0; e < 4; e++) {
            for (int p = 1; p <= 3; p++) {
                l.add(new RecensementDto.LotTerrain((e == 0 ? "RDC" : String.valueOf(e)) + "-" + p, e, "F3", p == 2 ? "VACANT" : "OCCUPE", 3, null, null));
            }
        }
        return l;
    }

    @Test
    @DisplayName("Un immeuble de 12 lots arrive « À valider », et un envoi rejoué ne crée pas de doublon")
    void recensementRejoue() {
        UUID cle = UUID.randomUUID();
        Map<String, Object> r = terrain.recevoir(recensement(cle, douzeLots()), List.of(), AGENT);
        int id = (Integer) r.get("idBatiment");
        assertEquals("A_VALIDER", jdbc.queryForObject("SELECT validation FROM pat.batiment WHERE id = ?", String.class, id));
        assertEquals("TERRAIN", jdbc.queryForObject("SELECT origine FROM pat.batiment WHERE id = ?", String.class, id));
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM pat.lot WHERE id_batiment = ?", Integer.class, id));
        assertEquals(8, jdbc.queryForObject("SELECT count(*) FROM pat.lot WHERE id_batiment = ? AND statut = 'OCCUPE'", Integer.class, id));
        assertEquals("PRO-SITE", jdbc.queryForObject("""
                SELECT p.reference FROM pat.batiment b JOIN pat.proprietaire p ON p.id = b.id_proprietaire WHERE b.id = ?""", String.class, id),
                "sans propriétaire connu : « à préciser »");

        Map<String, Object> rejoue = terrain.recevoir(recensement(cle, douzeLots()), List.of(), AGENT);
        assertEquals(id, rejoue.get("idBatiment"));
        assertEquals(true, rejoue.get("deja"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pat.batiment WHERE nom = 'Zztest Immeuble du terrain'", Integer.class));
    }

    @Test
    @DisplayName("Un recensement incohérent est refusé en entier ; il faut le droit de saisir")
    void controles() {
        List<RecensementDto.LotTerrain> doublon = List.of(new RecensementDto.LotTerrain("A1", 0, "F3", "OCCUPE", null, null, null),
                new RecensementDto.LotTerrain("a1", 0, "F3", "VACANT", null, null, null));
        assertThrows(ErreurMetier.class, () -> terrain.recevoir(recensement(UUID.randomUUID(), doublon), List.of(), AGENT));
        assertThrows(ErreurMetier.class, () -> terrain.recevoir(recensement(UUID.randomUUID(), List.of()), List.of(), TRAVAUX));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pat.batiment WHERE nom = 'Zztest Immeuble du terrain'", Integer.class));
    }
}
