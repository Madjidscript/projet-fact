package com.eburtis.fact.service;

import com.eburtis.fact.repository.JournalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Qui a fait quoi depuis la plateforme (app.journal). « Chaque création ou modification est
 * tracée : auteur, date, ancienne et nouvelle valeur » (§ 8 des spécifications, SIG-22).
 */
@Service
public class JournalService {

    public static final String COMPTE = "COMPTE";
    public static final String CONNEXION = "CONNEXION";

    private final JournalRepository journalRepository;
    private final ObjectMapper json;

    public JournalService(JournalRepository journalRepository, ObjectMapper json) {
        this.journalRepository = journalRepository;
        this.json = json;
    }

    /** Une action sans valeur à comparer : connexion, réinitialisation d'un mot de passe. */
    public void consigner(String auteur, String objet, String cle, String action) {
        consigner(auteur, objet, cle, action, null, null);
    }

    /**
     * Une création ({@code avant} nul), une modification, ou un archivage. {@code avant} et
     * {@code apres} sont écrits en JSON : ne jamais y mettre un mot de passe ni son empreinte.
     */
    public void consigner(String auteur, String objet, String cle, String action, Object avant, Object apres) {
        journalRepository.inserer(tronquer(auteur, 80), objet, tronquer(cle, 80), tronquer(action, 200),
                ecrire(avant), ecrire(apres), poste());
    }

    private String ecrire(Object valeur) {
        if (valeur == null) {
            return null;
        }
        try {
            return json.writeValueAsString(valeur);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** L'adresse du poste d'où vient la requête en cours ; nul hors requête (démarrage, tâche planifiée). */
    private static String poste() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest requete = a.getRequest();
            return tronquer(requete.getRemoteAddr(), 60);
        }
        return null;
    }

    private static String tronquer(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
