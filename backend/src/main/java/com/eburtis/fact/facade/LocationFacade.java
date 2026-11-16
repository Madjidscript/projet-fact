package com.eburtis.fact.facade;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.FinBailDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.LocationRepository;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.QuittanceService;
import com.eburtis.fact.service.ReferentielService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion locative (SIG-07, SIG-08, § 4.4) : occupants, baux, encaissements, quittances.
 * Règles du § 4.2 : un lot n'a qu'un seul bail actif ; la signature du bail fait passer le lot
 * « Occupé », le préavis « En préavis », la sortie « Vacant ». Tout passe par le périmètre du
 * compte : un gestionnaire n'atteint que les baux de son portefeuille.
 */
@Service
public class LocationFacade {

    public static final String OCCUPANT = "OCCUPANT";
    public static final String BAIL = "BAIL";
    public static final String ENCAISSEMENT = "ENCAISSEMENT";

    /** Les statuts d'un lot qui interdisent d'y signer un bail. */
    private static final Set<String> LOT_INDISPONIBLE = Set.of("EN_TRAVAUX", "HORS_GESTION");

    private final LocationRepository locationRepository;
    private final LotRepository lotRepository;
    private final BatimentRepository batimentRepository;
    private final PerimetreService perimetreService;
    private final ReferentielService referentielService;
    private final QuittanceService quittanceService;
    private final JournalService journalService;
    private final com.eburtis.fact.service.MessageService messageService;

    public LocationFacade(LocationRepository locationRepository, LotRepository lotRepository, BatimentRepository batimentRepository,
                          PerimetreService perimetreService, ReferentielService referentielService,
                          QuittanceService quittanceService, JournalService journalService,
                          com.eburtis.fact.service.MessageService messageService) {
        this.messageService = messageService;
        this.locationRepository = locationRepository;
        this.lotRepository = lotRepository;
        this.batimentRepository = batimentRepository;
        this.perimetreService = perimetreService;
        this.referentielService = referentielService;
        this.quittanceService = quittanceService;
        this.journalService = journalService;
    }

    // ------------------------------------------------------------------ lecture
    /** Les baux du périmètre : tous, « actifs » ou en « retard ». */
    public JsonNode baux(Utilisateur u, String filtre, Integer idBatiment) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return locationRepository.baux(perimetre, params, contacts(u), filtre, null, null, idBatiment);
    }

    /** La location d'un lot : son bail actif, ses échéances et encaissements, l'historique de ses baux. */
    public Map<String, Object> locationDuLot(int idLot, Utilisateur u) {
        int idBatiment = lotRepository.batimentDe(idLot).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
        accessible(idBatiment, u);
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        JsonNode baux = locationRepository.baux(perimetre, params, contacts(u), null, idLot, null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lot", lotRepository.trouver(idLot).orElseThrow());
        JsonNode actif = null;
        for (JsonNode b : baux) {
            String statut = b.get("statut").asText();
            if (statut.equals("ACTIF") || statut.equals("PREAVIS")) {
                actif = b;
                break;
            }
        }
        r.put("bail", actif);
        r.put("echeances", actif == null ? List.of() : locationRepository.echeances(actif.get("id").asInt()));
        r.put("encaissements", actif == null ? List.of() : locationRepository.encaissements(actif.get("id").asInt()));
        r.put("historique", baux);
        return r;
    }

    /** Un bail, ses échéances et ses encaissements. */
    public Map<String, Object> bail(int id, Utilisateur u) {
        JsonNode b = bailAccessible(id, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bail", b);
        r.put("echeances", locationRepository.echeances(id));
        r.put("encaissements", locationRepository.encaissements(id));
        return r;
    }

    /** Les chiffres des loyers du périmètre : attendu, réglé et encaissé des derniers mois, impayés. */
    public Map<String, Object> synthese(Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synthese", locationRepository.synthese(perimetre, params, 6));
        r.put("derniersEncaissements", locationRepository.derniersEncaissements(perimetre, params, 12));
        return r;
    }

    public JsonNode occupants() {
        return locationRepository.occupants();
    }

    public Map<String, Object> occupant(int id, Utilisateur u) {
        JsonNode o = locationRepository.occupant(id).orElseThrow(() -> ErreurMetier.introuvable("Occupant inconnu."));
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("occupant", o);
        r.put("baux", locationRepository.baux(perimetre, params, contacts(u), null, null, id));
        return r;
    }

    // ------------------------------------------------------------------ occupants
    @Transactional
    public JsonNode enregistrerOccupant(OccupantSaisieDto saisie, Utilisateur auteur) {
        OccupantSaisieDto s = nettoyer(saisie);
        referentielService.exiger("TYPE_PERSONNE", s.typePersonne(), "Type de personne");
        if (s.id() == null) {
            int id = locationRepository.creerOccupant(s, auteur.signature());
            JsonNode apres = locationRepository.occupant(id).orElseThrow();
            journalService.consigner(auteur.signature(), OCCUPANT, String.valueOf(id), "Occupant créé", null, apres);
            return apres;
        }
        JsonNode avant = locationRepository.occupant(s.id()).orElseThrow(() -> ErreurMetier.introuvable("Occupant inconnu."));
        locationRepository.modifierOccupant(s, auteur.signature());
        JsonNode apres = locationRepository.occupant(s.id()).orElseThrow();
        journalService.consigner(auteur.signature(), OCCUPANT, String.valueOf(s.id()), "Occupant modifié", avant, apres);
        return apres;
    }

    // ------------------------------------------------------------------ baux
    /**
     * Signe un bail sur un lot : l'occupant est choisi ou créé, le lot passe « Occupé », les
     * échéances jusqu'à ce jour sont produites, et l'avance versée peut être encaissée d'emblée.
     */
    @Transactional
    public Map<String, Object> creerBail(BailSaisieDto s, Utilisateur auteur) {
        if (s.idLot() == null) {
            throw ErreurMetier.invalide("Le lot est obligatoire.");
        }
        JsonNode lot = lotRepository.trouver(s.idLot()).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
        accessible(lot.get("idBatiment").asInt(), auteur);
        if (!lot.get("archiveLe").isNull()) {
            throw ErreurMetier.invalide("Ce lot est archivé.");
        }
        if (LOT_INDISPONIBLE.contains(lot.get("statut").asText())) {
            throw ErreurMetier.invalide("Ce lot est « " + lot.get("libelleStatut").asText() + " » : il ne peut pas être loué en l'état.");
        }
        if (locationRepository.bailActifDuLot(s.idLot()).isPresent()) {
            throw ErreurMetier.invalide("Ce lot a déjà un bail actif : terminez-le avant d'en signer un autre.");
        }
        if (s.dateEffet() == null) {
            throw ErreurMetier.invalide("La date d'effet du bail est obligatoire.");
        }
        if (s.dateEffet().isAfter(LocalDate.now().plusMonths(6))) {
            throw ErreurMetier.invalide("La date d'effet ne peut pas dépasser six mois à venir.");
        }
        if (s.loyer() == null || s.loyer() <= 0) {
            throw ErreurMetier.invalide("Le loyer mensuel est obligatoire.");
        }
        BailSaisieDto b = new BailSaisieDto(s.idLot(), null, null, s.dateEffet(), s.dureeMois() == null ? 12 : s.dureeMois(),
                s.loyer(), s.charges() == null ? 0 : s.charges(), s.caution() == null ? 0 : s.caution(),
                s.avanceMois() == null ? 0 : s.avanceMois(), s.jourExigibilite() == null ? 5 : s.jourExigibilite(),
                s.avanceEncaissee(), s.modeAvance(), vide(s.notes()));

        // L'occupant : choisi parmi les fiches, ou créé dans la foulée.
        int idOccupant;
        if (s.idOccupant() != null) {
            JsonNode o = locationRepository.occupant(s.idOccupant()).orElseThrow(() -> ErreurMetier.invalide("Occupant inconnu."));
            if (!o.get("archiveLe").isNull()) {
                throw ErreurMetier.invalide("Cet occupant est archivé.");
            }
            idOccupant = s.idOccupant();
        } else if (s.nouvelOccupant() != null) {
            idOccupant = enregistrerOccupant(new OccupantSaisieDto(null, s.nouvelOccupant().typePersonne(), s.nouvelOccupant().nom(),
                    s.nouvelOccupant().prenoms(), s.nouvelOccupant().telephone(), s.nouvelOccupant().telephone2(),
                    s.nouvelOccupant().email(), s.nouvelOccupant().pieceType(), s.nouvelOccupant().pieceNumero(),
                    s.nouvelOccupant().profession(), s.nouvelOccupant().employeur(), s.nouvelOccupant().garantNom(),
                    s.nouvelOccupant().garantTelephone(), s.nouvelOccupant().notes()), auteur).get("id").asInt();
        } else {
            throw ErreurMetier.invalide("Choisissez l'occupant, ou saisissez sa fiche.");
        }

        int id = locationRepository.creerBail(b, idOccupant, auteur.signature());
        locationRepository.statutLot(s.idLot(), "OCCUPE", auteur.signature());
        locationRepository.genererEcheances(LocalDate.now(), id);
        JsonNode cree = bailAccessible(id, auteur);
        journalService.consigner(auteur.signature(), BAIL, String.valueOf(id), "Bail signé : lot " + lot.get("numero").asText()
                + " · " + cree.get("occupant").asText(), null, cree);
        journalService.consigner(auteur.signature(), BatimentFacade.LOT, String.valueOf(s.idLot()),
                "Lot " + lot.get("numero").asText() + " : " + lot.get("libelleStatut").asText() + " → Occupé (bail " + cree.get("reference").asText() + ")");

        Map<String, Object> r = new LinkedHashMap<>();
        // L'avance versée à la signature est un vrai paiement : elle solde les premiers mois.
        if (Boolean.TRUE.equals(s.avanceEncaissee()) && b.avanceMois() > 0) {
            if (!auteur.peut(Profil.Droit.ENCAISSEMENT_ECRIRE)) {
                throw ErreurMetier.refuse("Votre compte n'enregistre pas d'encaissement : décochez l'avance encaissée.");
            }
            referentielService.exiger("MODE_PAIEMENT", s.modeAvance(), "Mode de paiement de l'avance");
            LocalDate jour = b.dateEffet().isAfter(LocalDate.now()) ? LocalDate.now() : b.dateEffet();
            r.put("encaissement", encaisser(new EncaissementSaisieDto(id, jour, b.avanceMois() * (b.loyer() + b.charges()),
                    s.modeAvance(), null, "Avance de " + b.avanceMois() + " mois versée à l'entrée"), auteur));
        }
        r.put("bail", bailAccessible(id, auteur));
        return r;
    }

    /** Le locataire annonce son départ : le bail passe en préavis, le lot « En préavis ». */
    @Transactional
    public JsonNode preavis(int id, FinBailDto f, Utilisateur auteur) {
        JsonNode avant = bailAccessible(id, auteur);
        if (!avant.get("statut").asText().equals("ACTIF")) {
            throw ErreurMetier.invalide("Seul un bail actif peut passer en préavis.");
        }
        if (f.dateSortie() == null || f.dateSortie().isBefore(LocalDate.parse(avant.get("dateEffet").asText()))) {
            throw ErreurMetier.invalide("La date de sortie prévue est obligatoire, et postérieure au début du bail.");
        }
        locationRepository.preavis(id, f.dateSortie(), vide(f.motif()), auteur.signature());
        locationRepository.statutLot(avant.get("idLot").asInt(), "EN_PREAVIS", auteur.signature());
        JsonNode apres = bailAccessible(id, auteur);
        journalService.consigner(auteur.signature(), BAIL, String.valueOf(id), "Préavis : sortie prévue le " + f.dateSortie(), avant, apres);
        return apres;
    }

    /** Le locataire est parti : le bail est clos à la date de sortie, le lot redevient « Vacant ». */
    @Transactional
    public JsonNode terminer(int id, FinBailDto f, Utilisateur auteur) {
        JsonNode avant = bailAccessible(id, auteur);
        String statut = avant.get("statut").asText();
        if (!statut.equals("ACTIF") && !statut.equals("PREAVIS")) {
            throw ErreurMetier.invalide("Ce bail est déjà terminé.");
        }
        if (f.dateSortie() == null || f.dateSortie().isBefore(LocalDate.parse(avant.get("dateEffet").asText()))) {
            throw ErreurMetier.invalide("La date de sortie est obligatoire, et postérieure au début du bail.");
        }
        if (f.dateSortie().isAfter(LocalDate.now())) {
            throw ErreurMetier.invalide("La sortie ne s'enregistre qu'une fois faite : pour une sortie à venir, posez un préavis.");
        }
        // Les mois dus jusqu'à la sortie sont produits avant la clôture : ils restent à payer.
        locationRepository.genererEcheances(f.dateSortie(), id);
        locationRepository.terminer(id, f.dateSortie(), vide(f.motif()), auteur.signature());
        locationRepository.statutLot(avant.get("idLot").asInt(), "VACANT", auteur.signature());
        JsonNode apres = bailAccessible(id, auteur);
        journalService.consigner(auteur.signature(), BAIL, String.valueOf(id), "Bail terminé : sortie le " + f.dateSortie(), avant, apres);
        return apres;
    }

    // ------------------------------------------------------------------ encaissements
    /** Enregistre un paiement ; rend l'encaissement, les périodes qu'il solde et la situation du bail. */
    @Transactional
    public Map<String, Object> encaisser(EncaissementSaisieDto s, Utilisateur auteur) {
        if (s.idBail() == null) {
            throw ErreurMetier.invalide("Le bail est obligatoire.");
        }
        if (s.cle() != null) {
            var deja = locationRepository.encaissementParCle(s.cle());
            if (deja.isPresent()) {
                // L'envoi précédent du téléphone avait abouti : on rend le même paiement, sans doublon.
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("encaissement", locationRepository.encaissement(deja.get()).orElseThrow());
                r.put("deja", true);
                return r;
            }
        }
        JsonNode bail = bailAccessible(s.idBail(), auteur);
        if (s.montant() == null || s.montant() <= 0) {
            throw ErreurMetier.invalide("Le montant doit être supérieur à zéro.");
        }
        if (s.datePaiement() == null || s.datePaiement().isAfter(LocalDate.now())) {
            throw ErreurMetier.invalide("La date de paiement est obligatoire, et ne peut pas être dans le futur.");
        }
        if (s.datePaiement().isBefore(LocalDate.parse(bail.get("dateEffet").asText()).minusMonths(2))) {
            throw ErreurMetier.invalide("La date de paiement précède de beaucoup le début du bail : vérifiez-la.");
        }
        referentielService.exiger("MODE_PAIEMENT", s.mode(), "Mode de paiement");
        boolean terrain = auteur.profil() == Profil.AGENT || s.cle() != null;
        long id = locationRepository.creerEncaissement(new EncaissementSaisieDto(s.idBail(), s.datePaiement(), s.montant(), s.mode(),
                vide(s.referencePaiement()), vide(s.notes()), s.cle(), s.latitude(), s.longitude(), s.envoyerRecu()),
                terrain ? "TERRAIN" : "SAISIE", auteur.signature());
        if (id == 0 && s.cle() != null) {
            // Le même envoi est arrivé deux fois en même temps : l'autre l'a déjà enregistré.
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("encaissement", locationRepository.encaissement(locationRepository.encaissementParCle(s.cle()).orElseThrow()).orElseThrow());
            r.put("deja", true);
            return r;
        }
        JsonNode e = locationRepository.encaissement(id).orElseThrow();
        journalService.consigner(auteur.signature(), ENCAISSEMENT, String.valueOf(id), "Encaissement " + e.get("numero").asText()
                + " : " + s.montant() + " FCFA, " + bail.get("occupant").asText(), null, e);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("encaissement", e);
        r.put("periodes", quittanceService.periodes(s.idBail(), id, s.montant()));
        r.put("bail", bailAccessible(s.idBail(), auteur));
        // Le reçu part au locataire (§ 4.6) : d'office sur le terrain, sur demande au bureau.
        if (Boolean.TRUE.equals(s.envoyerRecu()) || (terrain && s.envoyerRecu() == null)) {
            r.put("recu", messageService.recu(locationRepository.pourRecu(id).orElseThrow()));
        }
        return r;
    }

    /** Le reçu ouvert par le locataire depuis le lien de son message, sans compte. */
    public Map.Entry<String, byte[]> recuPublic(java.util.UUID jeton) {
        long id = locationRepository.encaissementParJeton(jeton).orElseThrow(() -> ErreurMetier.introuvable("Reçu introuvable."));
        JsonNode q = locationRepository.pourQuittance(id).orElseThrow(() -> ErreurMetier.introuvable("Reçu introuvable."));
        List<QuittanceService.Periode> periodes = quittanceService.periodes(q.get("idBail").asInt(), id, q.get("montant").asLong());
        return Map.entry("recu_" + q.get("numero").asText() + ".pdf", quittanceService.pdf(q, periodes, "FACT Immobilier"));
    }

    /** Annule un encaissement saisi par erreur : réservé à la Direction ; il reste visible, barré. */
    @Transactional
    public JsonNode annuler(long id, String motif, Utilisateur auteur) {
        if (!auteur.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction annule un encaissement.");
        }
        if (motif == null || motif.isBlank()) {
            throw ErreurMetier.invalide("Le motif de l'annulation est obligatoire.");
        }
        JsonNode avant = locationRepository.encaissement(id).orElseThrow(() -> ErreurMetier.introuvable("Encaissement inconnu."));
        bailAccessible(avant.get("idBail").asInt(), auteur);
        if (locationRepository.annuler(id, motif.trim(), auteur.signature()) == 0) {
            throw ErreurMetier.invalide("Cet encaissement est déjà annulé.");
        }
        JsonNode apres = locationRepository.encaissement(id).orElseThrow();
        journalService.consigner(auteur.signature(), ENCAISSEMENT, String.valueOf(id), "Encaissement " + avant.get("numero").asText()
                + " annulé : " + motif.trim(), avant, apres);
        return apres;
    }

    /** La quittance PDF d'un encaissement, et le nom de son fichier. */
    public Map.Entry<String, byte[]> quittance(long id, Utilisateur u) {
        JsonNode q = locationRepository.pourQuittance(id).orElseThrow(() -> ErreurMetier.introuvable("Encaissement inconnu."));
        accessible(q.get("idBatiment").asInt(), u);
        List<QuittanceService.Periode> periodes = quittanceService.periodes(q.get("idBail").asInt(), id, q.get("montant").asLong());
        byte[] pdf = quittanceService.pdf(q, periodes, u.nom());
        return Map.entry("quittance_" + q.get("numero").asText() + ".pdf", pdf);
    }

    // ------------------------------------------------------------------ contrôles
    private void accessible(int idBatiment, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        batimentRepository.trouver(idBatiment, perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Lot inconnu."));
    }

    private JsonNode bailAccessible(int id, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return locationRepository.bail(id, perimetre, params, contacts(u)).orElseThrow(() -> ErreurMetier.introuvable("Bail inconnu."));
    }

    /** Le propriétaire voit l'occupation de ses biens, pas les coordonnées de ses locataires (§ 4.9). */
    private static boolean contacts(Utilisateur u) {
        return u.profil() != Profil.PROPRIETAIRE;
    }

    private static OccupantSaisieDto nettoyer(OccupantSaisieDto s) {
        String nom = vide(s.nom());
        if (nom == null) {
            throw ErreurMetier.invalide("Le nom de l'occupant est obligatoire.");
        }
        return new OccupantSaisieDto(s.id(), vide(s.typePersonne()) == null ? "PHYSIQUE" : s.typePersonne().trim(), nom, vide(s.prenoms()),
                vide(s.telephone()), vide(s.telephone2()), vide(s.email()), vide(s.pieceType()), vide(s.pieceNumero()),
                vide(s.profession()), vide(s.employeur()), vide(s.garantNom()), vide(s.garantTelephone()), vide(s.notes()));
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
