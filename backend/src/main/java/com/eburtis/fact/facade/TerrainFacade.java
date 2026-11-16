package com.eburtis.fact.facade;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.presentation.dto.RecensementDto;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.repository.TerrainRepository;
import com.eburtis.fact.service.ReferentielService;
import com.eburtis.fact.service.StockagePhotos;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * L'application de terrain (§ 4.6) : un recensement saisi sur le téléphone, peut-être hors
 * connexion, arrive d'un bloc (bâtiment, lots, photos). Il est rejouable : la clé tirée sur le
 * téléphone garantit qu'un envoi interrompu puis repris ne crée pas deux bâtiments. La fiche
 * créée porte le statut « À valider » jusqu'au contrôle du gestionnaire.
 */
@Service
public class TerrainFacade {

    private static final int LOTS_MAX = 300;
    private static final int PHOTOS_MAX = 20;

    private final BatimentFacade batimentFacade;
    private final LotRepository lotRepository;
    private final TerrainRepository terrainRepository;
    private final ReferentielService referentielService;
    private final StockagePhotos stockagePhotos;
    private final com.eburtis.fact.service.PerimetreService perimetreService;
    private final TourneeFacade tourneeFacade;

    public TerrainFacade(BatimentFacade batimentFacade, LotRepository lotRepository, TerrainRepository terrainRepository,
                         ReferentielService referentielService, StockagePhotos stockagePhotos,
                         com.eburtis.fact.service.PerimetreService perimetreService, TourneeFacade tourneeFacade) {
        this.perimetreService = perimetreService;
        this.tourneeFacade = tourneeFacade;
        this.batimentFacade = batimentFacade;
        this.lotRepository = lotRepository;
        this.terrainRepository = terrainRepository;
        this.referentielService = referentielService;
        this.stockagePhotos = stockagePhotos;
    }

    /** Ce que le téléphone garde pour travailler sans réseau. */
    public Map<String, Object> preparation(Utilisateur u) {
        if (!u.peut(Profil.Droit.PATRIMOINE_ECRIRE) && !u.peut(Profil.Droit.TERRAIN)) {
            throw ErreurMetier.refuse("L'application de terrain est réservée aux agents et aux gestionnaires.");
        }
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("proprietaires", terrainRepository.proprietairesPourTerrain());
        r.put("proprietaireAPreciser", terrainRepository.proprietaireAPreciser().orElse(0));
        java.util.List<Object> params = new java.util.ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        r.put("batiments", terrainRepository.batimentsPourTerrain(perimetre, params));
        if (u.peut(Profil.Droit.LOCATION_LIRE)) {
            java.util.List<Object> p2 = new java.util.ArrayList<>();
            r.put("baux", terrainRepository.bauxPourTerrain(perimetreService.batiments(u, p2), p2));
        }
        r.put("tournee", tourneeFacade.maTournee(u));
        r.put("droits", Map.of("encaisser", u.peut(Profil.Droit.ENCAISSEMENT_ECRIRE),
                "etatDesLieux", u.peut(Profil.Droit.LOCATION_LIRE) && (u.peut(Profil.Droit.TERRAIN) || u.peut(Profil.Droit.LOCATION_ECRIRE)),
                "recenser", u.peut(Profil.Droit.PATRIMOINE_ECRIRE)));
        return r;
    }

    @Transactional
    public Map<String, Object> recevoir(RecensementDto r, List<MultipartFile> photos, Utilisateur u) {
        exiger(u);
        if (r.cle() == null) {
            throw ErreurMetier.invalide("Recensement sans identifiant : mettez l'application à jour.");
        }
        var deja = terrainRepository.dejaRecu(r.cle());
        if (deja.isPresent()) {
            // L'envoi précédent avait abouti ; le téléphone n'en a pas eu la réponse.
            return Map.of("idBatiment", deja.get(), "deja", true);
        }
        List<RecensementDto.LotTerrain> lots = r.lots() == null ? List.of() : r.lots();
        if (lots.size() > LOTS_MAX) {
            throw ErreurMetier.invalide("Au plus " + LOTS_MAX + " lots par recensement.");
        }
        if (photos != null && photos.size() > PHOTOS_MAX) {
            throw ErreurMetier.invalide("Au plus " + PHOTOS_MAX + " photos par recensement.");
        }
        Set<String> numeros = new HashSet<>();
        for (RecensementDto.LotTerrain l : lots) {
            String n = l.numero() == null ? "" : l.numero().trim();
            if (n.isEmpty()) {
                throw ErreurMetier.invalide("Chaque lot a un numéro de porte.");
            }
            if (!numeros.add(n.toLowerCase(Locale.ROOT))) {
                throw ErreurMetier.invalide("Le numéro de porte « " + n + " » est saisi deux fois.");
            }
            referentielService.exiger("TYPE_LOT", l.type(), "Type du lot " + n);
            referentielService.exiger("STATUT_LOT", l.statut() == null ? "VACANT" : l.statut(), "Statut du lot " + n);
            if (l.etage() != null && (l.etage() < -5 || l.etage() > 60)) {
                throw ErreurMetier.invalide("Étage du lot " + n + " : entre -5 et 60.");
            }
            if ((l.pieces() != null && l.pieces() < 0) || (l.surface() != null && l.surface() <= 0)) {
                throw ErreurMetier.invalide("Pièces et surface du lot " + n + " : des nombres positifs.");
            }
        }
        Integer proprietaire = r.idProprietaire() != null ? r.idProprietaire() : terrainRepository.proprietaireAPreciser().orElse(null);
        String description = r.description();
        if (r.precisionGps() != null) {
            String note = "Position relevée sur le terrain à ± " + Math.round(r.precisionGps()) + " m.";
            description = description == null || description.isBlank() ? note : description.trim() + "\n" + note;
        }
        int id = batimentFacade.creerDepuisTerrain(new BatimentSaisieDto(null, r.nom(), r.type(), r.adresse(), r.idCommune(),
                r.idQuartier(), r.latitude(), r.longitude(), r.precisionGps(), r.niveaux(), null, description, proprietaire, null, null), u);
        for (RecensementDto.LotTerrain l : lots) {
            lotRepository.creer(new LotSaisieDto(null, id, l.numero().trim(), l.etage() == null ? 0 : l.etage(), l.type(), l.surface(),
                    l.pieces(), null, null, List.of(), null, null, l.statut() == null ? "VACANT" : l.statut(),
                    l.description() == null || l.description().isBlank() ? null : l.description().trim()), u.signature());
        }
        int n = 0;
        if (photos != null) {
            for (MultipartFile f : photos) {
                try {
                    var rangee = stockagePhotos.enregistrer("batiments", String.valueOf(id), f.getBytes());
                    terrainRepository.ajouterPhoto(id, rangee.chemin(), rangee.largeur(), rangee.hauteur(), r.saisiLe(), u.signature());
                    n++;
                } catch (IOException e) {
                    throw ErreurMetier.invalide("Une photo n'a pas pu être lue : reprenez-la.");
                }
            }
        }
        terrainRepository.consigner(r.cle(), id, r.saisiLe(), u.signature());
        return Map.of("idBatiment", id, "lots", lots.size(), "photos", n, "deja", false);
    }

    private static void exiger(Utilisateur u) {
        if (!u.peut(Profil.Droit.PATRIMOINE_ECRIRE)) {
            throw ErreurMetier.refuse("Le recensement demande le droit de saisir le patrimoine.");
        }
    }
}
