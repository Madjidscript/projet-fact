package com.eburtis.fact.facade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.MandatSaisieDto;
import com.eburtis.fact.presentation.dto.ResiliationDto;
import com.eburtis.fact.repository.MandatRepository;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.ReferentielService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les mandats confiés par les propriétaires : type, durée, taux d'honoraires, seuil de travaux.
 * Ce sont des engagements de l'agence : seule la Direction les crée, les modifie ou les résilie.
 */
@Service
public class MandatFacade {

    public static final String OBJET = "MANDAT";

    private final MandatRepository mandatRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final ReferentielService referentielService;
    private final JournalService journalService;
    private final com.eburtis.fact.service.StockagePhotos stockage;

    public MandatFacade(MandatRepository mandatRepository, ProprietaireRepository proprietaireRepository,
                        ReferentielService referentielService, JournalService journalService,
                        com.eburtis.fact.service.StockagePhotos stockage) {
        this.stockage = stockage;
        this.mandatRepository = mandatRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.referentielService = referentielService;
        this.journalService = journalService;
    }

    @Transactional
    public JsonNode enregistrer(MandatSaisieDto saisie, Utilisateur auteur) {
        exigerDirection(auteur);
        MandatSaisieDto s = controler(saisie);
        int id;
        JsonNode avant = null;
        if (s.id() == null) {
            id = mandatRepository.creer(s, auteur.signature());
        } else {
            avant = mandatRepository.trouver(s.id()).orElseThrow(() -> ErreurMetier.introuvable("Mandat inconnu."));
            if (avant.get("idProprietaire").asInt() != s.idProprietaire()) {
                throw ErreurMetier.invalide("Un mandat ne change pas de propriétaire : créez-en un nouveau.");
            }
            if (!avant.get("resilieLe").isNull()) {
                throw ErreurMetier.invalide("Ce mandat est résilié : créez-en un nouveau.");
            }
            id = s.id();
            mandatRepository.modifier(s, auteur.signature());
        }
        mandatRepository.poserBatiments(id, Boolean.TRUE.equals(s.tousLesBiens()) ? List.of() : s.batiments());
        JsonNode apres = mandatRepository.trouver(id).orElseThrow();
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id),
                avant == null ? "Mandat créé" : "Mandat modifié", avant, apres);
        return apres;
    }

    @Transactional
    public JsonNode resilier(int id, ResiliationDto r, Utilisateur auteur) {
        exigerDirection(auteur);
        JsonNode avant = mandatRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Mandat inconnu."));
        if (!avant.get("resilieLe").isNull()) {
            throw ErreurMetier.invalide("Ce mandat est déjà résilié.");
        }
        LocalDate date = r.date() == null ? LocalDate.now() : r.date();
        if (date.isBefore(LocalDate.parse(avant.get("dateDebut").asText()))) {
            throw ErreurMetier.invalide("La résiliation ne peut pas précéder le début du mandat.");
        }
        String motif = r.motif() == null || r.motif().isBlank() ? null : r.motif().trim();
        if (motif == null) {
            throw ErreurMetier.invalide("Indiquez le motif de la résiliation.");
        }
        mandatRepository.resilier(id, date, motif, auteur.signature());
        JsonNode apres = mandatRepository.trouver(id).orElseThrow();
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Mandat résilié : " + motif, avant, apres);
        return apres;
    }

    /** Le mandat signé, en PDF (§ 4.2, onglet « Propriétaire et mandat ») : déposé par la Direction. */
    @Transactional
    public JsonNode deposerDocument(int id, org.springframework.web.multipart.MultipartFile f, Utilisateur auteur) {
        exigerDirection(auteur);
        mandatRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Mandat inconnu."));
        try {
            byte[] o = f.getBytes();
            if (o.length > 15 * 1024 * 1024) {
                throw ErreurMetier.invalide("Le document dépasse 15 Mo.");
            }
            if (o.length < 5 || !new String(o, 0, 5).equals("%PDF-")) {
                throw ErreurMetier.invalide("Déposez le mandat signé au format PDF.");
            }
            String chemin = stockage.enregistrerFichier("mandats", String.valueOf(id), o, "pdf");
            String nom = f.getOriginalFilename() == null ? "mandat.pdf" : f.getOriginalFilename().replaceAll("[\\\\/:*?\"<>|]", "_");
            mandatRepository.poserDocument(id, chemin, nom.length() > 200 ? nom.substring(0, 200) : nom, auteur.signature());
        } catch (java.io.IOException e) {
            throw ErreurMetier.invalide("Le document n'a pas pu être lu.");
        }
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Document du mandat déposé", null, null);
        return mandatRepository.trouver(id).orElseThrow();
    }

    /** Le document du mandat : pour la Direction, le gestionnaire et le propriétaire lui-même. */
    public java.util.Map.Entry<String, byte[]> document(int id, Utilisateur u) {
        Object[] d = mandatRepository.document(id).orElseThrow(() -> ErreurMetier.introuvable("Aucun document pour ce mandat."));
        boolean lui = proprietaireRepository.compteDe((Integer) d[2]).map(c -> c == u.id()).orElse(false);
        if (!lui && !u.estDirection() && !u.peut(com.eburtis.fact.domain.Profil.Droit.PATRIMOINE_ECRIRE)) {
            throw ErreurMetier.introuvable("Aucun document pour ce mandat.");
        }
        return java.util.Map.entry((String) d[1], stockage.lire((String) d[0]));
    }

    private MandatSaisieDto controler(MandatSaisieDto s) {
        if (s.idProprietaire() == null || proprietaireRepository.trouver(s.idProprietaire(), false).isEmpty()) {
            throw ErreurMetier.invalide("Le propriétaire est obligatoire.");
        }
        String type = s.type() == null || s.type().isBlank() ? "GESTION_LOCATIVE" : s.type();
        referentielService.exiger("TYPE_MANDAT", type, "Type de mandat");
        if (s.dateDebut() == null) {
            throw ErreurMetier.invalide("La date de début du mandat est obligatoire.");
        }
        int duree = s.dureeMois() == null ? 12 : s.dureeMois();
        if (duree < 1 || duree > 240) {
            throw ErreurMetier.invalide("La durée du mandat va de 1 à 240 mois.");
        }
        BigDecimal taux = s.tauxHonoraires();
        if (taux == null || taux.signum() < 0 || taux.compareTo(BigDecimal.valueOf(50)) > 0) {
            throw ErreurMetier.invalide("Le taux d'honoraires est obligatoire, entre 0 et 50 %.");
        }
        if (s.seuilTravaux() != null && s.seuilTravaux() <= 0) {
            throw ErreurMetier.invalide("Le seuil de travaux doit être positif, ou laissé vide.");
        }
        boolean tous = s.tousLesBiens() == null || s.tousLesBiens();
        List<Integer> batiments = s.batiments() == null ? List.of() : s.batiments().stream().distinct().toList();
        if (!tous) {
            if (batiments.isEmpty()) {
                throw ErreurMetier.invalide("Cochez au moins un bâtiment couvert par ce mandat.");
            }
            if (mandatRepository.batimentsDuProprietaire(s.idProprietaire(), batiments) != batiments.size()) {
                throw ErreurMetier.invalide("Un mandat ne couvre que des bâtiments de ce propriétaire.");
            }
        }
        String notes = s.notes() == null || s.notes().isBlank() ? null : s.notes().trim();
        return new MandatSaisieDto(s.id(), s.idProprietaire(), type, s.dateDebut(), duree,
                s.reconductionTacite() == null || s.reconductionTacite(), taux, s.seuilTravaux(), tous, batiments, notes);
    }

    private static void exigerDirection(Utilisateur u) {
        if (!u.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction gère les mandats.");
        }
    }
}
