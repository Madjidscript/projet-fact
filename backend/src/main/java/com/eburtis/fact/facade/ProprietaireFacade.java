package com.eburtis.fact.facade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.MandatRepository;
import com.eburtis.fact.repository.ProprietaireRepository;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.ReferentielService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les propriétaires mandants. Un compte de profil Propriétaire ne voit que sa propre fiche ;
 * les coordonnées ne sont rendues qu'aux profils qui gèrent le patrimoine (§ 8).
 */
@Service
public class ProprietaireFacade {

    public static final String OBJET = "PROPRIETAIRE";

    private final ProprietaireRepository proprietaireRepository;
    private final BatimentRepository batimentRepository;
    private final PerimetreService perimetreService;
    private final ReferentielService referentielService;
    private final JournalService journalService;
    private final MandatRepository mandatRepository;

    public ProprietaireFacade(ProprietaireRepository proprietaireRepository, BatimentRepository batimentRepository,
                              PerimetreService perimetreService, ReferentielService referentielService,
                              JournalService journalService, MandatRepository mandatRepository) {
        this.mandatRepository = mandatRepository;
        this.proprietaireRepository = proprietaireRepository;
        this.batimentRepository = batimentRepository;
        this.perimetreService = perimetreService;
        this.referentielService = referentielService;
        this.journalService = journalService;
    }

    public JsonNode lister(Utilisateur u, boolean archives) {
        boolean seulementLui = u.profil() == Profil.PROPRIETAIRE;
        return proprietaireRepository.lister(perimetreService.voitContacts(u) || seulementLui,
                archives && perimetreService.voitContacts(u), seulementLui ? u.id() : null);
    }

    /** La fiche d'un propriétaire et ses bâtiments visibles du compte connecté. */
    public Map<String, Object> fiche(int id, Utilisateur u) {
        boolean lui = proprietaireRepository.compteDe(id).map(c -> c == u.id()).orElse(false);
        if (u.profil() == Profil.PROPRIETAIRE && !lui) {
            throw ErreurMetier.introuvable("Propriétaire inconnu.");
        }
        JsonNode p = proprietaireRepository.trouver(id, perimetreService.voitContacts(u) || lui)
                .orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("proprietaire", p);
        r.put("batiments", batimentRepository.lister(perimetre, params, false, id));
        r.put("mandats", mandatRepository.duProprietaire(id));
        return r;
    }

    /** Les comptes de l'espace propriétaire que l'on peut rattacher à une fiche. */
    public JsonNode comptes() {
        return proprietaireRepository.comptesProprietaires();
    }

    @Transactional
    public JsonNode enregistrer(ProprietaireSaisieDto saisie, Utilisateur auteur) {
        ProprietaireSaisieDto s = nettoyer(saisie);
        if (s.nom() == null) {
            throw ErreurMetier.invalide("Le nom, ou la raison sociale, est obligatoire.");
        }
        referentielService.exiger("TYPE_PERSONNE", s.typePersonne(), "Type de personne");
        if (s.reversementMode() != null) {
            referentielService.exiger("MODE_PAIEMENT", s.reversementMode(), "Mode de reversement");
        }
        // Rattacher un compte à une fiche ouvre l'espace propriétaire : réservé à la Direction.
        if (s.idCompte() != null && !auteur.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction rattache un compte à un propriétaire.");
        }
        if (s.id() == null) {
            int id = proprietaireRepository.creer(s, "SAISIE", auteur.signature());
            JsonNode apres = proprietaireRepository.trouver(id, true).orElseThrow();
            journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Propriétaire créé", null, apres);
            return apres;
        }
        JsonNode avant = proprietaireRepository.trouver(s.id(), true)
                .orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
        // Qui n'est pas de la Direction ne touche pas au compte rattaché : il est conservé tel quel.
        ProprietaireSaisieDto aEcrire = auteur.estDirection() ? s : avecCompte(s, entier(avant, "idCompte"));
        proprietaireRepository.modifier(aEcrire, auteur.signature());
        JsonNode apres = proprietaireRepository.trouver(s.id(), true).orElseThrow();
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(s.id()), "Propriétaire modifié", avant, apres);
        return apres;
    }

    /**
     * Ouvre l'espace propriétaire en ligne (§ 4.9) : crée son compte à son e-mail et le rattache à sa
     * fiche. La Direction remet le mot de passe provisoire au propriétaire ; il le change à la connexion.
     */
    @Transactional
    public Map<String, Object> ouvrirEspace(int id, Utilisateur auteur, CompteFacade comptes) {
        if (!auteur.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction ouvre l'espace d'un propriétaire.");
        }
        JsonNode p = proprietaireRepository.trouver(id, true).orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
        if (!p.get("idCompte").isNull()) {
            throw ErreurMetier.invalide("Ce propriétaire a déjà son espace.");
        }
        String email = p.hasNonNull("email") ? p.get("email").asText() : null;
        if (email == null || !email.contains("@")) {
            throw ErreurMetier.invalide("Renseignez d'abord l'e-mail du propriétaire : il s'en servira pour se connecter.");
        }
        Map<String, Object> c = comptes.creerProprietaire(p.get("nomComplet").asText(), email,
                p.hasNonNull("telephone") ? p.get("telephone").asText() : null, auteur);
        proprietaireRepository.rattacherCompte(id, (Integer) c.get("id"), auteur.signature());
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Espace propriétaire ouvert", p,
                proprietaireRepository.trouver(id, true).orElseThrow());
        Map<String, Object> r = new LinkedHashMap<>(c);
        r.put("email", email);
        return r;
    }

    /** Archive ou restaure. Un propriétaire qui possède encore des bâtiments actifs ne s'archive pas. */
    @Transactional
    public JsonNode archiver(int id, boolean archive, Utilisateur auteur) {
        JsonNode avant = proprietaireRepository.trouver(id, true)
                .orElseThrow(() -> ErreurMetier.introuvable("Propriétaire inconnu."));
        if (archive && proprietaireRepository.batimentsActifs(id) > 0) {
            throw ErreurMetier.invalide("Ce propriétaire possède encore des bâtiments actifs : archivez-les ou changez leur propriétaire d'abord.");
        }
        proprietaireRepository.archiver(id, archive, auteur.signature());
        JsonNode apres = proprietaireRepository.trouver(id, true).orElseThrow();
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id),
                archive ? "Propriétaire archivé" : "Propriétaire restauré", avant, apres);
        return apres;
    }

    private static ProprietaireSaisieDto nettoyer(ProprietaireSaisieDto s) {
        return new ProprietaireSaisieDto(s.id(), vide(s.typePersonne()) == null ? "PHYSIQUE" : s.typePersonne().trim(),
                vide(s.nom()), vide(s.prenoms()), vide(s.telephone()), vide(s.telephone2()), vide(s.email()), vide(s.adresse()),
                vide(s.pieceType()), vide(s.pieceNumero()), vide(s.reversementMode()), vide(s.reversementCoordonnees()),
                s.idCompte(), vide(s.notes()));
    }

    private static ProprietaireSaisieDto avecCompte(ProprietaireSaisieDto s, Integer idCompte) {
        return new ProprietaireSaisieDto(s.id(), s.typePersonne(), s.nom(), s.prenoms(), s.telephone(), s.telephone2(), s.email(),
                s.adresse(), s.pieceType(), s.pieceNumero(), s.reversementMode(), s.reversementCoordonnees(), idCompte, s.notes());
    }

    private static Integer entier(JsonNode n, String champ) {
        return n.hasNonNull(champ) ? n.get(champ).asInt() : null;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
