package com.eburtis.fact.facade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.ReferentielService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bâtiments et lots (SIG-02, SIG-03) et leurs règles du § 4.2 :
 * un bâtiment ne s'enregistre pas sans position ni propriétaire ; le numéro de porte est unique
 * dans un bâtiment ; rien ne se supprime, tout s'archive. Chaque lecture et chaque écriture
 * passe par le périmètre du compte : un gestionnaire n'atteint que son portefeuille.
 */
@Service
public class BatimentFacade {

    public static final String BATIMENT = "BATIMENT";
    public static final String LOT = "LOT";

    /**
     * Les statuts qu'on peut poser à la main sur un lot. « Occupé » et « En préavis » viendront
     * du bail (étape 9), « À louer » et « À vendre » de la publication (étape 14) ; ils restent
     * permis ici pour reprendre l'existant de l'agence avant que ces étapes n'arrivent.
     */
    private static final String DOMAINE_STATUT = "STATUT_LOT";

    private final BatimentRepository batimentRepository;
    private final LotRepository lotRepository;
    private final ProprietaireRepository proprietaireRepository;
    private final PerimetreService perimetreService;
    private final ReferentielService referentielService;
    private final JournalService journalService;
    private final com.eburtis.fact.repository.LocationRepository locationRepository;
    private final com.eburtis.fact.repository.TerrainRepository terrainRepository;
    private final com.eburtis.fact.service.StockagePhotos stockagePhotos;

    public BatimentFacade(BatimentRepository batimentRepository, LotRepository lotRepository,
                          ProprietaireRepository proprietaireRepository, PerimetreService perimetreService,
                          ReferentielService referentielService, JournalService journalService,
                          com.eburtis.fact.repository.LocationRepository locationRepository,
                          com.eburtis.fact.repository.TerrainRepository terrainRepository,
                          com.eburtis.fact.service.StockagePhotos stockagePhotos) {
        this.locationRepository = locationRepository;
        this.terrainRepository = terrainRepository;
        this.stockagePhotos = stockagePhotos;
        this.batimentRepository = batimentRepository;
        this.lotRepository = lotRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.perimetreService = perimetreService;
        this.referentielService = referentielService;
        this.journalService = journalService;
    }

    // ------------------------------------------------------------------ lecture
    public JsonNode lister(Utilisateur u, boolean archives) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return batimentRepository.lister(perimetre, params, archives && u.peut(Profil.Droit.PATRIMOINE_ECRIRE), null);
    }

    /** La fiche d'un bâtiment : son identité, ses lots, son propriétaire. */
    public Map<String, Object> fiche(int id, Utilisateur u) {
        JsonNode b = accessible(id, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("batiment", b);
        r.put("lots", lotRepository.duBatiment(id, false));
        r.put("photos", terrainRepository.photos(id));
        boolean sien = b.hasNonNull("idProprietaire") && proprietaireRepository.compteDe(b.get("idProprietaire").asInt())
                .map(c -> c == u.id()).orElse(false);
        r.put("proprietaire", proprietaireRepository.trouver(b.get("idProprietaire").asInt(),
                perimetreService.voitContacts(u) || sien).orElse(null));
        return r;
    }

    /**
     * Un bâtiment recensé sur le terrain (§ 4.6) : mêmes contrôles qu'à la saisie au bureau, mais
     * la fiche attend toujours le contrôle du gestionnaire, quel que soit le profil de l'agent.
     */
    @Transactional
    public int creerDepuisTerrain(BatimentSaisieDto saisie, Utilisateur auteur) {
        BatimentSaisieDto s = controler(saisie, null, auteur);
        int id = batimentRepository.creer(s, "A_VALIDER", "TERRAIN", auteur.signature());
        JsonNode apres = batimentRepository.trouver(id, "true", List.of()).orElseThrow();
        journalService.consigner(auteur.signature(), BATIMENT, String.valueOf(id), "Bâtiment recensé sur le terrain", null, apres);
        return id;
    }

    /** Ajoute des photos à un bâtiment (terrain ou bureau). */
    @Transactional
    public JsonNode ajouterPhotos(int id, List<org.springframework.web.multipart.MultipartFile> fichiers, Utilisateur u) {
        if (!u.peut(Profil.Droit.PATRIMOINE_ECRIRE)) {
            throw ErreurMetier.refuse("Ajouter des photos demande le droit de modifier le patrimoine.");
        }
        accessible(id, u);
        if (fichiers == null || fichiers.isEmpty()) {
            throw ErreurMetier.invalide("Choisissez au moins une photo.");
        }
        for (var f : fichiers) {
            try {
                var rangee = stockagePhotos.enregistrer("batiments", String.valueOf(id), f.getBytes());
                terrainRepository.ajouterPhoto(id, rangee.chemin(), rangee.largeur(), rangee.hauteur(), null, u.signature());
            } catch (java.io.IOException e) {
                throw ErreurMetier.invalide("La photo « " + f.getOriginalFilename() + " » n'a pas pu être lue.");
            }
        }
        journalService.consigner(u.signature(), BATIMENT, String.valueOf(id), fichiers.size() + " photo(s) ajoutée(s)", null, null);
        return terrainRepository.photos(id);
    }

    public byte[] photo(int idPhoto, Utilisateur u) {
        Object[] p = terrainRepository.photo(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo inconnue."));
        accessible((Integer) p[1], u);
        return stockagePhotos.lire((String) p[0]);
    }

    public JsonNode historique(int id, Utilisateur u) {
        accessible(id, u);
        return batimentRepository.historique(id);
    }

    public JsonNode gestionnaires() {
        return batimentRepository.gestionnaires();
    }

    // ------------------------------------------------------------------ bâtiments
    @Transactional
    public JsonNode enregistrer(BatimentSaisieDto saisie, Utilisateur auteur) {
        JsonNode avant = saisie.id() == null ? null : accessible(saisie.id(), auteur);
        BatimentSaisieDto s = controler(saisie, avant, auteur);
        if (avant == null) {
            // Une fiche créée par un agent de terrain attend le contrôle du gestionnaire (§ 4.6).
            String validation = auteur.profil() == Profil.AGENT ? "A_VALIDER" : "VALIDE";
            int id = batimentRepository.creer(s, validation, auteur.profil() == Profil.AGENT ? "TERRAIN" : "SAISIE", auteur.signature());
            JsonNode apres = batimentRepository.trouver(id, "true", List.of()).orElseThrow();
            journalService.consigner(auteur.signature(), BATIMENT, String.valueOf(id), "Bâtiment créé", null, apres);
            return apres;
        }
        batimentRepository.modifier(s, auteur.signature());
        JsonNode apres = batimentRepository.trouver(s.id(), "true", List.of()).orElseThrow();
        boolean reaffecte = !egal(avant, apres, "idGestionnaire") || !egal(avant, apres, "idSuppleant");
        journalService.consigner(auteur.signature(), BATIMENT, String.valueOf(s.id()),
                reaffecte ? "Bâtiment modifié et réaffecté" : "Bâtiment modifié", avant, apres);
        return apres;
    }

    /** Le gestionnaire, ou la Direction, valide une fiche venue du terrain ou d'une reprise (§ 4.6, § 7.3). */
    @Transactional
    public JsonNode valider(int id, Utilisateur auteur) {
        if (auteur.profil() == Profil.AGENT) {
            throw ErreurMetier.refuse("La validation revient au gestionnaire du bâtiment ou à la Direction.");
        }
        JsonNode avant = accessible(id, auteur);
        if (avant.get("latitude").isNull()) {
            throw ErreurMetier.invalide("Ce bâtiment n'est pas encore localisé : placez-le sur la carte avant de le valider.");
        }
        batimentRepository.valider(id, auteur.signature());
        JsonNode apres = batimentRepository.trouver(id, "true", List.of()).orElseThrow();
        journalService.consigner(auteur.signature(), BATIMENT, String.valueOf(id), "Bâtiment validé", avant, apres);
        return apres;
    }

    /** Archive ou restaure un bâtiment. Réservé à la Direction : un bâtiment archivé sort de la gestion. */
    @Transactional
    public JsonNode archiver(int id, boolean archive, Utilisateur auteur) {
        if (!auteur.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction archive ou restaure un bâtiment.");
        }
        JsonNode avant = batimentRepository.trouver(id, "true", List.of())
                .orElseThrow(() -> ErreurMetier.introuvable("Bâtiment inconnu."));
        batimentRepository.archiver(id, archive, auteur.signature());
        JsonNode apres = batimentRepository.trouver(id, "true", List.of()).orElseThrow();
        journalService.consigner(auteur.signature(), BATIMENT, String.valueOf(id),
                archive ? "Bâtiment archivé" : "Bâtiment restauré", avant, apres);
        return apres;
    }

    // ------------------------------------------------------------------ lots
    @Transactional
    public JsonNode enregistrerLot(LotSaisieDto saisie, Utilisateur auteur) {
        JsonNode avant = null;
        Integer idBatiment = saisie.idBatiment();
        if (saisie.id() != null) {
            avant = lotRepository.trouver(saisie.id()).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
            idBatiment = avant.get("idBatiment").asInt();
        }
        if (idBatiment == null) {
            throw ErreurMetier.invalide("Le bâtiment du lot est obligatoire.");
        }
        accessible(idBatiment, auteur);
        LotSaisieDto s = controlerLot(saisie, idBatiment, avant);
        if (avant == null) {
            int id = lotRepository.creer(s, auteur.signature());
            JsonNode apres = lotRepository.trouver(id).orElseThrow();
            journalService.consigner(auteur.signature(), LOT, String.valueOf(id), "Lot " + s.numero() + " créé", null, apres);
            return apres;
        }
        lotRepository.modifier(s, auteur.signature());
        JsonNode apres = lotRepository.trouver(s.id()).orElseThrow();
        boolean statutChange = !egal(avant, apres, "statut");
        journalService.consigner(auteur.signature(), LOT, String.valueOf(s.id()),
                statutChange ? "Lot " + s.numero() + " : " + avant.get("libelleStatut").asText() + " → " + apres.get("libelleStatut").asText()
                        : "Lot " + s.numero() + " modifié", avant, apres);
        return apres;
    }

    @Transactional
    public JsonNode archiverLot(int id, boolean archive, Utilisateur auteur) {
        JsonNode avant = lotRepository.trouver(id).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
        int idBatiment = avant.get("idBatiment").asInt();
        accessible(idBatiment, auteur);
        if (auteur.profil() == Profil.AGENT) {
            throw ErreurMetier.refuse("L'archivage d'un lot revient au gestionnaire ou à la Direction.");
        }
        String statut = avant.get("statut").asText();
        if (archive && (statut.equals("OCCUPE") || statut.equals("EN_PREAVIS") || locationRepository.bailActifDuLot(id).isPresent())) {
            throw ErreurMetier.invalide("Ce lot est occupé : il ne peut pas être archivé.");
        }
        if (!archive && lotRepository.numeroPris(idBatiment, avant.get("numero").asText())) {
            throw ErreurMetier.invalide("Un autre lot porte désormais ce numéro de porte dans le bâtiment.");
        }
        lotRepository.archiver(id, archive, auteur.signature());
        JsonNode apres = lotRepository.trouver(id).orElseThrow();
        journalService.consigner(auteur.signature(), LOT, String.valueOf(id),
                "Lot " + avant.get("numero").asText() + (archive ? " archivé" : " restauré"), avant, apres);
        return apres;
    }

    // ------------------------------------------------------------------ contrôles
    /** Le bâtiment s'il est dans le périmètre du compte ; sinon « inconnu », sans dire qu'il existe. */
    private JsonNode accessible(int id, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return batimentRepository.trouver(id, perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Bâtiment inconnu."));
    }

    private BatimentSaisieDto controler(BatimentSaisieDto s, JsonNode avant, Utilisateur auteur) {
        String nom = vide(s.nom());
        if (nom == null) {
            throw ErreurMetier.invalide("Le nom du bâtiment est obligatoire.");
        }
        referentielService.exiger("TYPE_BATIMENT", s.type(), "Type de bâtiment");
        if (s.idCommune() == null) {
            throw ErreurMetier.invalide("La commune est obligatoire.");
        }
        if (s.idProprietaire() == null) {
            throw ErreurMetier.invalide("Un bâtiment ne peut pas être enregistré sans propriétaire.");
        }
        JsonNode proprietaire = proprietaireRepository.trouver(s.idProprietaire(), false)
                .orElseThrow(() -> ErreurMetier.invalide("Propriétaire inconnu."));
        if (!proprietaire.get("archiveLe").isNull()) {
            throw ErreurMetier.invalide("Ce propriétaire est archivé.");
        }
        // § 4.2 : pas de bâtiment sans position. Seule exception, un bâtiment importé ou repris qui
        // attend encore d'être localisé (§ 7.3) : on peut corriger sa fiche avant le passage de l'agent.
        boolean position = s.latitude() != null && s.longitude() != null;
        boolean enAttente = avant != null && avant.get("latitude").isNull();
        if (!position && !enAttente) {
            throw ErreurMetier.invalide("Un bâtiment ne peut pas être enregistré sans position sur la carte.");
        }
        if (position && (s.latitude() < 4.2 || s.latitude() > 10.8 || s.longitude() < -8.7 || s.longitude() > -2.4)) {
            throw ErreurMetier.invalide("Cette position est hors de Côte d'Ivoire : vérifiez que latitude et longitude ne sont pas inversées.");
        }

        // Qui gère quoi : seule la Direction affecte (§ 2.2). Un gestionnaire qui crée un bâtiment
        // le prend dans son portefeuille ; sinon l'affectation existante est conservée.
        Integer gestionnaire;
        Integer suppleant;
        if (auteur.peut(Profil.Droit.AFFECTATION_ECRIRE)) {
            gestionnaire = s.idGestionnaire();
            suppleant = s.idSuppleant();
            for (Integer g : new Integer[]{gestionnaire, suppleant}) {
                if (g != null && !batimentRepository.estGestionnaire(g)) {
                    throw ErreurMetier.invalide("Le gestionnaire choisi n'est pas un compte gestionnaire actif.");
                }
            }
            if (gestionnaire == null && suppleant != null) {
                throw ErreurMetier.invalide("Un suppléant ne se désigne qu'avec un gestionnaire principal.");
            }
            if (gestionnaire != null && gestionnaire.equals(suppleant)) {
                throw ErreurMetier.invalide("Le suppléant doit être une autre personne que le gestionnaire.");
            }
        } else if (avant == null) {
            gestionnaire = auteur.profil() == Profil.GESTIONNAIRE ? auteur.id() : null;
            suppleant = null;
        } else {
            gestionnaire = entier(avant, "idGestionnaire");
            suppleant = entier(avant, "idSuppleant");
        }
        return new BatimentSaisieDto(s.id(), nom, s.type(), vide(s.adresse()), s.idCommune(), s.idQuartier(),
                position ? s.latitude() : null, position ? s.longitude() : null, position ? s.precisionGps() : null,
                s.niveaux(), s.anneeConstruction(), vide(s.description()), s.idProprietaire(), gestionnaire, suppleant);
    }

    private LotSaisieDto controlerLot(LotSaisieDto s, int idBatiment, JsonNode avant) {
        String numero = vide(s.numero());
        if (numero == null) {
            throw ErreurMetier.invalide("Le numéro de porte est obligatoire.");
        }
        referentielService.exiger("TYPE_LOT", s.type(), "Type de lot");
        String statut = vide(s.statut()) == null ? "VACANT" : s.statut().trim();
        referentielService.exiger(DOMAINE_STATUT, statut, "Statut");
        String statutAvant = avant == null ? null : avant.get("statut").asText();
        boolean occupeAvant = "OCCUPE".equals(statutAvant) || "EN_PREAVIS".equals(statutAvant);
        boolean occupeApres = statut.equals("OCCUPE") || statut.equals("EN_PREAVIS");
        boolean bailActif = avant != null && locationRepository.bailActifDuLot(avant.get("id").asInt()).isPresent();
        if (!statut.equals(statutAvant)) {
            if (bailActif) {
                throw ErreurMetier.invalide("Ce lot a un bail actif : son statut suit le bail (préavis, sortie).");
            }
            if (occupeApres && !(occupeAvant)) {
                throw ErreurMetier.invalide("Un lot occupé a obligatoirement un bail : signez le bail depuis l'onglet « Location » du lot.");
            }
        }
        boolean memeNumero = avant != null && avant.get("numero").asText().equalsIgnoreCase(numero);
        if (!memeNumero && lotRepository.numeroPris(idBatiment, numero)) {
            throw ErreurMetier.invalide("Le numéro de porte « " + numero + " » existe déjà dans ce bâtiment.");
        }
        List<String> equipements = new ArrayList<>();
        for (String e : s.equipements() == null ? List.<String>of() : s.equipements()) {
            referentielService.exiger("EQUIPEMENT", e, "Équipement");
            if (!equipements.contains(e)) {
                equipements.add(e);
            }
        }
        for (Number n : new Number[]{s.surface(), s.pieces(), s.chambres(), s.sallesEau(), s.loyerReference(), s.charges()}) {
            if (n != null && n.doubleValue() < 0) {
                throw ErreurMetier.invalide("Aucune valeur ne peut être négative.");
            }
        }
        return new LotSaisieDto(s.id(), idBatiment, numero, s.etage() == null ? 0 : s.etage(), s.type(), s.surface(), s.pieces(),
                s.chambres(), s.sallesEau(), equipements, s.loyerReference(), s.charges(), statut, vide(s.description()));
    }

    private static boolean egal(JsonNode a, JsonNode b, String champ) {
        return String.valueOf(a.get(champ)).equals(String.valueOf(b.get(champ)));
    }

    private static Integer entier(JsonNode n, String champ) {
        return n.hasNonNull(champ) ? n.get(champ).asInt() : null;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
