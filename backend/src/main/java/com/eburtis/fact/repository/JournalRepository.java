package com.eburtis.fact.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Qui a fait quoi depuis la plateforme (table app.journal). Le journal ne se modifie ni ne se purge d'ici. */
@Repository
public class JournalRepository {

    private final JdbcTemplate jdbc;

    public JournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void inserer(String auteur, String objet, String cle, String action, String avant, String apres, String adresseIp) {
        jdbc.update("""
                INSERT INTO app.journal (auteur, objet, cle, action, avant, apres, adresse_ip)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""", auteur, objet, cle, action, avant, apres, adresseIp);
    }

    /**
     * Les dernières lignes, les plus récentes d'abord, en JSON : pour un objet (COMPTE, BATIMENT…),
     * pour une fiche précise (objet et clé), ou pour tout le journal.
     */
    public String dernieres(int limite, String objet, String cle) {
        StringBuilder sql = new StringBuilder("SELECT coalesce(json_agg(to_json(j)), '[]')::text FROM (SELECT * FROM app.journal WHERE true");
        List<Object> params = new ArrayList<>();
        if (objet != null && !objet.isBlank()) {
            sql.append(" AND objet = ?");
            params.add(objet);
        }
        if (cle != null && !cle.isBlank()) {
            sql.append(" AND cle = ?");
            params.add(cle);
        }
        sql.append(" ORDER BY le DESC, id DESC LIMIT ?) j");
        params.add(limite);
        return jdbc.queryForObject(sql.toString(), String.class, params.toArray());
    }

    /** Les natures d'objets présentes dans le journal, pour le filtre de l'écran. */
    public List<String> objets() {
        return jdbc.queryForList("SELECT DISTINCT objet FROM app.journal ORDER BY objet", String.class);
    }
}
