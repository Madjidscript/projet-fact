package com.eburtis.fact.facade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.AnnonceSaisieDto;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.repository.AnnonceRepository;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.repository.ReferentielRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.MessageService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.StockagePhotos;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * La publication des lots sur le site (§ 4.10) : le gestionnaire prépare l'annonce d'un lot libre
 * (texte, prix, photos, mode de position) et la propose ; l'éditeur web ou la Direction la valide,
 * et elle est en ligne. La signature d'un bail la retire d'elle-même (déclencheur en base).
 */
@Service
public class AnnonceFacade {

    public static final String OBJET = "ANNONCE";
    private static final Set<String> LIBRES = Set.of("VACANT", "A_LOUER", "A_VENDRE", "RESERVE");
    /** Les locaux où le nombre de pièces n'a pas de sens. */
    private static final Set<String> SANS_PIECES = Set.of("BUREAU", "MAGASIN", "ENTREPOT", "TERRAIN");

    private final AnnonceRepository annonceRepository;
    private final LotRepository lotRepository;
    private final BatimentRepository batimentRepository;
    private final ReferentielRepository referentielRepository;
    private final PerimetreService perimetreService;
    private final StockagePhotos stockagePhotos;
    private final JournalService journalService;
    private final MessageService messageService;
    private final AlerteRepository alerteRepository;

    public AnnonceFacade(AnnonceRepository annonceRepository, LotRepository lotRepository, BatimentRepository batimentRepository,
                         ReferentielRepository referentielRepository, PerimetreService perimetreService,
                         StockagePhotos stockagePhotos, JournalService journalService, MessageService messageService,
                         AlerteRepository alerteRepository) {
        this.annonceRepository = annonceRepository;
        this.lotRepository = lotRepository;
        this.batimentRepository = batimentRepository;
        this.referentielRepository = referentielRepository;
        this.perimetreService = perimetreService;
        this.stockagePhotos = stockagePhotos;
        this.journalService = journalService;
        this.messageService = messageService;
        this.alerteRepository = alerteRepository;
    }

    public static boolean redige(Utilisateur u) {
        return u.peut(Profil.Droit.ANNONCE_ECRIRE) || valide(u);
    }

    public static boolean valide(Utilisateur u) {
        return u.peut(Profil.Droit.ANNONCE_VALIDER);
    }

    public JsonNode lister(Utilisateur u, String statut) {
        exigerLecture(u);
        List<Object> params = new ArrayList<>();
        return annonceRepository.lister(perimetreService.batiments(u, params), params, statut);
    }

    public Map<String, Object> fiche(int id, Utilisateur u) {
        exigerLecture(u);
        JsonNode a = accessible(id, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("annonce", a);
        r.put("photos", annonceRepository.photos(id));
        r.put("manques", manques(a));
        r.put("actions", actions(a, u));
        r.put("lien", messageService.site() + "/biens/" + a.get("slug").asText());
        return r;
    }

    /** L'annonce vivante d'un lot, ou ce qu'il faut pour la commencer (lot, prix suggéré). */
    public Map<String, Object> duLot(int idLot, Utilisateur u) {
        exigerLecture(u);
        JsonNode lot = lotAccessible(idLot, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lot", lot);
        annonceRepository.vivanteDuLot(idLot).ifPresentOrElse(a -> r.putAll(fiche(a.get("id").asInt(), u)), () -> r.put("annonce", null));
        r.put("photosMinimum", photosMinimum());
        return r;
    }

    @Transactional
    public Map<String, Object> enregistrer(AnnonceSaisieDto saisie, Utilisateur auteur) {
        exigerRedaction(auteur);
        AnnonceSaisieDto s = controler(saisie);
        JsonNode lot = lotAccessible(s.idLot(), auteur);
        int id;
        JsonNode avant = null;
        if (s.id() == null) {
            if (!LIBRES.contains(lot.get("statut").asText()) || !lot.get("archiveLe").isNull()) {
                throw ErreurMetier.invalide("Seul un lot libre (vacant, à louer, à vendre ou réservé) se publie sur le site.");
            }
            if (annonceRepository.vivanteDuLot(s.idLot()).isPresent()) {
                throw ErreurMetier.invalide("Ce lot a déjà une annonce : modifiez-la.");
            }
            id = annonceRepository.creer(s, auteur.signature());
        } else {
            avant = accessible(s.id(), auteur);
            if ("RETIREE".equals(avant.get("statut").asText())) {
                throw ErreurMetier.invalide("Cette annonce est retirée : créez-en une nouvelle depuis le lot.");
            }
            id = s.id();
            annonceRepository.modifier(new AnnonceSaisieDto(id, avant.get("idLot").asInt(), s.operation(), s.titre(), s.description(),
                    s.prix(), s.charges(), s.cautionMois(), s.avanceMois(), s.positionExacte()), auteur.signature());
            // Un texte modifié par qui ne valide pas repasse par la validation avant de rester en ligne.
            if ("PUBLIEE".equals(avant.get("statut").asText()) && !valide(auteur)) {
                annonceRepository.statut(id, "A_VALIDER", null, auteur.signature());
            }
        }
        JsonNode apres = annonceRepository.trouver(id).orElseThrow();
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), avant == null ? "Annonce préparée" : "Annonce modifiée", avant, apres);
        alerteRepository.generer();
        return fiche(id, auteur);
    }

    /** Proposer l'annonce : contrôles du § 4.10, puis validation (ou mise en ligne directe pour qui valide). */
    @Transactional
    public Map<String, Object> soumettre(int id, Utilisateur auteur) {
        exigerRedaction(auteur);
        JsonNode a = accessible(id, auteur);
        String statut = a.get("statut").asText();
        if (!Set.of("BROUILLON", "REFUSEE").contains(statut)) {
            throw ErreurMetier.invalide("Cette annonce est déjà proposée ou en ligne.");
        }
        List<String> manques = manques(a);
        if (!manques.isEmpty()) {
            throw ErreurMetier.invalide("Avant de la proposer, il manque : " + String.join(", ", manques) + ".");
        }
        if (valide(auteur)) {
            return publier(a, auteur);
        }
        annonceRepository.statut(id, "A_VALIDER", null, auteur.signature());
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Annonce proposée à la validation", a,
                annonceRepository.trouver(id).orElseThrow());
        alerteRepository.generer();
        return fiche(id, auteur);
    }

    @Transactional
    public Map<String, Object> valider(int id, Utilisateur auteur) {
        exigerValidation(auteur);
        JsonNode a = accessible(id, auteur);
        if (!"A_VALIDER".equals(a.get("statut").asText())) {
            throw ErreurMetier.invalide("Seule une annonce proposée se valide.");
        }
        List<String> manques = manques(a);
        if (!manques.isEmpty()) {
            throw ErreurMetier.invalide("Il manque : " + String.join(", ", manques) + ".");
        }
        return publier(a, auteur);
    }

    @Transactional
    public Map<String, Object> refuser(int id, String motif, Utilisateur auteur) {
        exigerValidation(auteur);
        JsonNode a = accessible(id, auteur);
        if (!"A_VALIDER".equals(a.get("statut").asText())) {
            throw ErreurMetier.invalide("Seule une annonce proposée se renvoie au rédacteur.");
        }
        String m = vide(motif);
        if (m == null) {
            throw ErreurMetier.invalide("Dites ce qu'il faut revoir.");
        }
        annonceRepository.statut(id, "REFUSEE", m, auteur.signature());
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Annonce à revoir : " + m, a, annonceRepository.trouver(id).orElseThrow());
        alerteRepository.generer();
        return fiche(id, auteur);
    }

    @Transactional
    public Map<String, Object> retirer(int id, String motif, Utilisateur auteur) {
        exigerRedaction(auteur);
        JsonNode a = accessible(id, auteur);
        if ("RETIREE".equals(a.get("statut").asText())) {
            throw ErreurMetier.invalide("Cette annonce est déjà retirée.");
        }
        String m = vide(motif) == null ? "Retirée par l'agence" : vide(motif);
        annonceRepository.statut(id, "RETIREE", m, auteur.signature());
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Annonce retirée : " + m, a, annonceRepository.trouver(id).orElseThrow());
        alerteRepository.generer();
        return fiche(id, auteur);
    }

    @Transactional
    public Map<String, Object> aLaUne(int id, boolean une, Utilisateur auteur) {
        exigerValidation(auteur);
        JsonNode a = accessible(id, auteur);
        if (une && !"PUBLIEE".equals(a.get("statut").asText())) {
            throw ErreurMetier.invalide("Seule une annonce en ligne se met à la une.");
        }
        annonceRepository.aLaUne(id, une, auteur.signature());
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), une ? "Annonce mise à la une" : "Annonce retirée de la une", a,
                annonceRepository.trouver(id).orElseThrow());
        return fiche(id, auteur);
    }

    // ------------------------------------------------------------------ photos
    @Transactional
    public Map<String, Object> ajouterPhotos(int id, List<MultipartFile> fichiers, Utilisateur auteur) {
        exigerRedaction(auteur);
        JsonNode a = accessible(id, auteur);
        if ("RETIREE".equals(a.get("statut").asText())) {
            throw ErreurMetier.invalide("Cette annonce est retirée.");
        }
        if (fichiers == null || fichiers.isEmpty()) {
            throw ErreurMetier.invalide("Choisissez au moins une photo.");
        }
        if (a.get("photos").asInt() + fichiers.size() > 20) {
            throw ErreurMetier.invalide("Une annonce compte au plus 20 photos.");
        }
        for (MultipartFile f : fichiers) {
            try {
                StockagePhotos.Rangee r = stockagePhotos.enregistrer("annonces", String.valueOf(id), f.getBytes());
                annonceRepository.ajouterPhoto(id, r.chemin(), r.largeur(), r.hauteur(), auteur.signature());
            } catch (java.io.IOException e) {
                throw ErreurMetier.invalide("La photo « " + f.getOriginalFilename() + " » n'a pas pu être lue.");
            }
        }
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), fichiers.size() + " photo(s) ajoutée(s)", null, null);
        return fiche(id, auteur);
    }

    @Transactional
    public Map<String, Object> supprimerPhoto(int idPhoto, Utilisateur auteur) {
        exigerRedaction(auteur);
        Object[] p = annonceRepository.photo(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo inconnue."));
        int id = (Integer) p[1];
        JsonNode a = accessible(id, auteur);
        if ("PUBLIEE".equals(a.get("statut").asText()) && a.get("photos").asInt() <= photosMinimum()) {
            throw ErreurMetier.invalide("Une annonce en ligne garde au moins " + photosMinimum() + " photos : ajoutez-en une avant d'enlever celle-ci.");
        }
        annonceRepository.supprimerPhoto(idPhoto);
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Photo retirée", null, null);
        return fiche(id, auteur);
    }

    @Transactional
    public Map<String, Object> ordonnerPhotos(int id, List<Integer> ordre, Utilisateur auteur) {
        exigerRedaction(auteur);
        accessible(id, auteur);
        annonceRepository.ordonner(id, ordre == null ? List.of() : ordre);
        return fiche(id, auteur);
    }

    public byte[] photo(int idPhoto, Utilisateur u) {
        exigerLecture(u);
        Object[] p = annonceRepository.photo(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo inconnue."));
        accessible((Integer) p[1], u);
        return stockagePhotos.lire((String) p[0]);
    }

    // ------------------------------------------------------------------ règles
    private Map<String, Object> publier(JsonNode a, Utilisateur auteur) {
        int id = a.get("id").asInt();
        annonceRepository.statut(id, "PUBLIEE", null, auteur.signature());
        annonceRepository.proposerLot(a.get("idLot").asInt(), a.get("operation").asText(), auteur.signature());
        int prevenus = messageService.alerterAbonnes(id);
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id),
                "Annonce mise en ligne" + (prevenus > 0 ? ", " + prevenus + " abonné(s) à prévenir" : ""), a, annonceRepository.trouver(id).orElseThrow());
        alerteRepository.generer();
        return fiche(id, auteur);
    }

    /** Ce qui manque pour publier (§ 4.10) : prix, surface, pièces, trois photos, position. */
    private List<String> manques(JsonNode a) {
        List<String> m = new ArrayList<>();
        if (a.get("prix").asInt() <= 0) {
            m.add("le prix");
        }
        if (a.get("surface").isNull()) {
            m.add("la surface du lot");
        }
        if (a.get("pieces").isNull() && !SANS_PIECES.contains(a.get("typeLot").asText())) {
            m.add("le nombre de pièces du lot");
        }
        int photos = a.get("photos").asInt();
        if (photos < photosMinimum()) {
            m.add((photosMinimum() - photos) + " photo(s) de plus (au moins " + photosMinimum() + ")");
        }
        if (a.get("latitude").isNull()) {
            m.add("la position du bâtiment sur la carte");
        }
        if (vide(a.hasNonNull("description") ? a.get("description").asText() : null) == null) {
            m.add("la description");
        }
        return m;
    }

    private Map<String, Boolean> actions(JsonNode a, Utilisateur u) {
        String s = a.get("statut").asText();
        boolean r = redige(u), v = valide(u);
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("modifier", r && !"RETIREE".equals(s));
        m.put("soumettre", r && Set.of("BROUILLON", "REFUSEE").contains(s));
        m.put("valider", v && "A_VALIDER".equals(s));
        m.put("refuser", v && "A_VALIDER".equals(s));
        m.put("retirer", r && !"RETIREE".equals(s));
        m.put("aLaUne", v && "PUBLIEE".equals(s));
        m.put("photos", r && !"RETIREE".equals(s));
        return m;
    }

    private AnnonceSaisieDto controler(AnnonceSaisieDto s) {
        if (s.idLot() == null && s.id() == null) {
            throw ErreurMetier.invalide("Le lot est obligatoire.");
        }
        String op = s.operation() == null || s.operation().isBlank() ? "LOCATION" : s.operation();
        if (!Set.of("LOCATION", "VENTE").contains(op)) {
            throw ErreurMetier.invalide("Opération inconnue : à louer ou à vendre.");
        }
        String titre = vide(s.titre());
        if (titre == null || titre.length() > 160) {
            throw ErreurMetier.invalide("Le titre est obligatoire (160 caractères au plus).");
        }
        if (s.prix() == null || s.prix() <= 0) {
            throw ErreurMetier.invalide("Le prix est obligatoire et doit être positif.");
        }
        int charges = s.charges() == null ? 0 : s.charges();
        int caution = s.cautionMois() == null ? 0 : s.cautionMois();
        int avance = s.avanceMois() == null ? 0 : s.avanceMois();
        if (charges < 0 || caution < 0 || caution > 24 || avance < 0 || avance > 24) {
            throw ErreurMetier.invalide("Charges positives ; caution et avance de 0 à 24 mois.");
        }
        return new AnnonceSaisieDto(s.id(), s.idLot(), op, titre, vide(s.description()), s.prix(), charges, caution, avance,
                Boolean.TRUE.equals(s.positionExacte()));
    }

    private int photosMinimum() {
        try {
            return Integer.parseInt(referentielRepository.reglages().getOrDefault("annonce.photos_minimum", "3"));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private JsonNode accessible(int id, Utilisateur u) {
        JsonNode a = annonceRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Annonce inconnue."));
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        batimentRepository.trouver(a.get("idBatiment").asInt(), perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Annonce inconnue."));
        return a;
    }

    private JsonNode lotAccessible(int idLot, Utilisateur u) {
        JsonNode lot = lotRepository.trouver(idLot).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        batimentRepository.trouver(lot.get("idBatiment").asInt(), perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
        return lot;
    }

    private static void exigerLecture(Utilisateur u) {
        if (!redige(u)) {
            throw ErreurMetier.refuse("Les annonces sont réservées aux gestionnaires, à l'éditeur web et à la Direction.");
        }
    }

    private static void exigerRedaction(Utilisateur u) {
        exigerLecture(u);
    }

    private static void exigerValidation(Utilisateur u) {
        if (!valide(u)) {
            throw ErreurMetier.refuse("Seuls l'éditeur web et la Direction valident les annonces.");
        }
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
