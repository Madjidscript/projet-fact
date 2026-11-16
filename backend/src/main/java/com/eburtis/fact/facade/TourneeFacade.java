package com.eburtis.fact.facade;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.TourneeSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.TourneeRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La tournée du jour (§ 4.6) : le gestionnaire (ou l'agent lui-même) choisit les bâtiments à
 * visiter ; l'ordre de passage est calculé au plus court depuis l'agence ; sur place, l'agent
 * pointe chaque étape (la distance au bâtiment prouve le passage) et laisse un compte rendu.
 */
@Service
public class TourneeFacade {

    public static final String OBJET = "TOURNEE";
    private static final Set<String> MOTIFS = Set.of("VISITE", "RECOUVREMENT", "ETAT_DES_LIEUX", "CONTROLE", "TRAVAUX");
    private static final Set<String> RESULTATS = Set.of("FAIT", "ABSENT", "REPORTE");
    private static final int ETAPES_MAX = 30;

    private final TourneeRepository tourneeRepository;
    private final BatimentRepository batimentRepository;
    private final PerimetreService perimetreService;
    private final JournalService journalService;

    public TourneeFacade(TourneeRepository tourneeRepository, BatimentRepository batimentRepository, PerimetreService perimetreService,
                         JournalService journalService) {
        this.tourneeRepository = tourneeRepository;
        this.batimentRepository = batimentRepository;
        this.perimetreService = perimetreService;
        this.journalService = journalService;
    }

    /** Peut préparer la tournée des autres : gestionnaires, Direction, administrateur. */
    public static boolean planificateur(Utilisateur u) {
        return u.estDirection() || u.peut(Profil.Droit.LOCATION_ECRIRE) || u.peut(Profil.Droit.AFFECTATION_LIRE);
    }

    private static void exigerTerrain(Utilisateur u) {
        if (!planificateur(u) && !u.peut(Profil.Droit.TERRAIN)) {
            throw ErreurMetier.refuse("Les tournées sont réservées aux agents de terrain et aux gestionnaires.");
        }
    }

    // ------------------------------------------------------------------ lecture
    public JsonNode duJour(LocalDate jour, Integer idAgent, Utilisateur u) {
        exigerTerrain(u);
        return tourneeRepository.duJourPourTous(jour == null ? LocalDate.now() : jour, planificateur(u) ? idAgent : Integer.valueOf(u.id()));
    }

    /** La tournée du jour du compte connecté, avec ses étapes ; nulle s'il n'en a pas. */
    public Map<String, Object> maTournee(Utilisateur u) {
        exigerTerrain(u);
        return tourneeRepository.duJour(u.id(), LocalDate.now()).map(this::avecEtapes).orElse(null);
    }

    public Map<String, Object> tournee(int id, Utilisateur u) {
        return avecEtapes(lisible(id, u));
    }

    public JsonNode agents(Utilisateur u) {
        exigerTerrain(u);
        return tourneeRepository.agents();
    }

    public JsonNode suggestions(LocalDate jour, Utilisateur u) {
        exigerTerrain(u);
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return tourneeRepository.suggestions(jour == null ? LocalDate.now() : jour, perimetre, params);
    }

    // ------------------------------------------------------------------ préparation
    /** Crée la tournée d'un agent pour un jour, ou remplace ses étapes non encore pointées. */
    @Transactional
    public Map<String, Object> enregistrer(TourneeSaisieDto s, Utilisateur u) {
        exigerTerrain(u);
        int agent = s.idAgent() == null ? u.id() : s.idAgent();
        if (agent != u.id() && !planificateur(u)) {
            throw ErreurMetier.refuse("Vous ne préparez que votre propre tournée.");
        }
        if (!tourneeRepository.estAgentPossible(agent)) {
            throw ErreurMetier.invalide("Choisissez un agent de terrain ou un gestionnaire actif.");
        }
        LocalDate jour = s.jour() == null ? LocalDate.now() : s.jour();
        if (jour.isBefore(LocalDate.now())) {
            throw ErreurMetier.invalide("Une tournée se prépare pour aujourd'hui ou un jour à venir.");
        }
        List<TourneeSaisieDto.EtapeSaisie> etapes = s.etapes() == null ? List.of() : s.etapes();
        if (etapes.isEmpty()) {
            throw ErreurMetier.invalide("Ajoutez au moins un bâtiment à visiter.");
        }
        if (etapes.size() > ETAPES_MAX) {
            throw ErreurMetier.invalide("Au plus " + ETAPES_MAX + " bâtiments par tournée : elle doit tenir dans la journée.");
        }
        Map<Integer, TourneeSaisieDto.EtapeSaisie> parBatiment = new LinkedHashMap<>();
        for (TourneeSaisieDto.EtapeSaisie e : etapes) {
            if (e.idBatiment() == null) {
                throw ErreurMetier.invalide("Chaque étape a besoin d'un bâtiment.");
            }
            if (e.motif() != null && !MOTIFS.contains(e.motif())) {
                throw ErreurMetier.invalide("Motif de visite inconnu : " + e.motif() + ".");
            }
            visible(e.idBatiment(), u);
            parBatiment.putIfAbsent(e.idBatiment(), e);
        }
        String consigne = vide(s.consigne());
        JsonNode existante = tourneeRepository.duJour(agent, jour).orElse(null);
        int id;
        if (existante == null) {
            id = tourneeRepository.creer(agent, jour, consigne, u.signature());
        } else {
            if ("TERMINEE".equals(existante.get("statut").asText())) {
                throw ErreurMetier.invalide("La tournée de ce jour est déjà terminée.");
            }
            id = existante.get("id").asInt();
            tourneeRepository.modifier(id, consigne, u.signature());
        }
        List<Integer> ordre = ordonner(new ArrayList<>(parBatiment.keySet()));
        tourneeRepository.retirerEtapesSauf(id, ordre);
        for (int i = 0; i < ordre.size(); i++) {
            TourneeSaisieDto.EtapeSaisie e = parBatiment.get(ordre.get(i));
            tourneeRepository.poserEtape(id, e.idBatiment(), i + 1, e.motif() == null ? "VISITE" : e.motif(), vide(e.consigne()));
        }
        JsonNode t = tourneeRepository.trouver(id).orElseThrow();
        journalService.consigner(u.signature(), OBJET, String.valueOf(id), (existante == null ? "Tournée préparée : " : "Tournée modifiée : ")
                + t.get("reference").asText() + " de " + t.get("agent").asText() + " le " + jour + ", " + ordre.size() + " étape(s)",
                existante, t);
        return avecEtapes(t);
    }

    /**
     * L'ordre de passage : au plus proche, de proche en proche, depuis l'agence. Pour une
     * trentaine d'arrêts, c'est rapide et proche du meilleur trajet.
     */
    List<Integer> ordonner(List<Integer> batiments) {
        Map<Integer, double[]> pos = new HashMap<>();
        for (double[] p : tourneeRepository.positions(batiments)) {
            pos.put((int) p[0], new double[]{p[1], p[2]});
        }
        double lat = tourneeRepository.reglage("agence.latitude", 5.36), lon = tourneeRepository.reglage("agence.longitude", -3.96);
        Set<Integer> restants = new LinkedHashSet<>(batiments);
        List<Integer> ordre = new ArrayList<>();
        while (!restants.isEmpty()) {
            Integer meilleur = null;
            double d = Double.MAX_VALUE;
            for (Integer b : restants) {
                double[] p = pos.get(b);
                double x = p == null ? Double.MAX_VALUE / 2 : distance(lat, lon, p[0], p[1]);
                if (x < d) {
                    d = x;
                    meilleur = b;
                }
            }
            ordre.add(meilleur);
            restants.remove(meilleur);
            double[] p = pos.get(meilleur);
            if (p != null) {
                lat = p[0];
                lon = p[1];
            }
        }
        return ordre;
    }

    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000, f1 = Math.toRadians(lat1), f2 = Math.toRadians(lat2);
        double df = f2 - f1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(df / 2) * Math.sin(df / 2) + Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ------------------------------------------------------------------ sur place
    /** Pointe une étape : sur place, ou plus tard depuis le téléphone (l'heure saisie est gardée). */
    @Transactional
    public Map<String, Object> pointer(int idTournee, int idEtape, TourneeSaisieDto.PointageDto p, Utilisateur u) {
        JsonNode t = lisible(idTournee, u);
        if (t.get("idAgent").asInt() != u.id()) {
            throw ErreurMetier.refuse("Seul l'agent de la tournée pointe ses étapes.");
        }
        JsonNode e = tourneeRepository.etape(idEtape).orElseThrow(() -> ErreurMetier.introuvable("Étape inconnue."));
        if (e.get("idTournee").asInt() != idTournee) {
            throw ErreurMetier.introuvable("Étape inconnue.");
        }
        String resultat = p.resultat() == null ? "FAIT" : p.resultat();
        if (!RESULTATS.contains(resultat)) {
            throw ErreurMetier.invalide("Résultat de visite inconnu : " + resultat + ".");
        }
        String cr = vide(p.compteRendu());
        if (!"FAIT".equals(resultat) && cr == null) {
            throw ErreurMetier.invalide("Dites en quelques mots pourquoi la visite n'a pas eu lieu.");
        }
        if (cr != null && cr.length() > 2000) {
            throw ErreurMetier.invalide("Le compte rendu d'une étape tient en 2 000 caractères au plus.");
        }
        OffsetDateTime le = p.pointeeLe() == null || p.pointeeLe().isAfter(OffsetDateTime.now().plusMinutes(5)) ? OffsetDateTime.now() : p.pointeeLe();
        Integer distance = tourneeRepository.pointer(idEtape, le, p.latitude(), p.longitude(), p.precision(), resultat, cr);
        tourneeRepository.demarrer(idTournee);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("distanceM", distance);
        r.put("eloigne", distance != null && distance > tourneeRepository.reglage("tournee.distance_pointage_m", 250));
        r.put("tournee", avecEtapes(tourneeRepository.trouver(idTournee).orElseThrow()));
        return r;
    }

    /** Termine la tournée avec son compte rendu ; les gestionnaires des bâtiments visités sont prévenus. */
    @Transactional
    public Map<String, Object> terminer(int id, String compteRendu, Utilisateur u) {
        JsonNode t = lisible(id, u);
        if (t.get("idAgent").asInt() != u.id() && !u.estDirection()) {
            throw ErreurMetier.refuse("Seul l'agent de la tournée la termine.");
        }
        String cr = vide(compteRendu);
        if (cr == null) {
            throw ErreurMetier.invalide("Écrivez le compte rendu de la tournée : ce qui a été fait, ce qui reste à faire.");
        }
        if (tourneeRepository.terminer(id, cr, u.signature()) == 0) {
            throw ErreurMetier.invalide("Cette tournée est déjà terminée.");
        }
        JsonNode apres = tourneeRepository.trouver(id).orElseThrow();
        String titre = "Compte rendu de tournée : " + apres.get("agent").asText();
        String message = apres.get("reference").asText() + " · " + apres.get("pointees").asInt() + " visite(s) sur "
                + apres.get("etapes").asInt() + " — " + (cr.length() > 140 ? cr.substring(0, 137) + "…" : cr);
        tourneeRepository.prevenirGestionnaires(id, apres.get("idAgent").asInt(), titre, message);
        journalService.consigner(u.signature(), OBJET, String.valueOf(id), "Tournée terminée : " + apres.get("reference").asText(), t, apres);
        return avecEtapes(apres);
    }

    // ------------------------------------------------------------------ contrôles
    private JsonNode lisible(int id, Utilisateur u) {
        exigerTerrain(u);
        JsonNode t = tourneeRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Tournée inconnue."));
        if (t.get("idAgent").asInt() != u.id() && !planificateur(u)) {
            throw ErreurMetier.introuvable("Tournée inconnue.");
        }
        return t;
    }

    private void visible(int idBatiment, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        batimentRepository.trouver(idBatiment, perimetre, params)
                .orElseThrow(() -> ErreurMetier.introuvable("Bâtiment inconnu ou hors de votre portefeuille."));
    }

    private Map<String, Object> avecEtapes(JsonNode t) {
        Map<String, Object> r = new LinkedHashMap<>();
        t.fields().forEachRemaining((e) -> r.put(e.getKey(), e.getValue()));
        r.put("etapesDetail", tourneeRepository.etapes(t.get("id").asInt()));
        r.put("depart", Map.of("latitude", tourneeRepository.reglage("agence.latitude", 5.36),
                "longitude", tourneeRepository.reglage("agence.longitude", -3.96)));
        return r;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
