package com.eburtis.fact.facade;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.repository.RapportRepository;
import com.eburtis.fact.service.ExportService;
import com.eburtis.fact.service.ExportService.Colonne;
import com.eburtis.fact.service.ExportService.Format;
import com.eburtis.fact.service.ExportService.Tableau;
import com.eburtis.fact.service.PerimetreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.springframework.stereotype.Service;

/**
 * Les tableaux de bord et rapports du § 4.8 : indicateurs sur une période de mois, limités au
 * périmètre du compte, et leurs exports PDF et Excel.
 */
@Service
public class RapportFacade {

    /** Une période de mois : du premier jour du mois {@code du} au dernier jour du mois {@code au}. */
    public record Periode(LocalDate du, LocalDate au) {
        public String libelle() {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE);
            return du.equals(au) ? f.format(du) : "de " + f.format(du) + " à " + f.format(au);
        }
    }

    private final RapportRepository rapportRepository;
    private final PerimetreService perimetreService;
    private final ExportService exportService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RapportFacade(RapportRepository rapportRepository, PerimetreService perimetreService, ExportService exportService) {
        this.rapportRepository = rapportRepository;
        this.perimetreService = perimetreService;
        this.exportService = exportService;
    }

    /** « 2026-01 » et « 2026-11 » ; par défaut les douze derniers mois, mois en cours compris. */
    public static Periode periode(String du, String au) {
        try {
            YearMonth fin = au == null || au.isBlank() ? YearMonth.now() : YearMonth.parse(au);
            YearMonth debut = du == null || du.isBlank() ? fin.minusMonths(11) : YearMonth.parse(du);
            if (debut.isAfter(fin)) {
                throw ErreurMetier.invalide("Le premier mois de la période doit précéder le dernier.");
            }
            if (debut.plusMonths(60).isBefore(fin)) {
                throw ErreurMetier.invalide("La période ne peut pas dépasser cinq ans.");
            }
            return new Periode(debut.atDay(1), fin.atDay(1));
        } catch (DateTimeParseException e) {
            throw ErreurMetier.invalide("Mois attendu sous la forme AAAA-MM.");
        }
    }

    public Map<String, Object> indicateurs(Utilisateur u, Periode p) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("periode", Map.of("du", p.du().toString(), "au", p.au().toString(), "libelle", p.libelle()));
        r.put("occupation", rapportRepository.occupation(perimetre, params));
        r.put("recouvrement", rapportRepository.recouvrement(perimetre, params, p.du(), p.au()));
        r.put("impayes", rapportRepository.impayes(perimetre, params));
        r.put("vacance", rapportRepository.vacance(perimetre, params, p.du(), p.au()));
        r.put("loyerM2", rapportRepository.loyerM2(perimetre, params));
        r.put("interventions", rapportRepository.interventions(perimetre, params, p.du(), p.au()));
        return r;
    }

    public byte[] excel(Utilisateur u, Periode p) {
        return exportService.excel("Tableau de bord", sousTitre(u, p), tableaux(indicateurs(u, p)));
    }

    public byte[] pdf(Utilisateur u, Periode p) {
        return exportService.pdf("Tableau de bord", sousTitre(u, p), tableaux(indicateurs(u, p)));
    }

    private static String sousTitre(Utilisateur u, Periode p) {
        String l = p.libelle();
        return Character.toUpperCase(l.charAt(0)) + l.substring(1) + (perimetreTout(u) ? "" : " · portefeuille de " + u.nom());
    }

    private static boolean perimetreTout(Utilisateur u) {
        return u.profil().portee() == com.eburtis.fact.domain.Profil.Portee.TOUT;
    }

    /** Les tableaux exportés, dans l'ordre du § 4.8. */
    private List<Tableau> tableaux(Map<String, Object> i) {
        JsonNode occ = (JsonNode) i.get("occupation");
        JsonNode rec = (JsonNode) i.get("recouvrement");
        JsonNode imp = (JsonNode) i.get("impayes");
        JsonNode vac = (JsonNode) i.get("vacance");
        JsonNode m2 = (JsonNode) i.get("loyerM2");
        JsonNode itv = (JsonNode) i.get("interventions");

        ArrayNode cles = mapper.createArrayNode();
        cle(cles, "Lots gérés", occ.get("lots").asText());
        cle(cles, "Taux d'occupation", taux(occ.get("taux")));
        cle(cles, "Loyers attendus", ExportService.texte(rec.get("attendu"), Format.FCFA));
        cle(cles, "Loyers réglés", ExportService.texte(rec.get("regle"), Format.FCFA));
        cle(cles, "Taux de recouvrement", taux(rec.get("taux")));
        cle(cles, "Argent reçu sur la période", ExportService.texte(rec.get("encaisse"), Format.FCFA));
        cle(cles, "Impayés en retard", ExportService.texte(imp.get("total"), Format.FCFA) + " (" + imp.get("baux").asText() + " bail(s))");
        cle(cles, "Durée moyenne de vacance", vac.get("moyenne").isNull() ? "pas de relocation sur la période"
                : vac.get("moyenne").asText() + " jours (" + vac.get("relocations").asText() + " relocation(s))");
        cle(cles, "Loyer moyen au m²", m2.get("moyenne").isNull() ? "-" : ExportService.texte(m2.get("moyenne"), Format.FCFA));
        cle(cles, "Délai moyen des interventions", itv.get("delaiMoyen").isNull() ? "aucune clôturée"
                : ExportService.texte(itv.get("delaiMoyen"), Format.JOURS) + " (" + itv.get("closes").asText() + " clôturée(s))");

        List<Colonne> occCols = List.of(col("libelle", "", Format.TEXTE), col("lots", "Lots", Format.ENTIER),
                col("occupes", "Occupés", Format.ENTIER), col("taux", "Taux", Format.TAUX));
        List<Colonne> recCols = List.of(col("libelle", "", Format.TEXTE), col("attendu", "Attendu", Format.FCFA),
                col("regle", "Réglé", Format.FCFA), col("taux", "Recouvrement", Format.TAUX));
        List<Colonne> vacCols = List.of(col("libelle", "", Format.TEXTE), col("cas", "Lots", Format.ENTIER),
                col("jours", "Durée moyenne", Format.JOURS));
        List<Colonne> m2Cols = List.of(col("libelle", "", Format.TEXTE), col("lots", "Lots", Format.ENTIER),
                col("prix", "Loyer au m²", Format.FCFA), col("loyerMoyen", "Loyer moyen", Format.FCFA),
                col("surfaceMoyenne", "Surface moyenne (m²)", Format.ENTIER));
        List<Colonne> itvCols = List.of(col("libelle", "", Format.TEXTE), col("interventions", "Clôturées", Format.ENTIER),
                col("jours", "Délai moyen", Format.JOURS), col("cout", "Coût", Format.FCFA));

        return List.of(
                new Tableau("Chiffres clés", List.of(col("libelle", "Indicateur", Format.TEXTE), col("valeur", "Valeur", Format.TEXTE)), cles),
                new Tableau("Occupation par commune", renomme(occCols, "Commune"), occ.get("parCommune")),
                new Tableau("Occupation par gestionnaire", renomme(occCols, "Gestionnaire"), occ.get("parGestionnaire")),
                new Tableau("Occupation par propriétaire", renomme(occCols, "Propriétaire"), occ.get("parProprietaire")),
                new Tableau("Occupation par bâtiment", renomme(occCols, "Bâtiment"), occ.get("parBatiment")),
                new Tableau("Recouvrement par mois", List.of(col("mois", "Mois", Format.MOIS), col("attendu", "Attendu", Format.FCFA),
                        col("regle", "Réglé", Format.FCFA), col("taux", "Recouvrement", Format.TAUX),
                        col("encaisse", "Argent reçu", Format.FCFA)), rec.get("parMois")),
                new Tableau("Recouvrement par gestionnaire", renomme(recCols, "Gestionnaire"), rec.get("parGestionnaire")),
                new Tableau("Recouvrement par bâtiment", renomme(recCols, "Bâtiment"), rec.get("parBatiment")),
                new Tableau("Impayés par ancienneté", List.of(col("libelle", "Retard", Format.TEXTE), col("baux", "Baux", Format.ENTIER),
                        col("montant", "Montant", Format.FCFA)), imp.get("parAnciennete")),
                new Tableau("Plus gros impayés", List.of(col("occupant", "Locataire", Format.TEXTE), col("telephone", "Téléphone", Format.TEXTE),
                        col("batiment", "Bâtiment", Format.TEXTE), col("lot", "Lot", Format.TEXTE), col("moisRetard", "Mois de retard", Format.ENTIER),
                        col("montant", "Montant", Format.FCFA)), imp.get("dossiers")),
                new Tableau("Vacance par type de lot", renomme(vacCols, "Type de lot"), vac.get("parType")),
                new Tableau("Vacance par quartier", renomme(vacCols, "Quartier"), vac.get("parQuartier")),
                new Tableau("Lots vacants aujourd'hui par type", renomme(vacCols, "Type de lot"), vac.get("enCours").get("parType")),
                new Tableau("Lots vacants aujourd'hui par quartier", renomme(vacCols, "Quartier"), vac.get("enCours").get("parQuartier")),
                new Tableau("Loyer au m² par quartier", renomme(m2Cols, "Quartier"), m2.get("parQuartier")),
                new Tableau("Loyer au m² par type de lot", renomme(m2Cols, "Type de lot"), m2.get("parType")),
                new Tableau("Interventions par catégorie", renomme(itvCols, "Catégorie"), itv.get("parCategorie")),
                new Tableau("Interventions par prestataire", renomme(itvCols, "Prestataire"), itv.get("parPrestataire")));
    }

    private static Colonne col(String cle, String libelle, Format f) {
        return new Colonne(cle, libelle, f);
    }

    /** La première colonne prend le nom de l'axe d'analyse. */
    private static List<Colonne> renomme(List<Colonne> cols, String premiere) {
        List<Colonne> r = new ArrayList<>(cols);
        r.set(0, col(cols.get(0).cle(), premiere, cols.get(0).format()));
        return r;
    }

    private void cle(ArrayNode a, String libelle, String valeur) {
        a.addObject().put("libelle", libelle).put("valeur", valeur);
    }

    private static String taux(JsonNode t) {
        return t == null || t.isNull() ? "-" : ExportService.texte(t, Format.TAUX);
    }
}
