package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.ReaffectationDto;
import com.eburtis.fact.repository.AffectationRepository;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.service.JournalService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Qui gère quoi (§ 4.3) : les portefeuilles des gestionnaires et la réaffectation, un bâtiment ou
 * plusieurs d'un coup. Lecture : droit affectation.lire ; réaffectation : affectation.ecrire
 * (Direction). L'historique est tenu par la base, quel que soit l'écran qui change le gestionnaire.
 */
@Service
public class AffectationFacade {

    private static final int MAXIMUM = 500;

    private final AffectationRepository affectationRepository;
    private final BatimentRepository batimentRepository;
    private final AlerteRepository alerteRepository;
    private final JournalService journalService;

    public AffectationFacade(AffectationRepository affectationRepository, BatimentRepository batimentRepository,
                             AlerteRepository alerteRepository, JournalService journalService) {
        this.affectationRepository = affectationRepository;
        this.batimentRepository = batimentRepository;
        this.alerteRepository = alerteRepository;
        this.journalService = journalService;
    }

    public Map<String, Object> portefeuilles(Utilisateur u) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("portefeuilles", affectationRepository.portefeuilles());
        r.put("batiments", affectationRepository.batiments());
        r.put("gestionnaires", batimentRepository.gestionnaires());
        r.put("historique", affectationRepository.historique(null, 60));
        r.put("peutReaffecter", u.peut(Profil.Droit.AFFECTATION_ECRIRE));
        return r;
    }

    public Object historique(Integer idBatiment) {
        return affectationRepository.historique(idBatiment, 100);
    }

    @Transactional
    public Map<String, Object> reaffecter(ReaffectationDto s, Utilisateur auteur) {
        if (!auteur.peut(Profil.Droit.AFFECTATION_ECRIRE)) {
            throw ErreurMetier.refuse("Seule la Direction réaffecte les bâtiments.");
        }
        List<Integer> batiments = s.batiments() == null ? List.of() : s.batiments().stream().distinct().toList();
        if (batiments.isEmpty()) {
            throw ErreurMetier.invalide("Sélectionnez au moins un bâtiment.");
        }
        if (batiments.size() > MAXIMUM) {
            throw ErreurMetier.invalide("Au plus " + MAXIMUM + " bâtiments par réaffectation.");
        }
        if (affectationRepository.inconnus(batiments) > 0) {
            throw ErreurMetier.invalide("Certains bâtiments sélectionnés n'existent plus ou sont archivés : rechargez la page.");
        }
        Integer g = s.idGestionnaire();
        if (g != null && !batimentRepository.estGestionnaire(g)) {
            throw ErreurMetier.invalide("Le nouveau gestionnaire doit être un compte gestionnaire actif.");
        }
        boolean changerSuppleant = Boolean.TRUE.equals(s.changerSuppleant());
        Integer sup = changerSuppleant ? s.idSuppleant() : null;
        if (sup != null) {
            if (g == null) {
                throw ErreurMetier.invalide("Un suppléant ne se désigne qu'avec un gestionnaire principal.");
            }
            if (sup.equals(g)) {
                throw ErreurMetier.invalide("Le suppléant doit être une autre personne que le gestionnaire.");
            }
            if (!batimentRepository.estGestionnaire(sup)) {
                throw ErreurMetier.invalide("Le suppléant doit être un compte gestionnaire actif.");
            }
        }
        String motif = s.motif() == null || s.motif().isBlank() ? null : s.motif().trim();
        if (motif != null && motif.length() > 250) {
            throw ErreurMetier.invalide("Le motif tient en 250 caractères.");
        }
        UUID operation = UUID.randomUUID();
        affectationRepository.preparerOperation(operation, motif);
        int n = affectationRepository.reaffecter(batiments, g, changerSuppleant, sup, auteur.signature());
        if (n > 0) {
            if (g != null && g != auteur.id()) {
                affectationRepository.prevenir(g, operation, n, "GESTIONNAIRE", auteur.signature(), motif);
            }
            if (sup != null && sup != auteur.id()) {
                affectationRepository.prevenir(sup, operation, n, "SUPPLEANT", auteur.signature(), motif);
            }
            journalService.consigner(auteur.signature(), "AFFECTATION", operation.toString(),
                    "Réaffectation de " + n + " bâtiment(s)" + (motif == null ? "" : " : " + motif), null,
                    null);
            alerteRepository.generer();
        }
        return Map.of("modifies", n, "selectionnes", batiments.size(), "operation", operation.toString());
    }
}
