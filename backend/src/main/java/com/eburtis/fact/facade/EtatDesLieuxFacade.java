package com.eburtis.fact.facade;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.EtatDesLieuxDto;
import com.eburtis.fact.repository.EtatDesLieuxRepository;
import com.eburtis.fact.service.EtatDesLieuxService;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.StockagePhotos;
import com.eburtis.fact.utils.ImageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * L'état des lieux (§ 4.6) : pièce par pièce, l'état de chaque élément, des photos, et la
 * signature au doigt de l'occupant et de l'agent. Un seul état des lieux d'entrée et un seul de
 * sortie par bail ; l'envoi depuis le téléphone est rejouable sans doublon (clé du téléphone).
 */
@Service
public class EtatDesLieuxFacade {

    public static final String OBJET = "ETAT_DES_LIEUX";
    public static final Set<String> ETATS = Set.of("BON", "USAGE", "MAUVAIS", "HORS_SERVICE");
    private static final int PIECES_MAX = 30;
    private static final int PHOTOS_MAX = 60;
    private static final Pattern NOM_PHOTO = Pattern.compile("^p(\\d{1,2})-");

    private final EtatDesLieuxRepository repository;
    private final EtatDesLieuxService service;
    private final PerimetreService perimetreService;
    private final StockagePhotos stockagePhotos;
    private final JournalService journalService;
    private final ObjectMapper mapper;

    public EtatDesLieuxFacade(EtatDesLieuxRepository repository, EtatDesLieuxService service, PerimetreService perimetreService,
                              StockagePhotos stockagePhotos, JournalService journalService, ObjectMapper mapper) {
        this.repository = repository;
        this.service = service;
        this.perimetreService = perimetreService;
        this.stockagePhotos = stockagePhotos;
        this.journalService = journalService;
        this.mapper = mapper;
    }

    private static void exigerSaisie(Utilisateur u) {
        if (!u.peut(Profil.Droit.LOCATION_LIRE) || !(u.peut(Profil.Droit.TERRAIN) || u.peut(Profil.Droit.LOCATION_ECRIRE))) {
            throw ErreurMetier.refuse("Les états des lieux sont faits par les agents de terrain et les gestionnaires.");
        }
    }

    private static void exigerLecture(Utilisateur u) {
        if (!u.peut(Profil.Droit.LOCATION_LIRE) || u.profil() == Profil.PROPRIETAIRE) {
            throw ErreurMetier.refuse("Les états des lieux sont réservés à l'agence.");
        }
    }

    private JsonNode bail(int idBail, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return repository.bail(idBail, perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Bail inconnu."));
    }

    @Transactional
    public Map<String, Object> recevoir(EtatDesLieuxDto s, MultipartFile signatureOccupant, MultipartFile signatureAgent,
                                        List<MultipartFile> photos, Utilisateur u) {
        exigerSaisie(u);
        if (s.cle() == null) {
            throw ErreurMetier.invalide("État des lieux sans identifiant : mettez l'application à jour.");
        }
        var deja = repository.parCle(s.cle());
        if (deja.isPresent()) {
            // Le téléphone renvoie un état des lieux déjà reçu : on rend le même, sans doublon.
            return Map.of("etatDesLieux", repository.trouver(deja.get()).orElseThrow(), "deja", true);
        }
        if (s.idBail() == null) {
            throw ErreurMetier.invalide("Le bail est obligatoire.");
        }
        JsonNode b = bail(s.idBail(), u);
        String type = s.type() == null ? "" : s.type();
        if (!type.equals("ENTREE") && !type.equals("SORTIE")) {
            throw ErreurMetier.invalide("Précisez s'il s'agit d'un état des lieux d'entrée ou de sortie.");
        }
        String statut = b.get("statut").asText();
        if (!Set.of("ACTIF", "PREAVIS").contains(statut)
                && !(type.equals("SORTIE") && b.hasNonNull("dateSortie") && !LocalDate.parse(b.get("dateSortie").asText()).isBefore(LocalDate.now().minusDays(30)))) {
            throw ErreurMetier.invalide("Ce bail est terminé : l'état des lieux ne peut plus être fait.");
        }
        repository.duBail(s.idBail(), type).ifPresent((x) -> {
            throw ErreurMetier.invalide("Un état des lieux " + (type.equals("ENTREE") ? "d'entrée" : "de sortie") + " existe déjà pour ce bail ("
                    + x.get("reference").asText() + ").");
        });
        List<EtatDesLieuxDto.Piece> pieces = s.pieces() == null ? List.of() : s.pieces();
        if (pieces.isEmpty()) {
            throw ErreurMetier.invalide("Décrivez au moins une pièce.");
        }
        if (pieces.size() > PIECES_MAX) {
            throw ErreurMetier.invalide("Au plus " + PIECES_MAX + " pièces par état des lieux.");
        }
        List<Map<String, Object>> propres = new ArrayList<>();
        for (EtatDesLieuxDto.Piece p : pieces) {
            String nom = p.piece() == null ? "" : p.piece().trim();
            if (nom.isEmpty()) {
                throw ErreurMetier.invalide("Chaque pièce a besoin d'un nom.");
            }
            List<Map<String, Object>> elements = new ArrayList<>();
            for (EtatDesLieuxDto.Element e : p.elements() == null ? List.<EtatDesLieuxDto.Element>of() : p.elements()) {
                if (e.nom() == null || e.nom().isBlank()) {
                    continue;
                }
                if (e.etat() == null || !ETATS.contains(e.etat())) {
                    throw ErreurMetier.invalide("Indiquez l'état de « " + e.nom().trim() + " » (" + nom + ").");
                }
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("nom", e.nom().trim());
                x.put("etat", e.etat());
                x.put("note", vide(e.note()));
                elements.add(x);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("piece", nom);
            m.put("elements", elements);
            m.put("note", vide(p.note()));
            propres.add(m);
        }
        String signataire = vide(s.signataire());
        if (signataire == null) {
            throw ErreurMetier.invalide("Écrivez le nom de la personne qui signe pour l'occupant.");
        }
        byte[] so = lireSignature(signatureOccupant, "de l'occupant");
        byte[] sa = lireSignature(signatureAgent, "de l'agent");
        if (photos != null && photos.size() > PHOTOS_MAX) {
            throw ErreurMetier.invalide("Au plus " + PHOTOS_MAX + " photos par état des lieux.");
        }
        Map<String, String> compteurs = new LinkedHashMap<>();
        if (s.compteurs() != null) {
            s.compteurs().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    compteurs.put(k.trim(), v.trim());
                }
            });
        }
        OffsetDateTime faitLe = s.faitLe() == null || s.faitLe().isAfter(OffsetDateTime.now().plusMinutes(5)) ? OffsetDateTime.now() : s.faitLe();
        String objet = "bail-" + s.idBail();
        String cheminSo = stockagePhotos.enregistrerFichier("edl", objet, so, "png");
        String cheminSa = stockagePhotos.enregistrerFichier("edl", objet, sa, "png");
        int id;
        try {
            id = repository.creer(s.cle(), s.idBail(), type, faitLe, mapper.writeValueAsString(propres), mapper.writeValueAsString(compteurs),
                    s.clesRemises(), vide(s.observations()), signataire, cheminSo, cheminSa, s.latitude(), s.longitude(), u.signature());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (id == 0) {
            // Le même envoi est arrivé deux fois en même temps : l'autre l'a déjà enregistré.
            return Map.of("etatDesLieux", repository.trouver(repository.parCle(s.cle()).orElseThrow()).orElseThrow(), "deja", true);
        }
        int ordre = 0;
        for (MultipartFile f : photos == null ? List.<MultipartFile>of() : photos) {
            Matcher m = NOM_PHOTO.matcher(f.getOriginalFilename() == null ? "" : f.getOriginalFilename());
            int rang = m.find() ? Integer.parseInt(m.group(1)) : -1;
            String piece = rang >= 0 && rang < propres.size() ? (String) propres.get(rang).get("piece") : "Général";
            try {
                var r = stockagePhotos.enregistrer("edl", objet, f.getBytes());
                repository.ajouterPhoto(id, piece, r.chemin(), ordre++);
            } catch (IOException e) {
                throw ErreurMetier.invalide("Une photo n'a pas pu être lue : renvoyez l'état des lieux.");
            }
        }
        JsonNode edl = repository.trouver(id).orElseThrow();
        journalService.consigner(u.signature(), OBJET, String.valueOf(id), "État des lieux " + (type.equals("ENTREE") ? "d'entrée " : "de sortie ")
                + edl.get("reference").asText() + " : " + b.get("batiment").asText() + ", lot " + b.get("numeroLot").asText()
                + ", signé par " + signataire, null, edl);
        return Map.of("etatDesLieux", edl, "deja", false);
    }

    private static byte[] lireSignature(MultipartFile f, String qui) {
        try {
            if (f == null || f.isEmpty() || ImageUtils.lire(f.getBytes()) == null) {
                throw ErreurMetier.invalide("La signature " + qui + " manque.");
            }
            if (f.getSize() > 2_000_000) {
                throw ErreurMetier.invalide("La signature " + qui + " est trop lourde.");
            }
            return f.getBytes();
        } catch (IOException e) {
            throw ErreurMetier.invalide("La signature " + qui + " n'a pas pu être lue.");
        }
    }

    // ------------------------------------------------------------------ lecture
    public JsonNode duBail(int idBail, Utilisateur u) {
        exigerLecture(u);
        bail(idBail, u);
        return repository.listeDuBail(idBail);
    }

    public JsonNode etatDesLieux(int id, Utilisateur u) {
        exigerLecture(u);
        JsonNode e = repository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("État des lieux inconnu."));
        bail(e.get("idBail").asInt(), u);
        return e;
    }

    public Map.Entry<String, byte[]> pdf(int id, Utilisateur u) {
        JsonNode e = etatDesLieux(id, u);
        JsonNode entree = "SORTIE".equals(e.get("type").asText()) ? repository.duBail(e.get("idBail").asInt(), "ENTREE").orElse(null) : null;
        byte[] pdf = service.pdf(e, entree, repository.cheminsPhotos(id));
        return Map.entry("etat-des-lieux_" + e.get("reference").asText() + ".pdf", pdf);
    }

    public byte[] photo(int idPhoto, Utilisateur u) {
        Object[] p = repository.photo(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo inconnue."));
        etatDesLieux((Integer) p[1], u);
        return stockagePhotos.lire((String) p[0]);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
