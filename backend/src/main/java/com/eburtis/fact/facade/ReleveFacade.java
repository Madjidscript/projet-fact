package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.RapportFacade.Periode;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.repository.ReleveRepository;
import com.eburtis.fact.service.ReleveService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

/**
 * Le relevé de gestion d'un propriétaire. Il porte des montants dus au mandant : seuls la
 * Direction et le propriétaire lui-même (espace propriétaire) peuvent l'éditer.
 */
@Service
public class ReleveFacade {

    private final ReleveRepository releveRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final ReleveService releveService;

    public ReleveFacade(ReleveRepository releveRepository, ProprietaireRepository proprietaireRepository, ReleveService releveService) {
        this.releveRepository = releveRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.releveService = releveService;
    }

    /** Les chiffres du relevé, pour l'écran : biens, lots, travaux et totaux. */
    public Map<String, Object> donnees(int id, Periode p, Utilisateur u) {
        JsonNode proprietaire = autorise(id, u);
        JsonNode r = releveRepository.releve(id, p.du(), p.au());
        ReleveService.Totaux t = ReleveService.totaux(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("proprietaire", proprietaire);
        m.put("periode", Map.of("du", p.du().toString(), "au", p.au().toString(), "libelle", p.libelle()));
        m.put("totaux", t);
        m.put("batiments", r.get("batiments"));
        m.put("lots", r.get("lots"));
        m.put("travaux", r.get("travaux"));
        return m;
    }

    /** Le PDF et son nom de fichier. */
    public Map.Entry<String, byte[]> pdf(int id, Periode p, Utilisateur u) {
        JsonNode proprietaire = autorise(id, u);
        JsonNode r = releveRepository.releve(id, p.du(), p.au());
        String nom = "releve-" + proprietaire.get("reference").asText() + "-" + p.du().toString().substring(0, 7)
                + (p.du().equals(p.au()) ? "" : "-" + p.au().toString().substring(0, 7)) + ".pdf";
        return Map.entry(nom, releveService.pdf(proprietaire, r, p.libelle(), p.du(), p.au()));
    }

    private JsonNode autorise(int id, Utilisateur u) {
        boolean lui = proprietaireRepository.compteDe(id).map(c -> c == u.id()).orElse(false);
        if (!u.estDirection() && !lui) {
            throw ErreurMetier.refuse("Le relevé de gestion est réservé à la Direction et au propriétaire.");
        }
        return proprietaireRepository.trouver(id, true).orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
    }
}
