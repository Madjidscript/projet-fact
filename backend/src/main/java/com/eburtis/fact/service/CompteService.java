package com.eburtis.fact.service;

import java.security.SecureRandom;
import java.util.Optional;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.repository.CompteRepository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Les comptes de la plateforme : reconnaissance du compte d'un jeton, vérification du mot de
 * passe, politique des mots de passe (§ 8 : « politique minimale de complexité »), compte de départ.
 */
@Service
public class CompteService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom HASARD = new SecureRandom();

    private final CompteRepository compteRepository;
    private final PasswordEncoder encodeur;
    private final JournalService journalService;
    /** Empreinte de leurre : un identifiant inconnu coûte autant de temps qu'un mot de passe faux. */
    private final String leurre;

    public CompteService(CompteRepository compteRepository, PasswordEncoder encodeur, JournalService journalService) {
        this.compteRepository = compteRepository;
        this.encodeur = encodeur;
        this.journalService = journalService;
        this.leurre = encodeur.encode(motDePasseProvisoire());
    }

    // ------------------------------------------------------------------ lecture
    public Optional<Utilisateur> utilisateurActif(String identifiant) {
        return compteRepository.trouverActif(identifiant);
    }

    public boolean aucunCompte() {
        return compteRepository.aucunCompte();
    }

    // ------------------------------------------------------------------ connexion
    /** Le compte si l'identifiant et le mot de passe concordent et qu'il est actif. */
    public Optional<Utilisateur> verifier(String identifiant, String motDePasse) {
        if (identifiant == null || motDePasse == null) {
            return Optional.empty();
        }
        String id = identifiant.trim().toLowerCase();
        // Un propriétaire se connecte avec son e-mail : on retrouve son identifiant.
        if (id.contains("@")) {
            id = compteRepository.identifiantParEmail(id).orElse(id);
        }
        String empreinte;
        try {
            empreinte = compteRepository.empreinteActive(id);
        } catch (EmptyResultDataAccessException e) {
            encodeur.matches(motDePasse, leurre);
            return Optional.empty();
        }
        if (empreinte == null || !encodeur.matches(motDePasse, empreinte)) {
            return Optional.empty();
        }
        compteRepository.marquerConnexion(id);
        return utilisateurActif(id);
    }

    @Transactional
    public Utilisateur changerMotDePasse(Utilisateur u, String actuel, String nouveau) {
        String empreinte = compteRepository.empreinte(u.id());
        if (actuel == null || !encodeur.matches(actuel, empreinte)) {
            throw ErreurMetier.invalide("Le mot de passe actuel n'est pas le bon.");
        }
        controlerRobustesse(nouveau, u.identifiant());
        if (encodeur.matches(nouveau, empreinte)) {
            throw ErreurMetier.invalide("Le nouveau mot de passe doit être différent de l'actuel.");
        }
        compteRepository.changerMotDePasse(u.id(), encodeur.encode(nouveau));
        journalService.consigner(u.signature(), JournalService.COMPTE, u.identifiant(), "Mot de passe changé");
        return utilisateurActif(u.identifiant()).orElseThrow();
    }

    /** La politique des mots de passe : 10 caractères au moins, des lettres et des chiffres, pas l'identifiant. */
    public static void controlerRobustesse(String motDePasse, String identifiant) {
        if (motDePasse == null || motDePasse.length() < 10) {
            throw ErreurMetier.invalide("Le mot de passe doit compter au moins 10 caractères.");
        }
        if (motDePasse.length() > 72) {
            throw ErreurMetier.invalide("Le mot de passe ne peut pas dépasser 72 caractères.");
        }
        if (!motDePasse.matches(".*\\p{L}.*") || !motDePasse.matches(".*\\d.*")) {
            throw ErreurMetier.invalide("Le mot de passe doit mêler des lettres et des chiffres.");
        }
        if (motDePasse.toLowerCase().contains(identifiant.toLowerCase())) {
            throw ErreurMetier.invalide("Le mot de passe ne doit pas contenir l'identifiant.");
        }
    }

    // ------------------------------------------------------------------ compte de départ
    /** Création du compte de départ, hors de toute session (premier démarrage). */
    public void creerDeDepart(String identifiant, String nom, Profil profil, String motDePasse) {
        compteRepository.creerDeDepart(identifiant, nom, profil, encodeur.encode(motDePasse));
    }

    /** Douze caractères sans lettres ambiguës (ni l, ni o, ni 0, ni 1), avec au moins un chiffre et une lettre. */
    public static String motDePasseProvisoire() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(HASARD.nextInt(ALPHABET.length())));
        }
        sb.setCharAt(0, (char) ('a' + HASARD.nextInt(11)));          // une lettre sûre, de a à k
        sb.setCharAt(11, (char) ('2' + HASARD.nextInt(8)));          // un chiffre, de 2 à 9
        return sb.toString();
    }
}
