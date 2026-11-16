package com.eburtis.fact.assistant.outils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.InterventionFacade;
import com.eburtis.fact.facade.LocationFacade;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * « Fais-moi le point » : un résumé de ce qui demande de l'attention aujourd'hui.
 * Chaque rubrique n'apparaît que si le compte a le droit de la voir.
 */
@Component
public class PointDuJourOutil implements OutilAssistant {

    /** Un bail qui se termine dans ce délai est signalé. */
    private static final int JOURS_ECHEANCE = 60;

    private final BatimentFacade batimentFacade;
    private final LocationFacade locationFacade;
    private final InterventionFacade interventionFacade;

    public PointDuJourOutil(BatimentFacade batimentFacade, LocationFacade locationFacade, InterventionFacade interventionFacade) {
        this.batimentFacade = batimentFacade;
        this.locationFacade = locationFacade;
        this.interventionFacade = interventionFacade;
    }

    @Override
    public String nom() {
        return "point_du_jour";
    }

    @Override
    public String description() {
        return "Fait le point de la journée : impayés, baux qui se terminent bientôt, interventions urgentes, lots vacants. "
                + "À utiliser quand la personne demande un résumé, un bilan, « le point », ou ce qui est urgent aujourd'hui.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public String droit() {
        return null;            // chaque rubrique vérifie son propre droit
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        List<String> rubriques = new ArrayList<>();

        if (utilisateur.peut(Profil.Droit.PATRIMOINE_LIRE)) {
            int batiments = 0;
            int vacants = 0;
            for (JsonNode b : batimentFacade.lister(utilisateur, false)) {
                batiments++;
                vacants += b.path("lotsVacants").asInt();
            }
            rubriques.add("Patrimoine : " + batiments + " bâtiment(s), " + vacants + " lot(s) vacant(s).");
        }

        if (utilisateur.peut(Profil.Droit.LOCATION_LIRE)) {
            JsonNode retards = locationFacade.baux(utilisateur, "retard", null);
            long total = 0;
            for (JsonNode b : retards) {
                total += b.path("impaye").asLong();
            }
            rubriques.add("Impayés : " + retards.size() + " bail(s) en retard, " + total + " FCFA au total.");

            LocalDate aujourdHui = LocalDate.now(ZoneId.of("Africa/Abidjan"));
            List<String> echeances = new ArrayList<>();
            for (JsonNode b : locationFacade.baux(utilisateur, "actifs", null)) {
                LocalDate fin = date(b.path("dateFin").asText(null));
                if (fin != null && !fin.isBefore(aujourdHui) && !fin.isAfter(aujourdHui.plusDays(JOURS_ECHEANCE))) {
                    echeances.add(b.path("batiment").asText("") + " lot " + b.path("numeroLot").asText("?") + " le " + fin);
                }
            }
            rubriques.add("Baux qui se terminent dans les " + JOURS_ECHEANCE + " jours : " + echeances.size()
                    + (echeances.isEmpty() ? "." : " (" + String.join(" ; ", echeances.subList(0, Math.min(8, echeances.size()))) + ")."));
        }

        if (utilisateur.peut(Profil.Droit.TRAVAUX_LIRE)) {
            int ouvertes = 0;
            List<String> urgentes = new ArrayList<>();
            for (JsonNode i : interventionFacade.lister(utilisateur, "ouvertes", null)) {
                ouvertes++;
                String urgence = i.path("urgence").asText("");
                if (urgence.equals("HAUTE") || urgence.equals("CRITIQUE")) {
                    urgentes.add(i.path("reference").asText("") + " (" + i.path("batiment").asText("") + ")");
                }
            }
            rubriques.add("Travaux : " + ouvertes + " intervention(s) ouverte(s), dont " + urgentes.size() + " urgente(s)"
                    + (urgentes.isEmpty() ? "." : " : " + String.join(", ", urgentes.subList(0, Math.min(8, urgentes.size()))) + "."));
        }

        if (rubriques.isEmpty()) {
            return "Ce compte n'a accès à aucune des rubriques du point du jour.";
        }
        return "Point du jour :\n- " + String.join("\n- ", rubriques);
    }

    private static LocalDate date(String texte) {
        if (texte == null || texte.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(texte.substring(0, 10));
        } catch (DateTimeParseException illisible) {
            return null;
        }
    }
}
