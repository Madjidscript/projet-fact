package com.eburtis.fact.facade;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.EtapeInterventionDto;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.eburtis.fact.presentation.dto.PrestataireSaisieDto;
import com.eburtis.fact.repository.BatimentRepository;
import com.eburtis.fact.repository.InterventionRepository;
import com.eburtis.fact.repository.LotRepository;
import com.eburtis.fact.repository.ReferentielRepository;
import com.eburtis.fact.service.AlerteService;
import com.eburtis.fact.service.JournalService;
import com.eburtis.fact.service.PerimetreService;
import com.eburtis.fact.service.ReferentielService;
import com.eburtis.fact.service.StockagePhotos;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Les interventions (SIG-13, § 4.5) et leur cycle. Qui fait quoi, d'après le § 4.5 et la matrice
 * du § 2.2 :
 * <ul>
 *   <li>signaler : gestionnaire, agent de terrain, responsable travaux, administrateur ;</li>
 *   <li>affecter, constater, terminer : le responsable travaux (ou l'administrateur) ;</li>
 *   <li>valider le devis, clôturer : le gestionnaire du bâtiment (ou l'administrateur) ; au-delà
 *       du seuil réglé, l'accord du propriétaire doit être attesté ;</li>
 *   <li>annuler : tant que l'intervention n'est pas terminée, par qui peut la valider ou l'affecter.</li>
 * </ul>
 * Tout passe par le périmètre du compte : un gestionnaire n'atteint que les bâtiments de son portefeuille.
 */
@Service
public class InterventionFacade {

    public static final String OBJET = "INTERVENTION";
    private static final Set<String> TYPES_PHOTO = Set.of("image/jpeg", "image/png", "image/gif", "image/bmp");
    private static final int PHOTOS_MAX = 12;

    private final InterventionRepository interventionRepository;
    private final BatimentRepository batimentRepository;
    private final LotRepository lotRepository;
    private final ReferentielRepository referentielRepository;
    private final PerimetreService perimetreService;
    private final ReferentielService referentielService;
    private final StockagePhotos stockagePhotos;
    private final JournalService journalService;
    private final AlerteService alerteService;

    public InterventionFacade(InterventionRepository interventionRepository, BatimentRepository batimentRepository,
                              LotRepository lotRepository, ReferentielRepository referentielRepository,
                              PerimetreService perimetreService, ReferentielService referentielService,
                              StockagePhotos stockagePhotos, JournalService journalService, AlerteService alerteService) {
        this.interventionRepository = interventionRepository;
        this.batimentRepository = batimentRepository;
        this.lotRepository = lotRepository;
        this.referentielRepository = referentielRepository;
        this.perimetreService = perimetreService;
        this.referentielService = referentielService;
        this.stockagePhotos = stockagePhotos;
        this.journalService = journalService;
        this.alerteService = alerteService;
    }

    // ------------------------------------------------------------------ lecture
    public JsonNode lister(Utilisateur u, String filtre, Integer idBatiment) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return interventionRepository.lister(perimetre, params, filtre, idBatiment);
    }

    public Map<String, Object> fiche(int id, Utilisateur u) {
        JsonNode i = accessible(id, u);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("intervention", i);
        r.put("photos", interventionRepository.photos(id));
        r.put("historique", interventionRepository.historique(id));
        r.put("seuilAccordProprietaire", seuil(i.get("idBatiment").asInt()));
        r.put("actions", actions(i, u));
        return r;
    }

    public Map<String, Object> synthese(Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("synthese", interventionRepository.synthese(perimetre, params));
        return r;
    }

    public byte[] photo(int idPhoto, Utilisateur u) {
        Object[] p = interventionRepository.photo(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo inconnue."));
        accessible((Integer) p[1], u);
        return stockagePhotos.lire((String) p[0]);
    }

    // ------------------------------------------------------------------ signalement
    @Transactional
    public JsonNode signaler(InterventionSaisieDto s, Utilisateur auteur) {
        if (s.idBatiment() == null) {
            throw ErreurMetier.invalide("Le bâtiment est obligatoire.");
        }
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(auteur, params);
        JsonNode b = batimentRepository.trouver(s.idBatiment(), perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Bâtiment inconnu."));
        if (!b.get("archiveLe").isNull()) {
            throw ErreurMetier.invalide("Ce bâtiment est archivé.");
        }
        if (s.idLot() != null && lotRepository.batimentDe(s.idLot()).map(x -> !x.equals(s.idBatiment())).orElse(true)) {
            throw ErreurMetier.invalide("Ce lot n'appartient pas au bâtiment.");
        }
        String titre = vide(s.titre());
        if (titre == null) {
            throw ErreurMetier.invalide("Décrivez la panne en quelques mots (titre).");
        }
        referentielService.exiger("CATEGORIE_INTERVENTION", s.categorie(), "Catégorie");
        String urgence = vide(s.urgence()) == null ? "NORMALE" : s.urgence();
        referentielService.exiger("URGENCE", urgence, "Urgence");
        int id = interventionRepository.signaler(new InterventionSaisieDto(s.idBatiment(), s.idLot(), s.categorie(), urgence,
                titre, vide(s.description())), auteur.signature());
        JsonNode cree = accessible(id, auteur);
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id), "Intervention signalée : " + titre, null, cree);
        // « Intervention urgente : responsable travaux, immédiat » (§ 4.7)
        if ("CRITIQUE".equals(urgence) || "HAUTE".equals(urgence)) {
            alerteService.generer();
        }
        return cree;
    }

    /** Ajoute des photos à une intervention : « avant » tant qu'elle n'est pas terminée, « après » ensuite ou à la fin. */
    @Transactional
    public JsonNode ajouterPhotos(int id, String moment, List<MultipartFile> fichiers, Utilisateur auteur) throws IOException {
        JsonNode i = accessible(id, auteur);
        if (!Set.of("AVANT", "APRES").contains(moment)) {
            throw ErreurMetier.invalide("Moment de la photo inconnu : AVANT ou APRES.");
        }
        if (!i.get("ouverte").asBoolean()) {
            throw ErreurMetier.invalide("Cette intervention est close : ses photos ne changent plus.");
        }
        if (fichiers == null || fichiers.isEmpty()) {
            throw ErreurMetier.invalide("Aucune photo envoyée.");
        }
        if (interventionRepository.nombrePhotos(id, moment) + fichiers.size() > PHOTOS_MAX) {
            throw ErreurMetier.invalide("Douze photos au plus par moment (avant, après).");
        }
        for (MultipartFile f : fichiers) {
            if (f.getContentType() != null && !TYPES_PHOTO.contains(f.getContentType())) {
                throw ErreurMetier.invalide("« " + f.getOriginalFilename() + " » n'est pas une photo JPEG ou PNG.");
            }
            StockagePhotos.Rangee r = stockagePhotos.enregistrer("interventions", String.valueOf(id), f.getBytes());
            interventionRepository.ajouterPhoto(id, moment, r.chemin(), nom(f.getOriginalFilename()), r.largeur(), r.hauteur(), r.taille(),
                    auteur.signature());
        }
        journalService.consigner(auteur.signature(), OBJET, String.valueOf(id),
                fichiers.size() + " photo(s) " + (moment.equals("AVANT") ? "avant" : "après") + " travaux ajoutée(s)");
        return interventionRepository.photos(id);
    }

    // ------------------------------------------------------------------ cycle
    /** Passe l'intervention à l'étape demandée, si le compte en a le droit et que l'étape s'y prête. */
    @Transactional
    public Map<String, Object> etape(int id, String action, EtapeInterventionDto e, Utilisateur auteur) {
        JsonNode avant = accessible(id, auteur);
        String statut = avant.get("statut").asText();
        if (!actions(avant, auteur).contains(action)) {
            throw ErreurMetier.refuse(pourquoiPas(action, statut));
        }
        String signature = auteur.signature();
        String note = vide(e.note());
        String libelle;
        switch (action) {
            case "affecter" -> {
                if (e.idPrestataire() == null) {
                    throw ErreurMetier.invalide("Choisissez le technicien ou l'entreprise.");
                }
                JsonNode p = interventionRepository.prestataire(e.idPrestataire()).orElseThrow(() -> ErreurMetier.invalide("Prestataire inconnu."));
                if (!p.get("actif").asBoolean()) {
                    throw ErreurMetier.invalide("Ce prestataire n'est plus actif.");
                }
                if (e.datePrevue() != null && e.datePrevue().isBefore(LocalDate.now().minusDays(1))) {
                    throw ErreurMetier.invalide("La date prévue est déjà passée.");
                }
                interventionRepository.affecter(id, e, signature);
                libelle = (statut.equals("AFFECTEE") ? "Réaffectée à " : "Affectée à ") + p.get("nom").asText();
            }
            case "demarrer" -> {
                if (vide(e.constat()) == null) {
                    throw ErreurMetier.invalide("Décrivez le constat fait sur place.");
                }
                if (e.devisMontant() != null && e.devisMontant() < 0) {
                    throw ErreurMetier.invalide("Le devis ne peut pas être négatif.");
                }
                interventionRepository.demarrer(id, new EtapeInterventionDto(null, null, vide(e.constat()), e.devisMontant(), null, null, null, null),
                        signature);
                libelle = e.devisMontant() == null ? "Constat fait, sans devis" : "Constat fait, devis de " + e.devisMontant() + " FCFA";
            }
            case "valider" -> {
                // L'accord peut venir du propriétaire lui-même, donné en ligne depuis son espace (§ 4.9).
                if ("REFUS".equals(avant.path("accordDecision").asText(null))) {
                    throw ErreurMetier.invalide("Le propriétaire a refusé ce devis" + (avant.hasNonNull("accordMotif")
                            ? " : « " + avant.get("accordMotif").asText().replaceAll("[.\s]+$", "") + " »" : "") + ". Demandez un autre devis.");
                }
                boolean accord = Boolean.TRUE.equals(e.accordProprietaire()) || "ACCORD".equals(avant.path("accordDecision").asText(null));
                int devis = avant.get("devisMontant").asInt();
                int seuil = seuil(avant.get("idBatiment").asInt());
                if (devis > seuil && !accord) {
                    throw ErreurMetier.invalide("Le devis dépasse " + String.format(java.util.Locale.FRANCE, "%,d", seuil).replace(' ', ' ').replace(' ', ' ') + " FCFA : l'accord du propriétaire est requis avant de valider.");
                }
                interventionRepository.valider(id, accord, note, signature);
                libelle = "Devis validé" + (accord ? " avec l'accord du propriétaire" : "");
            }
            case "terminer" -> {
                if (e.coutReel() == null || e.coutReel() < 0) {
                    throw ErreurMetier.invalide("Le coût réel des travaux est obligatoire (0 si rien n'a été facturé).");
                }
                interventionRepository.terminer(id, e.coutReel(), note, signature);
                libelle = "Travaux terminés : " + e.coutReel() + " FCFA";
            }
            case "cloturer" -> {
                referentielService.exiger("IMPUTATION", e.imputation(), "Imputation");
                interventionRepository.cloturer(id, e.imputation(), note, signature);
                libelle = "Clôturée : " + referentielService.valeurs("IMPUTATION").stream()
                        .filter(v -> v.code().equals(e.imputation())).findFirst().map(v -> v.libelle().toLowerCase()).orElse(e.imputation());
            }
            case "annuler" -> {
                if (note == null) {
                    throw ErreurMetier.invalide("Le motif de l'annulation est obligatoire.");
                }
                interventionRepository.annuler(id, note, signature);
                libelle = "Annulée : " + note;
            }
            default -> throw ErreurMetier.invalide("Étape inconnue : " + action);
        }
        JsonNode apres = accessible(id, auteur);
        journalService.consigner(signature, OBJET, String.valueOf(id), libelle, avant, apres);
        return fiche(id, auteur);
    }

    /**
     * Les étapes que ce compte peut faire franchir à l'intervention maintenant. L'interface n'affiche
     * que celles-là ; l'API les revérifie à chaque appel.
     */
    public List<String> actions(JsonNode i, Utilisateur u) {
        String statut = i.get("statut").asText();
        boolean admin = u.profil() == Profil.ADMIN;
        boolean travaux = u.peut(Profil.Droit.TRAVAUX_VALIDER) || admin;
        boolean gestion = (u.profil() == Profil.GESTIONNAIRE && u.peut(Profil.Droit.TRAVAUX_ECRIRE)) || admin;
        List<String> r = new ArrayList<>();
        switch (statut) {
            case "SIGNALEE" -> {
                if (travaux) r.add("affecter");
            }
            case "AFFECTEE" -> {
                if (travaux) {
                    r.add("demarrer");
                    r.add("affecter");
                }
            }
            case "EN_COURS" -> {
                boolean devis = i.hasNonNull("devisMontant");
                if (devis && gestion) r.add("valider");
                if (!devis && travaux) r.add("terminer");
            }
            case "VALIDEE" -> {
                if (travaux) r.add("terminer");
            }
            case "TERMINEE" -> {
                if (gestion) r.add("cloturer");
            }
            default -> {
            }
        }
        if (Set.of("SIGNALEE", "AFFECTEE", "EN_COURS", "VALIDEE").contains(statut) && (travaux || gestion)) {
            r.add("annuler");
        }
        if (i.get("ouverte").asBoolean() && u.peut(Profil.Droit.TRAVAUX_ECRIRE)) {
            r.add("photos");
        }
        return r;
    }

    // ------------------------------------------------------------------ prestataires
    public JsonNode prestataires(boolean inactifs) {
        return interventionRepository.prestataires(inactifs);
    }

    @Transactional
    public JsonNode enregistrerPrestataire(PrestataireSaisieDto s, Utilisateur auteur) {
        if (!auteur.peut(Profil.Droit.TRAVAUX_VALIDER) && auteur.profil() != Profil.ADMIN) {
            throw ErreurMetier.refuse("Le répertoire des prestataires est tenu par le responsable travaux.");
        }
        String nomP = vide(s.nom());
        if (nomP == null) {
            throw ErreurMetier.invalide("Le nom du prestataire est obligatoire.");
        }
        if (vide(s.specialite()) != null) {
            referentielService.exiger("CATEGORIE_INTERVENTION", s.specialite(), "Spécialité");
        }
        PrestataireSaisieDto p = new PrestataireSaisieDto(s.id(), nomP, vide(s.specialite()), vide(s.telephone()), vide(s.email()),
                vide(s.notes()), s.actif());
        if (s.id() == null) {
            int id = interventionRepository.creerPrestataire(p, auteur.signature());
            JsonNode cree = interventionRepository.prestataire(id).orElseThrow();
            journalService.consigner(auteur.signature(), "PRESTATAIRE", String.valueOf(id), "Prestataire créé : " + nomP, null, cree);
            return cree;
        }
        JsonNode avantP = interventionRepository.prestataire(s.id()).orElseThrow(() -> ErreurMetier.introuvable("Prestataire inconnu."));
        interventionRepository.modifierPrestataire(p);
        JsonNode apresP = interventionRepository.prestataire(s.id()).orElseThrow();
        journalService.consigner(auteur.signature(), "PRESTATAIRE", String.valueOf(s.id()), "Prestataire modifié : " + nomP, avantP, apresP);
        return apresP;
    }

    // ------------------------------------------------------------------ outils
    private JsonNode accessible(int id, Utilisateur u) {
        List<Object> params = new ArrayList<>();
        String perimetre = perimetreService.batiments(u, params);
        return interventionRepository.trouver(id, perimetre, params).orElseThrow(() -> ErreurMetier.introuvable("Intervention inconnue."));
    }

    /** Le seuil d'accord du propriétaire : celui du mandat du bâtiment, à défaut le réglage général. */
    private int seuil(int idBatiment) {
        return interventionRepository.seuilMandat(idBatiment).orElseGet(() -> {
            try {
                return Integer.parseInt(referentielRepository.reglages().getOrDefault("travaux.seuil_accord_proprietaire", "150000"));
            } catch (NumberFormatException ex) {
                return 150000;
            }
        });
    }

    private static String pourquoiPas(String action, String statut) {
        return switch (action) {
            case "affecter", "demarrer", "terminer" -> "Cette étape revient au responsable travaux, et l'intervention doit en être à l'étape précédente.";
            case "valider", "cloturer" -> "Cette étape revient au gestionnaire du bâtiment, et l'intervention doit en être à l'étape précédente.";
            case "annuler" -> "Une intervention terminée ou close ne s'annule plus.";
            default -> "Étape impossible depuis le statut " + statut + ".";
        };
    }

    private static String nom(String nom) {
        if (nom == null) {
            return null;
        }
        String n = nom.replaceAll("[\\\\/]", "_");
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
