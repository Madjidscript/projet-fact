package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.repository.EspaceRepository;
import com.eburtis.fact.repository.InterventionRepository;
import com.eburtis.fact.repository.MandatRepository;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.service.JournalService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * L'espace propriétaire (§ 4.9). Le propriétaire n'y voit que ses biens (recette du § 9), sans les
 * données personnelles de ses locataires ; il y répond en ligne aux devis qui dépassent le seuil de
 * son mandat. La Direction peut ouvrir l'espace d'un propriétaire pour l'accompagner.
 */
@Service
public class EspaceFacade {

    private final EspaceRepository espaceRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final MandatRepository mandatRepository;
    private final InterventionRepository interventionRepository;
    private final AlerteRepository alerteRepository;
    private final JournalService journalService;

    public EspaceFacade(EspaceRepository espaceRepository, ProprietaireRepository proprietaireRepository, MandatRepository mandatRepository,
                        InterventionRepository interventionRepository, AlerteRepository alerteRepository, JournalService journalService) {
        this.espaceRepository = espaceRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.mandatRepository = mandatRepository;
        this.interventionRepository = interventionRepository;
        this.alerteRepository = alerteRepository;
        this.journalService = journalService;
    }

    /** Le propriétaire dont l'espace s'ouvre : le sien, ou celui choisi par la Direction. */
    public int proprietaire(Utilisateur u, Integer choisi) {
        if (u.profil() == Profil.PROPRIETAIRE) {
            return espaceRepository.proprietaireDuCompte(u.id())
                    .orElseThrow(() -> ErreurMetier.introuvable("Votre compte n'est rattaché à aucune fiche de propriétaire : contactez l'agence."));
        }
        if (u.estDirection() && choisi != null) {
            return choisi;
        }
        throw ErreurMetier.refuse("L'espace propriétaire est réservé aux propriétaires.");
    }

    public Map<String, Object> espace(Utilisateur u, Integer choisi) {
        int id = proprietaire(u, choisi);
        JsonNode p = proprietaireRepository.trouver(id, true).orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> qui = new LinkedHashMap<>();
        for (String k : new String[]{"id", "reference", "nomComplet", "telephone", "email", "reversementMode", "reversementCoordonnees"}) {
            qui.put(k, p.get(k));
        }
        r.put("proprietaire", qui);
        r.putAll(toMap(espaceRepository.espace(id)));
        r.put("mandats", mandatRepository.duProprietaire(id));
        r.put("apercu", u.profil() != Profil.PROPRIETAIRE);
        return r;
    }

    /** Accord ou refus du propriétaire sur un devis qui dépasse le seuil de son mandat. */
    @Transactional
    public Map<String, Object> repondre(int idIntervention, boolean accord, String motif, Utilisateur u) {
        if (u.profil() != Profil.PROPRIETAIRE) {
            throw ErreurMetier.refuse("Seul le propriétaire répond, depuis son espace.");
        }
        int id = proprietaire(u, null);
        JsonNode i = espaceRepository.interventionPourAccord(idIntervention).orElseThrow(() -> ErreurMetier.introuvable("Intervention inconnue."));
        if (i.get("idProprietaire").asInt() != id) {
            throw ErreurMetier.introuvable("Intervention inconnue.");
        }
        if (!"EN_COURS".equals(i.get("statut").asText()) || i.get("devis").isNull() || i.get("devis").asInt() <= i.get("seuil").asInt()) {
            throw ErreurMetier.invalide("Ce devis n'attend pas votre accord.");
        }
        if (!i.get("accordDecision").isNull()) {
            throw ErreurMetier.invalide("Vous avez déjà répondu à ce devis.");
        }
        String m = motif == null || motif.isBlank() ? null : motif.trim();
        if (!accord && m == null) {
            throw ErreurMetier.invalide("Dites pourquoi vous refusez : l'agence vous proposera une autre solution.");
        }
        if (m != null && m.length() > 250) {
            throw ErreurMetier.invalide("250 caractères au plus.");
        }
        String decision = accord ? "ACCORD" : "REFUS";
        if (interventionRepository.repondre(idIntervention, decision, m, u.signature()) == 0) {
            throw ErreurMetier.invalide("Ce devis n'attend plus votre réponse.");
        }
        String titre = (accord ? "Devis accepté par le propriétaire : " : "Devis refusé par le propriétaire : ") + i.get("titre").asText();
        alerteRepository.prevenirReponseDevis(idIntervention, decision, titre,
                i.get("reference").asText() + ", " + i.get("batiment").asText() + (m == null ? "." : " — « " + m + " »"), i.get("idBatiment").asInt());
        journalService.consigner(u.signature(), "INTERVENTION", String.valueOf(idIntervention),
                accord ? "Accord du propriétaire sur le devis" : "Refus du propriétaire : " + m, null, null);
        alerteRepository.generer();
        return Map.of("decision", decision);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode n) {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(n, Map.class);
    }
}
