package com.eburtis.fact.assistant.outils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.facade.BatimentFacade;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Le guide : ouvre une page de FACT pour la personne.
 *
 * Le modèle ne choisit jamais une adresse libre : il choisit une page dans une liste fermée,
 * et ce code fabrique l'adresse. Chaque page garde le droit qu'exige sa route Angular : on ne
 * propose jamais une page que la personne ne pourrait pas ouvrir.
 */
@Component
public class OuvrirPageOutil implements OutilAssistant {

    /** Une page de l'application : son adresse, son nom affiché, le droit exigé, et si un propriétaire peut l'ouvrir. */
    private record Page(String chemin, String libelle, String droit, boolean personnelSeulement) {
    }

    /** Les pages, recopiées de frontend/src/app/app.routes.ts. Une page ajoutée là-bas s'ajoute ici. */
    private static final Map<String, Page> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("tableau_bord", new Page("/", "Tableau de bord", null, true));
        PAGES.put("mon_espace", new Page("/espace", "Mon espace", null, false));
        PAGES.put("carte", new Page("/carte", "Carte du patrimoine", Profil.Droit.PATRIMOINE_LIRE, false));
        PAGES.put("batiments", new Page("/patrimoine", "Bâtiments", Profil.Droit.PATRIMOINE_LIRE, true));
        PAGES.put("fiche_batiment", new Page("/patrimoine/", "Fiche du bâtiment", Profil.Droit.PATRIMOINE_LIRE, true));
        PAGES.put("loyers", new Page("/loyers", "Loyers", Profil.Droit.LOCATION_LIRE, true));
        PAGES.put("travaux", new Page("/travaux", "Travaux", Profil.Droit.TRAVAUX_LIRE, true));
        PAGES.put("tournees", new Page("/tournees", "Tournées", null, true));
        PAGES.put("portefeuilles", new Page("/portefeuilles", "Portefeuilles", Profil.Droit.AFFECTATION_LIRE, true));
        PAGES.put("annonces", new Page("/annonces", "Annonces", null, true));
        PAGES.put("demandes", new Page("/demandes", "Demandes", null, true));
        PAGES.put("rapports", new Page("/rapports", "Rapports", Profil.Droit.TABLEAU_BORD, true));
        PAGES.put("proprietaires", new Page("/proprietaires", "Propriétaires", Profil.Droit.PATRIMOINE_LIRE, true));
        PAGES.put("import", new Page("/import", "Import du patrimoine", Profil.Droit.PARAMETRES_LIRE, false));
        PAGES.put("comptes", new Page("/comptes", "Comptes", Profil.Droit.PARAMETRES_LIRE, false));
        PAGES.put("journal", new Page("/journal", "Journal", Profil.Droit.PARAMETRES_LIRE, false));
        PAGES.put("mot_de_passe", new Page("/mot-de-passe", "Changer de mot de passe", null, false));
    }

    private final BatimentFacade batimentFacade;

    public OuvrirPageOutil(BatimentFacade batimentFacade) {
        this.batimentFacade = batimentFacade;
    }

    @Override
    public String nom() {
        return "ouvrir_page";
    }

    @Override
    public String description() {
        return "Ouvre une page de l'application à l'écran de la personne. À utiliser quand elle veut voir, aller sur, "
                + "ou être guidée vers une page. Pour la fiche d'un bâtiment, donne son id : trouve-le d'abord avec chercher_batiments.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page", Map.of(
                                "type", "string",
                                "enum", List.copyOf(PAGES.keySet()),     // le modèle ne peut choisir QUE dans cette liste
                                "description", "La page à ouvrir."),
                        "id_batiment", Map.of(
                                "type", "integer",
                                "description", "Seulement pour fiche_batiment : l'id du bâtiment.")),
                "required", List.of("page"));
    }

    @Override
    public String droit() {
        return null;            // chaque page vérifie son propre droit, ci-dessous
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        String cle = arguments.path("page").asText();
        Page page = PAGES.get(cle);
        if (page == null) {
            return "Cette page n'existe pas. Pages possibles : " + String.join(", ", PAGES.keySet()) + ".";
        }
        if (page.droit() != null && !utilisateur.peut(page.droit())) {
            return "Refusé : ce compte n'a pas le droit d'ouvrir la page « " + page.libelle() + " ». Rien n'a été ouvert.";
        }
        boolean proprietaire = utilisateur.profil() == Profil.PROPRIETAIRE;
        if (proprietaire && page.personnelSeulement()) {
            return "Refusé : la page « " + page.libelle() + " » est réservée au personnel. Proposez « Mon espace » à la place.";
        }

        if (cle.equals("fiche_batiment")) {
            if (!arguments.hasNonNull("id_batiment")) {
                return "Il manque id_batiment. Cherche d'abord le bâtiment avec chercher_batiments.";
            }
            int id = arguments.get("id_batiment").asInt();
            // La facade vérifie que le bâtiment est dans le périmètre du compte : sinon elle lève une
            // ErreurMetier, que la boucle transforme en « Refusé : ... » pour le modèle.
            JsonNode batiment = batimentFacade.fiche(id, utilisateur).get("batiment") instanceof JsonNode b ? b : null;
            String nom = batiment == null ? "bâtiment " + id : batiment.path("nom").asText("bâtiment " + id);
            actions.naviguer(page.chemin() + id, "Fiche : " + nom);
            return "La fiche du bâtiment « " + nom + " » est ouverte à l'écran.";
        }

        actions.naviguer(page.chemin(), page.libelle());
        return "La page « " + page.libelle() + " » est ouverte à l'écran.";
    }
}
