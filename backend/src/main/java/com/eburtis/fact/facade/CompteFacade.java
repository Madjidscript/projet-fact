package com.eburtis.fact.facade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.CompteDto;
import com.eburtis.fact.presentation.dto.CompteSaisieDto;
import com.eburtis.fact.repository.CompteRepository;
import com.eburtis.fact.service.CompteService;
import com.eburtis.fact.service.JournalService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration des comptes (SIG-23). Lecture ouverte à la Direction, écriture réservée à
 * l'administrateur (matrice du § 2.2, ligne « Comptes et paramétrage »). Un compte ne se
 * supprime pas : il se désactive et reste consultable (§ 8).
 */
@Service
public class CompteFacade {

    /** Le libellé de chaque droit, tel que l'administration des comptes le présente. */
    private static final Map<String, String> LIBELLES_DROITS = libellesDroits();

    private final CompteRepository compteRepository;
    private final PasswordEncoder encodeur;
    private final JournalService journalService;

    public CompteFacade(CompteRepository compteRepository, PasswordEncoder encodeur, JournalService journalService) {
        this.compteRepository = compteRepository;
        this.encodeur = encodeur;
        this.journalService = journalService;
    }

    /**
     * Ouvre l'espace d'un propriétaire (§ 4.9) : un compte de profil Propriétaire, à son e-mail, au
     * mot de passe provisoire à changer à la première connexion. Rend l'identifiant et ce mot de passe.
     */
    @Transactional
    public Map<String, Object> creerProprietaire(String nom, String email, String telephone, Utilisateur auteur) {
        String base = email.substring(0, email.indexOf('@')).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", ".").replaceAll("\\.+", ".").replaceAll("^[.-]+|[.-]+$", "");
        if (base.length() < 3) {
            base = "proprietaire";
        }
        base = base.length() > 34 ? base.substring(0, 34) : base;
        String identifiant = base;
        for (int n = 2; compteRepository.trouver(identifiant).isPresent(); n++) {
            identifiant = base + n;
        }
        String provisoire = CompteService.motDePasseProvisoire();
        compteRepository.creer(identifiant, nom, "Propriétaire", email.toLowerCase(java.util.Locale.ROOT), telephone, Profil.PROPRIETAIRE,
                java.util.List.of(), encodeur.encode(provisoire), true);
        CompteDto apres = compteRepository.trouver(identifiant).orElseThrow();
        journalService.consigner(auteur.signature(), JournalService.COMPTE, identifiant, "Espace propriétaire ouvert", null, resume(apres));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("identifiant", identifiant);
        r.put("id", compteRepository.trouverActif(identifiant).orElseThrow().id());
        r.put("motDePasseProvisoire", provisoire);
        return r;
    }

    /** Les comptes, et ce qu'on peut leur donner : les profils et les droits. */
    public Map<String, Object> lister() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("comptes", compteRepository.lister());
        r.put("profils", Arrays.stream(Profil.values()).map(p -> Map.of(
                "code", p.name(), "libelle", p.libelle(), "description", p.description(),
                "portee", p.portee().name(), "droits", p.droits().stream().sorted().toList())).toList());
        r.put("droits", Profil.Droit.TOUS.stream().map(d -> Map.of("code", d, "libelle", LIBELLES_DROITS.get(d))).toList());
        return r;
    }

    /** Crée le compte ou met à jour le compte existant ; à la création, rend son mot de passe provisoire. */
    @Transactional
    public Map<String, Object> enregistrer(CompteSaisieDto s, Utilisateur auteur) {
        String identifiant = s.identifiant() == null ? "" : s.identifiant().trim().toLowerCase();
        if (!identifiant.matches("[a-z0-9._-]{3,40}")) {
            throw ErreurMetier.invalide("L'identifiant compte 3 à 40 caractères : lettres minuscules, chiffres, point, tiret.");
        }
        if (s.nom() == null || s.nom().isBlank()) {
            throw ErreurMetier.invalide("Le nom du compte est obligatoire.");
        }
        Profil profil = Profil.parCode(s.profil()).orElseThrow(() -> ErreurMetier.invalide("Le profil est obligatoire."));
        List<String> droits = droitsEnPlus(s.droits(), profil);
        String email = vide(s.email());
        if (profil == Profil.PROPRIETAIRE && email == null) {
            // § 4.9 : l'espace propriétaire s'ouvre par e-mail et mot de passe.
            throw ErreurMetier.invalide("L'adresse e-mail est obligatoire pour un compte propriétaire.");
        }
        boolean actif = s.actif() == null || s.actif();
        CompteDto avant = compteRepository.trouver(identifiant).orElse(null);

        if (identifiant.equals(auteur.identifiant())) {
            if (!actif) {
                throw ErreurMetier.invalide("Vous ne pouvez pas désactiver votre propre compte.");
            }
            if (profil != Profil.ADMIN) {
                throw ErreurMetier.invalide("Vous ne pouvez pas retirer votre propre profil d'administrateur.");
            }
        }
        // Il doit toujours rester un administrateur actif, sans quoi plus personne ne gère les comptes.
        if (avant != null && avant.actif() && "ADMIN".equals(avant.profil()) && (!actif || profil != Profil.ADMIN)
                && compteRepository.administrateursActifs() <= 1) {
            throw ErreurMetier.invalide("C'est le dernier administrateur actif : désignez-en un autre d'abord.");
        }

        Map<String, Object> resultat = new LinkedHashMap<>();
        if (avant != null) {
            compteRepository.modifier(identifiant, s.nom().trim(), vide(s.fonction()), email, vide(s.telephone()), profil, droits, actif);
            CompteDto apres = compteRepository.trouver(identifiant).orElseThrow();
            String action = avant.actif() && !actif ? "Compte désactivé" : !avant.actif() && actif ? "Compte réactivé" : "Compte modifié";
            journalService.consigner(auteur.signature(), JournalService.COMPTE, identifiant, action, resume(avant), resume(apres));
            resultat.put("compte", apres);
        } else {
            String provisoire = CompteService.motDePasseProvisoire();
            compteRepository.creer(identifiant, s.nom().trim(), vide(s.fonction()), email, vide(s.telephone()), profil, droits,
                    encodeur.encode(provisoire), actif);
            CompteDto apres = compteRepository.trouver(identifiant).orElseThrow();
            journalService.consigner(auteur.signature(), JournalService.COMPTE, identifiant, "Compte créé", null, resume(apres));
            resultat.put("compte", apres);
            resultat.put("motDePasseProvisoire", provisoire);
        }
        return resultat;
    }

    /** Pose un nouveau mot de passe provisoire, à changer à la prochaine connexion ; ferme les sessions du compte. */
    @Transactional
    public String reinitialiser(String identifiant, Utilisateur auteur) {
        String provisoire = CompteService.motDePasseProvisoire();
        if (compteRepository.reinitialiser(identifiant, encodeur.encode(provisoire)) == 0) {
            throw ErreurMetier.introuvable("Compte inconnu.");
        }
        journalService.consigner(auteur.signature(), JournalService.COMPTE, identifiant, "Mot de passe réinitialisé");
        return provisoire;
    }

    /** Les droits accordés en plus : connus, sans doublon, et sans ceux que le profil porte déjà. */
    private static List<String> droitsEnPlus(List<String> demandes, Profil profil) {
        List<String> r = new ArrayList<>();
        for (String d : demandes == null ? List.<String>of() : demandes) {
            if (!Profil.Droit.TOUS.contains(d)) {
                throw ErreurMetier.invalide("Droit inconnu : " + d);
            }
            if (!profil.droits().contains(d) && !r.contains(d)) {
                r.add(d);
            }
        }
        r.sort((a, b) -> Integer.compare(Profil.Droit.TOUS.indexOf(a), Profil.Droit.TOUS.indexOf(b)));
        return r;
    }

    /** Ce que le journal garde d'un compte : jamais son mot de passe, ni son empreinte. */
    private static Map<String, Object> resume(CompteDto c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nom", c.nom());
        r.put("fonction", c.fonction());
        r.put("email", c.email());
        r.put("telephone", c.telephone());
        r.put("profil", c.profil());
        r.put("droits", c.droits());
        r.put("actif", c.actif());
        return r;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, String> libellesDroits() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Profil.Droit.PATRIMOINE_LIRE, "Consulter les bâtiments et les lots");
        m.put(Profil.Droit.PATRIMOINE_ECRIRE, "Créer et modifier les bâtiments et les lots");
        m.put(Profil.Droit.AFFECTATION_LIRE, "Voir qui gère quoi");
        m.put(Profil.Droit.AFFECTATION_ECRIRE, "Affecter les gestionnaires");
        m.put(Profil.Droit.LOCATION_LIRE, "Consulter les occupants et les baux");
        m.put(Profil.Droit.LOCATION_ECRIRE, "Gérer les occupants et les baux");
        m.put(Profil.Droit.ENCAISSEMENT_ECRIRE, "Saisir les encaissements");
        m.put(Profil.Droit.TRAVAUX_LIRE, "Consulter les interventions");
        m.put(Profil.Droit.TRAVAUX_ECRIRE, "Signaler et suivre les interventions");
        m.put(Profil.Droit.TRAVAUX_VALIDER, "Affecter et valider les interventions");
        m.put(Profil.Droit.ANNONCE_ECRIRE, "Préparer la publication d'un lot sur le site");
        m.put(Profil.Droit.ANNONCE_VALIDER, "Valider la publication sur le site");
        m.put(Profil.Droit.CONTENU_ECRIRE, "Mettre à jour les pages et les articles du site");
        m.put(Profil.Droit.DEMANDE_LIRE, "Consulter les demandes venues du site");
        m.put(Profil.Droit.DEMANDE_ECRIRE, "Traiter les demandes venues du site");
        m.put(Profil.Droit.TABLEAU_BORD, "Voir les tableaux de bord");
        m.put(Profil.Droit.PARAMETRES_LIRE, "Consulter les comptes, le journal et le paramétrage");
        m.put(Profil.Droit.PARAMETRES_ECRIRE, "Modifier les listes de référence et les seuils");
        m.put(Profil.Droit.TERRAIN, "Utiliser l'application de terrain");
        m.put(Profil.Droit.COMPTES, "Gérer les comptes");
        return m;
    }
}
