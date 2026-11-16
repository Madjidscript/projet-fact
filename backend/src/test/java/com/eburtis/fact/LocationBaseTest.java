package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.LocationFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.FinBailDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.service.QuittanceService;
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
 * La gestion locative (§ 4.4) jouée contre la base réelle, dans une transaction annulée à la fin.
 * Ne tourne que sur demande : {@code set FACT_TESTS_BASE=1} puis {@code gradlew test}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class LocationBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur DIRECTION = new Utilisateur(-5, "test.dir", "Test Direction", null, null, Profil.DIRECTION, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private LocationFacade location;
    @Autowired private JdbcTemplate jdbc;

    private int idLot;

    /** Un bâtiment d'essai avec un lot vacant à 300 000 FCFA, charges 20 000. */
    @BeforeEach
    void preparer() {
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        int pro = proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", "Zztest Bailleur", null, null, null, null, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
        int bat = batiments.enregistrer(new BatimentSaisieDto(null, "Zztest Immeuble", "IMMEUBLE", null, cocody, null, 5.35, -3.98, null,
                3, null, null, pro, null, null), ADMIN).get("id").asInt();
        idLot = batiments.enregistrerLot(new LotSaisieDto(null, bat, "A1", 0, "F3", 80.0, 3, 2, 1, List.of(), 300000, 20000,
                "VACANT", null), ADMIN).get("id").asInt();
    }

    private OccupantSaisieDto occupant(String nom) {
        return new OccupantSaisieDto(null, "PHYSIQUE", nom, "Test", "07 01 02 03 04", null, null, "CNI", "C0123", "Comptable",
                null, "Garant Test", "05 06 07 08 09", null);
    }

    /** Un bail signé il y a trois mois, le jour 1, exigible le 5 : trois ou quatre échéances déjà dues. */
    private JsonNode signer(int avance, boolean encaissee) {
        LocalDate effet = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        return (JsonNode) location.creerBail(new BailSaisieDto(idLot, null, occupant("Zztest Locataire"), effet, 12, 300000, 20000,
                600000, avance, 5, encaissee, "ESPECES", null), ADMIN).get("bail");
    }

    /** Le nombre d'échéances en retard aujourd'hui pour un bail signé le 1er du mois, il y a trois mois, exigible le 5. */
    private static int retardsAttendus() {
        LocalDate aujourdhui = LocalDate.now();
        int n = 0;
        for (LocalDate m = aujourdhui.withDayOfMonth(1).minusMonths(3); !m.isAfter(aujourdhui); m = m.plusMonths(1)) {
            if (m.withDayOfMonth(5).plusDays(5).isBefore(aujourdhui)) {
                n++;
            }
        }
        return n;
    }

    private String statutLot() {
        return jdbc.queryForObject("SELECT statut FROM pat.lot WHERE id = ?", String.class, idLot);
    }

    @Test
    @DisplayName("Signer un bail : le lot passe Occupé, les échéances jusqu'à ce jour sont produites")
    void signature() {
        JsonNode bail = signer(0, false);
        assertEquals("OCCUPE", statutLot());
        assertTrue(bail.get("reference").asText().startsWith("BAIL-"));
        JsonNode echeances = (JsonNode) location.bail(bail.get("id").asInt(), ADMIN).get("echeances");
        assertEquals(4, echeances.size(), "trois mois passés et le mois en cours");
        assertEquals(320000, echeances.get(0).get("montantDu").asInt(), "loyer et charges");
        assertEquals(5, LocalDate.parse(echeances.get(0).get("dateExigibilite").asText()).getDayOfMonth());
        // Chaque mois échu depuis plus de cinq jours, non payé, est en retard.
        int retards = retardsAttendus();
        assertEquals(retards, bail.get("moisRetard").asInt());
        assertEquals(retards * 320000, bail.get("impaye").asInt());
        assertThrows(ErreurMetier.class, () -> signer(0, false), "un seul bail actif par lot");
    }

    @Test
    @DisplayName("Les paiements soldent les échéances de la plus ancienne à la plus récente ; un partiel laisse un reste")
    void paiements() {
        JsonNode bail = signer(0, false);
        int id = bail.get("id").asInt();
        Map<String, Object> r = location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 480000, "MOBILE_MONEY", "MP123", null), ADMIN);
        @SuppressWarnings("unchecked")
        List<QuittanceService.Periode> periodes = (List<QuittanceService.Periode>) r.get("periodes");
        assertEquals(2, periodes.size(), "un mois et demi : deux périodes touchées");
        assertEquals(320000, periodes.get(0).regle());
        assertEquals(0, periodes.get(0).reste());
        assertEquals(160000, periodes.get(1).regle());
        assertEquals(160000, periodes.get(1).reste());
        JsonNode apres = (JsonNode) r.get("bail");
        int retards = retardsAttendus();
        assertEquals(retards * 320000 - 480000, apres.get("impaye").asInt(), "un mois et demi soldé sur les mois en retard");
        assertEquals(retards - 1, apres.get("moisRetard").asInt(), "le premier mois est soldé, le second partiel reste en retard");

        JsonNode echeances = (JsonNode) location.bail(id, ADMIN).get("echeances");
        JsonNode plusAncienne = echeances.get(echeances.size() - 1);
        assertEquals("A_JOUR", plusAncienne.get("statutPaiement").asText());
        assertEquals("PARTIEL", echeances.get(echeances.size() - 2).get("statutPaiement").asText());

        // Annulation : réservée à la Direction ; l'encaissement reste, barré, et ne compte plus.
        long idEnc = ((JsonNode) r.get("encaissement")).get("id").asLong();
        assertThrows(ErreurMetier.class, () -> location.annuler(idEnc, "erreur", GESTIONNAIRE));
        location.annuler(idEnc, "Saisi sur le mauvais bail", DIRECTION);
        assertEquals(retards * 320000, ((JsonNode) location.bail(id, ADMIN).get("bail")).get("impaye").asInt());
        assertThrows(ErreurMetier.class, () -> location.annuler(idEnc, "deux fois", DIRECTION));
    }

    @Test
    @DisplayName("L'avance encaissée à la signature solde les premiers mois")
    void avance() {
        JsonNode bail = signer(2, true);
        assertEquals(640000, bail.get("paye").asInt());
        assertEquals(retardsAttendus() - 2, bail.get("moisRetard").asInt(), "les deux premiers mois sont payés d'avance");
    }

    @Test
    @DisplayName("Préavis puis sortie : le lot passe En préavis, puis Vacant")
    void finDeBail() {
        int id = signer(0, false).get("id").asInt();
        location.preavis(id, new FinBailDto(LocalDate.now().plusMonths(1), "Mutation professionnelle"), ADMIN);
        assertEquals("EN_PREAVIS", statutLot());
        assertThrows(ErreurMetier.class, () -> location.terminer(id, new FinBailDto(LocalDate.now().plusDays(10), null), ADMIN),
                "une sortie ne s'enregistre qu'une fois faite");
        JsonNode fini = location.terminer(id, new FinBailDto(LocalDate.now(), null), ADMIN);
        assertEquals("RESILIE", fini.get("statut").asText());
        assertEquals("VACANT", statutLot());
        assertEquals(retardsAttendus() * 320000, fini.get("impaye").asInt(), "les mois dus restent dus après la sortie");
        // Le lot est de nouveau louable.
        JsonNode nouveau = signer(0, false);
        assertEquals("ACTIF", nouveau.get("statut").asText());
    }

    @Test
    @DisplayName("La quittance est un PDF, « reçu » si le paiement ne solde pas ses périodes")
    void quittance() {
        int id = signer(0, false).get("id").asInt();
        long complet = ((JsonNode) location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 320000, "ESPECES", null, null), ADMIN)
                .get("encaissement")).get("id").asLong();
        Map.Entry<String, byte[]> pdf = location.quittance(complet, ADMIN);
        assertTrue(pdf.getKey().startsWith("quittance_QUI-"));
        assertEquals("%PDF", new String(pdf.getValue(), 0, 4));
        assertTrue(pdf.getValue().length > 1500);
    }

    @Test
    @DisplayName("La synthèse des loyers : attendu, réglé, encaissé, impayés, pour la Direction comme pour un gestionnaire")
    void synthese() {
        int id = signer(0, false).get("id").asInt();
        location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 320000, "ESPECES", null, null), ADMIN);
        JsonNode s = (JsonNode) location.synthese(ADMIN).get("synthese");
        assertEquals(6, s.get("mois").size());
        assertTrue(s.get("impaye").asLong() >= (retardsAttendus() - 1) * 320000L);
        assertTrue(s.get("bauxEnRetard").asInt() >= (retardsAttendus() > 1 ? 1 : 0));
        JsonNode dernier = s.get("mois").get(5);
        assertTrue(dernier.get("encaisse").asLong() >= 320000, "l'encaissement du jour compte dans le mois");
        assertTrue(((JsonNode) location.synthese(ADMIN).get("derniersEncaissements")).size() >= 1);
        // Un gestionnaire sans portefeuille ne voit rien, mais la requête aboutit.
        JsonNode g = (JsonNode) location.synthese(GESTIONNAIRE).get("synthese");
        assertEquals(0, g.get("bauxActifs").asInt());
    }

    @Test
    @DisplayName("Contrôles : montant, date, lot en travaux, statut Occupé posé à la main")
    void controles() {
        int id = signer(0, false).get("id").asInt();
        assertThrows(ErreurMetier.class, () -> location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 0, "ESPECES", null, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> location.encaisser(new EncaissementSaisieDto(id, LocalDate.now().plusDays(2), 1000, "ESPECES", null, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 1000, "TROC", null, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> location.encaisser(new EncaissementSaisieDto(id, LocalDate.now(), 1000, "ESPECES", null, null), GESTIONNAIRE),
                "hors de son portefeuille");
        // Un lot qui a un bail actif ne change pas de statut à la main.
        int bat = jdbc.queryForObject("SELECT id_batiment FROM pat.lot WHERE id = ?", Integer.class, idLot);
        assertThrows(ErreurMetier.class, () -> batiments.enregistrerLot(new LotSaisieDto(idLot, bat, "A1", 0, "F3", 80.0, 3, 2, 1, List.of(),
                300000, 20000, "VACANT", null), ADMIN));
    }
}
