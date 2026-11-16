package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.InterventionFacade;
import com.eburtis.fact.facade.LocationFacade;
import com.eburtis.fact.facade.MandatFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.facade.RapportFacade;
import com.eburtis.fact.facade.ReleveFacade;
import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.EtapeInterventionDto;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.MandatSaisieDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
import com.eburtis.fact.presentation.dto.PrestataireSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.presentation.dto.ResiliationDto;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.service.ReleveService;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Étape 11 contre la base réelle, dans une transaction annulée : mandats, seuil de travaux du
 * mandat, alertes, indicateurs et relevé de gestion. Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class RapportBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);
    private static final Utilisateur TRAVAUX = new Utilisateur(-6, "test.travaux", "Test Travaux", null, null, Profil.TRAVAUX, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private MandatFacade mandats;
    @Autowired private InterventionFacade interventions;
    @Autowired private LocationFacade location;
    @Autowired private RapportFacade rapports;
    @Autowired private ReleveFacade releves;
    @Autowired private AlerteRepository alertes;
    @Autowired private JdbcTemplate jdbc;

    private int idProprietaire;
    private int idBatiment;
    private int idLot;

    @BeforeEach
    void preparer() {
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        idProprietaire = proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", "Zztest Mandant", null, null, null, null, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
        idBatiment = batiments.enregistrer(new BatimentSaisieDto(null, "Zztest Résidence", "IMMEUBLE", null, cocody, null, 5.35, -3.98,
                null, 3, null, null, idProprietaire, null, null), ADMIN).get("id").asInt();
        idLot = batiments.enregistrerLot(new LotSaisieDto(null, idBatiment, "A1", 0, "F3", 80.0, 3, 2, 1, List.of(), 300000, 0,
                "VACANT", null), ADMIN).get("id").asInt();
    }

    private MandatSaisieDto mandat(LocalDate debut, int duree, boolean reconduction, double taux, Integer seuil) {
        return new MandatSaisieDto(null, idProprietaire, "GESTION_LOCATIVE", debut, duree, reconduction, BigDecimal.valueOf(taux), seuil,
                true, List.of(), null);
    }

    @Test
    @DisplayName("Un mandat se contrôle : taux de 0 à 50 %, Direction seule, bâtiments du propriétaire")
    void controlesDuMandat() {
        assertThrows(ErreurMetier.class, () -> mandats.enregistrer(mandat(LocalDate.now(), 12, true, 80, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> mandats.enregistrer(mandat(LocalDate.now(), 12, true, 10, null), GESTIONNAIRE));
        int autre = jdbc.queryForObject("SELECT id FROM pat.batiment WHERE id_proprietaire <> ? LIMIT 1", Integer.class, idProprietaire);
        assertThrows(ErreurMetier.class, () -> mandats.enregistrer(new MandatSaisieDto(null, idProprietaire, "GESTION_LOCATIVE",
                LocalDate.now(), 12, true, BigDecimal.TEN, null, false, List.of(autre), null), ADMIN));
        JsonNode m = mandats.enregistrer(mandat(LocalDate.now(), 12, true, 10, null), ADMIN);
        assertEquals("ACTIF", m.get("statut").asText());
        assertEquals(LocalDate.now().plusMonths(12).minusDays(1).toString(), m.get("dateFin").asText());
    }

    @Test
    @DisplayName("La reconduction tacite repousse l'échéance ; sans elle, le mandat est échu")
    void reconduction() {
        LocalDate debut = LocalDate.now().minusMonths(13);
        JsonNode reconduit = mandats.enregistrer(mandat(debut, 12, true, 10, null), ADMIN);
        assertEquals("ACTIF", reconduit.get("statut").asText());
        assertEquals(debut.plusMonths(24).minusDays(1).toString(), reconduit.get("dateFin").asText());
        JsonNode echu = mandats.enregistrer(mandat(debut, 12, false, 10, null), ADMIN);
        assertEquals("ECHU", echu.get("statut").asText());
        JsonNode resilie = mandats.resilier(reconduit.get("id").asInt(), new ResiliationDto(LocalDate.now(), "Vente du bien"), ADMIN);
        assertEquals("RESILIE", resilie.get("statut").asText());
    }

    @Test
    @DisplayName("Le seuil de travaux du mandat remplace le réglage général")
    void seuilDuMandat() {
        mandats.enregistrer(mandat(LocalDate.now(), 12, true, 10, 100000), ADMIN);
        int presta = interventions.enregistrerPrestataire(new PrestataireSaisieDto(null, "Zztest Peinture", "PEINTURE", null, null, null, true),
                TRAVAUX).get("id").asInt();
        int id = interventions.signaler(new InterventionSaisieDto(idBatiment, idLot, "PEINTURE", "NORMALE", "Murs à reprendre", null),
                ADMIN).get("id").asInt();
        interventions.etape(id, "affecter", new EtapeInterventionDto(presta, LocalDate.now().plusDays(1), null, null, null, null, null, null), TRAVAUX);
        // 120 000 FCFA : sous le réglage général (150 000) mais au-dessus du seuil de ce mandat (100 000)
        interventions.etape(id, "demarrer", new EtapeInterventionDto(null, null, "Deux pièces à repeindre", 120000, null, null, null, null), TRAVAUX);
        ErreurMetier refus = assertThrows(ErreurMetier.class, () -> interventions.etape(id, "valider",
                new EtapeInterventionDto(null, null, null, null, false, null, null, null), ADMIN));
        assertTrue(refus.getMessage().contains("100 000"), refus.getMessage());
        assertEquals(100000, interventions.fiche(id, ADMIN).get("seuilAccordProprietaire"));
    }

    @Test
    @DisplayName("Une alerte n'est créée qu'une fois, et se marque résolue quand la situation est réglée")
    void alertesLotVacant() {
        jdbc.update("UPDATE pat.lot SET statut_depuis = current_date - 40 WHERE id = ?", idLot);
        alertes.generer();
        String cle = idLot + "-" + LocalDate.now().minusDays(40);
        int n = jdbc.queryForObject("SELECT count(*) FROM app.alerte WHERE type = 'LOT_VACANT' AND cle = ?", Integer.class, cle);
        assertTrue(n > 0, "alerte de vacance attendue");
        alertes.generer();
        assertEquals(n, jdbc.queryForObject("SELECT count(*) FROM app.alerte WHERE type = 'LOT_VACANT' AND cle = ?", Integer.class, cle));
        jdbc.update("UPDATE pat.lot SET statut = 'EN_TRAVAUX' WHERE id = ?", idLot);
        alertes.generer();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM app.alerte WHERE type = 'LOT_VACANT' AND cle = ? AND resolue_le IS NULL",
                Integer.class, cle));
    }

    @Test
    @DisplayName("Le relevé déduit les honoraires du mandat des loyers encaissés")
    void releve() {
        mandats.enregistrer(mandat(LocalDate.now().minusMonths(2), 12, true, 10, null), ADMIN);
        OccupantSaisieDto occ = new OccupantSaisieDto(null, "PHYSIQUE", "Zztest Locataire", "Test", "07 01 02 03 04", null, null, "CNI", "C0123",
                null, null, null, null, null);
        Map<String, Object> b = location.creerBail(new BailSaisieDto(idLot, null, occ, LocalDate.now().withDayOfMonth(1), 12, 300000, 0,
                600000, 0, 5, false, "ESPECES", null), ADMIN);
        int idBail = ((JsonNode) b.get("bail")).get("id").asInt();
        location.encaisser(new EncaissementSaisieDto(idBail, LocalDate.now(), 300000, "ESPECES", null, null), ADMIN);
        String mois = YearMonth.now().toString();
        Map<String, Object> r = releves.donnees(idProprietaire, RapportFacade.periode(mois, mois), ADMIN);
        ReleveService.Totaux t = (ReleveService.Totaux) r.get("totaux");
        assertEquals(300000, t.encaisse());
        assertEquals(30000, t.honoraires());
        assertEquals(270000, t.aReverser());
        assertThrows(ErreurMetier.class, () -> releves.donnees(idProprietaire, RapportFacade.periode(mois, mois), GESTIONNAIRE));
        byte[] pdf = releves.pdf(idProprietaire, RapportFacade.periode(mois, mois), ADMIN).getValue();
        assertTrue(pdf.length > 1000 && new String(pdf, 0, 5).equals("%PDF-"));
    }

    @Test
    @DisplayName("Les indicateurs comptent le nouveau lot et s'exportent en PDF et en Excel")
    void indicateurs() {
        RapportFacade.Periode p = RapportFacade.periode(null, null);
        Map<String, Object> i = rapports.indicateurs(ADMIN, p);
        JsonNode occ = (JsonNode) i.get("occupation");
        int lots = jdbc.queryForObject("""
                SELECT count(*) FROM pat.lot l JOIN pat.batiment b ON b.id = l.id_batiment
                WHERE l.archive_le IS NULL AND b.archive_le IS NULL AND l.statut <> 'HORS_GESTION'""", Integer.class);
        assertEquals(lots, occ.get("lots").asInt());
        assertEquals(12, ((JsonNode) i.get("recouvrement")).get("parMois").size());
        assertNotNull(((JsonNode) i.get("impayes")).get("parAnciennete"));
        assertThrows(ErreurMetier.class, () -> RapportFacade.periode("2026-10", "2026-01"));
        assertTrue(new String(rapports.pdf(ADMIN, p), 0, 5).equals("%PDF-"));
        byte[] x = rapports.excel(ADMIN, p);
        assertTrue(x[0] == 'P' && x[1] == 'K', "un classeur .xlsx est une archive zip");
    }
}
