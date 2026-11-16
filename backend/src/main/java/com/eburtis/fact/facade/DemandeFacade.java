package com.eburtis.fact.facade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.SuiviDemandeDto;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.DemandeRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.StockagePhotos;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Le suivi des demandes du site (§ 3.8) : un tableau unique, des statuts communs. La Direction et
 * l'éditeur web voient tout ; un gestionnaire voit les demandes qui lui sont confiées et celles qui
 * portent sur ses bâtiments. La boîte d'envoi garde les messages à transmettre aux visiteurs.
 */
@Service
public class DemandeFacade {

    public static final String OBJET = "DEMANDE";
    private static final Set<String> STATUTS = Set.of("NOUVELLE", "PRISE_EN_CHARGE", "RENDEZ_VOUS", "CONCLUE", "SANS_SUITE");

    private final DemandeRepository demandeRepository;
    private final BatimentRepository batimentRepository;
    private final PerimetreService perimetreService;
    private final StockagePhotos stockage;
    private final JournalService journalService;
    private final AlerteRepository alerteRepository;

    public DemandeFacade(DemandeRepository demandeRepository, BatimentRepository batimentRepository, PerimetreService perimetreService,
                         StockagePhotos stockage, JournalService journalService, AlerteRepository alerteRepository) {
        this.demandeRepository = demandeRepository;
        this.batimentRepository = batimentRepository;
        this.perimetreService = perimetreService;
        this.stockage = stockage;
        this.journalService = journalService;
        this.alerteRepository = alerteRepository;
    }

    /** Voit toutes les demandes : Direction, éditeur web, administrateur. */
    public static boolean voitTout(Utilisateur u) {
        return u.peut(Profil.Droit.DEMANDE_LIRE) || u.peut(Profil.Droit.DEMANDE_ECRIRE);
    }

    public static boolean voit(Utilisateur u) {
        return voitTout(u) || u.peut(Profil.Droit.ANNONCE_ECRIRE);
    }

    public Map<String, Object> lister(Utilisateur u) {
        exigerLecture(u);
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("demandes", demandeRepository.lister(voitTout(u), u.id(), perimetre, params));
        r.put("messagesEnAttente", voitTout(u) ? demandeRepository.compterMessagesEnAttente() : 0);
        r.put("comptes", voitTout(u) || u.peut(Profil.Droit.DEMANDE_ECRIRE) ? batimentRepository.gestionnaires() : null);
        return r;
    }

    public Map<String, Object> fiche(int id, Utilisateur u) {
        JsonNode d = accessible(id, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("demande", d);
        r.put("pieces", demandeRepository.pieces(id));
        r.put("peutModifier", peutModifier(d, u));
        return r;
    }

    @Transactional
    public Map<String, Object> suivre(int id, SuiviDemandeDto s, Utilisateur u) {
        JsonNode avant = accessible(id, u);
        if (!peutModifier(avant, u)) {
            throw ErreurMetier.refuse("Vous pouvez lire cette demande, pas la modifier.");
        }
        String statut = s.statut() == null ? avant.get("statut").asText() : s.statut();
        if (!STATUTS.contains(statut)) {
            throw ErreurMetier.invalide("Statut inconnu.");
        }
        if ("RENDEZ_VOUS".equals(statut) && s.rdvLe() == null) {
            throw ErreurMetier.invalide("Indiquez la date et l'heure du rendez-vous.");
        }
        Integer compte = avant.hasNonNull("idCompte") ? avant.get("idCompte").asInt() : null;
        if (s.idCompte() != null && !s.idCompte().equals(compte)) {
            if (!u.peut(Profil.Droit.DEMANDE_ECRIRE)) {
                throw ErreurMetier.refuse("Seule la Direction confie une demande à quelqu'un d'autre.");
            }
            compte = s.idCompte();
        }
        // Qui prend en charge une demande sans responsable en devient le responsable.
        if (compte == null && !"NOUVELLE".equals(statut)) {
            compte = u.id();
        }
        String note = s.note() == null || s.note().isBlank() ? null : s.note().trim();
        demandeRepository.suivre(id, statut, note, compte, "RENDEZ_VOUS".equals(statut) ? s.rdvLe() : null, u.signature());
        JsonNode apres = demandeRepository.trouver(id).orElseThrow();
        journalService.consigner(u.signature(), OBJET, String.valueOf(id), "Demande : " + apres.get("libelleStatut").asText(), avant, apres);
        alerteRepository.generer();
        return fiche(id, u);
    }

    public Map.Entry<Object[], byte[]> piece(int idPiece, Utilisateur u) {
        Object[] p = demandeRepository.piece(idPiece).orElseThrow(() -> ErreurMetier.introuvable("Pièce introuvable."));
        accessible((Integer) p[3], u);
        return Map.entry(p, stockage.lire((String) p[0]));
    }

    // ------------------------------------------------------------------ boîte d'envoi
    public JsonNode messages(Utilisateur u) {
        exigerTout(u);
        return demandeRepository.messagesEnAttente();
    }

    @Transactional
    public Map<String, Object> marquerMessage(int id, boolean envoye, Utilisateur u) {
        exigerTout(u);
        int n = demandeRepository.marquerMessage(id, envoye, u.signature());
        if (n == 0) {
            throw ErreurMetier.invalide("Ce message est déjà traité.");
        }
        return Map.of("restants", demandeRepository.compterMessagesEnAttente());
    }

    // ------------------------------------------------------------------ contrôles
    private JsonNode accessible(int id, Utilisateur u) {
        exigerLecture(u);
        JsonNode d = demandeRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Demande inconnue."));
        if (voitTout(u)) {
            return d;
        }
        boolean lui = d.hasNonNull("idCompte") && d.get("idCompte").asInt() == u.id();
        boolean sonBatiment = false;
        if (d.hasNonNull("idBatiment")) {
            List<Object> params = new ArrayList<>();
            String perimetre = perimetreService.batiments(u, params);
            sonBatiment = batimentRepository.trouver(d.get("idBatiment").asInt(), perimetre, params).isPresent();
        }
        if (!lui && !sonBatiment) {
            throw ErreurMetier.introuvable("Demande inconnue.");
        }
        return d;
    }

    private static boolean peutModifier(JsonNode d, Utilisateur u) {
        return u.peut(Profil.Droit.DEMANDE_ECRIRE) || u.peut(Profil.Droit.ANNONCE_ECRIRE);
    }

    private static void exigerLecture(Utilisateur u) {
        if (!voit(u)) {
            throw ErreurMetier.refuse("Le suivi des demandes est réservé à l'agence.");
        }
    }

    private static void exigerTout(Utilisateur u) {
        if (!voitTout(u)) {
            throw ErreurMetier.refuse("La boîte d'envoi est réservée à la Direction et à l'éditeur web.");
        }
    }
}
