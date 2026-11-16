package com.eburtis.fact.repository;

import java.time.LocalDate;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Les chiffres du relevé de gestion d'un propriétaire (§ 4.8, SIG-17) : ses bâtiments, les loyers
 * encaissés sur la période, les honoraires de son mandat, les travaux à sa charge.
 */
@Repository
public class ReleveRepository {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public ReleveRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode releve(int idProprietaire, LocalDate du, LocalDate au) {
        return json.lire(jdbc.queryForObject("""
                WITH bats AS (
                    SELECT v.*, m.reference AS mandat, m.taux_honoraires AS taux
                    FROM pat.v_batiment v
                    LEFT JOIN pat.v_mandat m ON m.id = pat.mandat_actif(v.id)
                    WHERE v.id_proprietaire = ? AND v.archive_le IS NULL),
                periode AS (SELECT ?::date AS du, (?::date + interval '1 month' - interval '1 day')::date AS au),
                recu AS (
                    SELECT l.id AS id_lot, sum(c.montant) AS montant, count(*) AS paiements
                    FROM loc.encaissement c
                    JOIN loc.bail x ON x.id = c.id_bail
                    JOIN pat.lot l ON l.id = x.id_lot
                    JOIN bats b ON b.id = l.id_batiment, periode p
                    WHERE c.annule_le IS NULL AND c.date_paiement BETWEEN p.du AND p.au
                    GROUP BY l.id),
                lots AS (
                    SELECT l.id, l.id_batiment, l.numero, l.etage,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type) AS type,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_LOT' AND code = l.statut) AS statut,
                           vb.occupant, (vb.loyer + vb.charges) AS loyer, coalesce(r.montant, 0) AS encaisse,
                           coalesce(vb.impaye, 0) AS impaye
                    FROM pat.lot l
                    JOIN bats b ON b.id = l.id_batiment
                    LEFT JOIN loc.v_bail vb ON vb.id_lot = l.id AND vb.statut IN ('ACTIF', 'PREAVIS')
                    LEFT JOIN recu r ON r.id_lot = l.id
                    WHERE l.archive_le IS NULL AND l.statut <> 'HORS_GESTION'),
                travaux AS (
                    SELECT i.reference, i.titre, i.id_batiment, b.nom AS batiment, i.cloture_le::date AS le,
                           coalesce(i.cout_reel, 0) AS cout
                    FROM tra.intervention i JOIN bats b ON b.id = i.id_batiment, periode p
                    WHERE i.imputation = 'PROPRIETAIRE' AND i.cloture_le::date BETWEEN p.du AND p.au)
                SELECT json_build_object(
                    'batiments', (SELECT coalesce(json_agg(t ORDER BY t.nom), '[]') FROM (
                        SELECT b.id, b.nom, b.reference, b.quartier, b.commune, b.latitude, b.longitude,
                               b.lots, b.lots_occupes AS occupes, b.mandat, b.taux,
                               (SELECT coalesce(sum(encaisse), 0) FROM lots WHERE id_batiment = b.id) AS encaisse,
                               round((SELECT coalesce(sum(encaisse), 0) FROM lots WHERE id_batiment = b.id) * coalesce(b.taux, 0) / 100) AS honoraires,
                               (SELECT coalesce(sum(cout), 0) FROM travaux WHERE id_batiment = b.id) AS travaux,
                               (SELECT coalesce(sum(impaye), 0) FROM lots WHERE id_batiment = b.id) AS impaye
                        FROM bats b) t),
                    'lots', (SELECT coalesce(json_agg(t ORDER BY t.batiment, t.etage, t.numero), '[]') FROM (
                        SELECT l.*, b.nom AS batiment FROM lots l JOIN bats b ON b.id = l.id_batiment) t),
                    'travaux', (SELECT coalesce(json_agg(t ORDER BY t.le), '[]') FROM travaux t))::text""",
                String.class, idProprietaire, du, au));
    }
}
