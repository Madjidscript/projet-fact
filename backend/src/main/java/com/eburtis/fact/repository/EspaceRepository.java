package com.eburtis.fact.repository;

import java.util.Optional;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * L'espace propriétaire (§ 4.9) : ses biens et le statut de chaque lot, ses loyers et impayés,
 * ses interventions, ses gestionnaires. Aucune donnée personnelle des locataires n'en sort :
 * ni nom, ni téléphone, ni pièce d'identité — seulement la situation de chaque lot.
 */
@Repository
public class EspaceRepository {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public EspaceRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Le propriétaire rattaché à ce compte. */
    public Optional<Integer> proprietaireDuCompte(int idCompte) {
        return jdbc.queryForList("SELECT id FROM pat.proprietaire WHERE id_compte = ? AND archive_le IS NULL", Integer.class, idCompte)
                .stream().findFirst();
    }

    /** Le propriétaire d'un bâtiment. */
    public Optional<Integer> proprietaireDuBatiment(int idBatiment) {
        return jdbc.queryForList("SELECT id_proprietaire FROM pat.batiment WHERE id = ?", Integer.class, idBatiment).stream().findFirst();
    }

    public JsonNode espace(int idProprietaire) {
        return json.lire(jdbc.queryForObject("""
                WITH bats AS (SELECT v.* FROM pat.v_batiment v WHERE v.id_proprietaire = ? AND v.archive_le IS NULL),
                ech AS (
                    SELECT e.* FROM loc.v_echeance e
                    JOIN loc.bail x ON x.id = e.id_bail
                    JOIN pat.lot l ON l.id = x.id_lot
                    WHERE l.id_batiment IN (SELECT id FROM bats)
                      AND e.periode >= (date_trunc('month', current_date) - interval '11 months')::date
                      AND e.date_exigibilite <= current_date),
                recu AS (
                    SELECT date_trunc('month', c.date_paiement)::date AS mois, sum(c.montant) AS montant
                    FROM loc.encaissement c JOIN loc.bail x ON x.id = c.id_bail JOIN pat.lot l ON l.id = x.id_lot
                    WHERE c.annule_le IS NULL AND l.id_batiment IN (SELECT id FROM bats)
                      AND c.date_paiement >= (date_trunc('month', current_date) - interval '11 months')::date
                    GROUP BY 1)
                SELECT json_build_object(
                    'batiments', (SELECT coalesce(json_agg(t ORDER BY t.nom), '[]') FROM (
                        SELECT b.id, b.reference, b.nom, b.commune, b.quartier, b.latitude, b.longitude, b.lots,
                               b.lots_occupes AS occupes, b.lots_vacants AS vacants, b.impaye, b.validation,
                               b.interventions_ouvertes AS interventions,
                               (SELECT coalesce(json_agg(json_build_object(
                                    'numero', l.numero, 'etage', l.etage,
                                    'type', (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type),
                                    'statut', l.statut,
                                    'libelleStatut', (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_LOT' AND code = l.statut),
                                    'loyer', vb.loyer + vb.charges,
                                    'depuis', vb.date_effet,
                                    'moisRetard', coalesce(vb.mois_retard, 0),
                                    'impaye', coalesce(vb.impaye, 0)) ORDER BY l.etage, l.numero), '[]')
                                FROM pat.lot l
                                LEFT JOIN loc.v_bail vb ON vb.id_lot = l.id AND vb.statut IN ('ACTIF', 'PREAVIS')
                                WHERE l.id_batiment = b.id AND l.archive_le IS NULL AND l.statut <> 'HORS_GESTION') AS "lotsDetail"
                        FROM bats b) t),
                    'gestionnaires', (SELECT coalesce(json_agg(t ORDER BY t.role, t.nom), '[]') FROM (
                        SELECT DISTINCT c.id, c.nom, c.fonction, c.telephone, c.email,
                               CASE WHEN c.id IN (SELECT id_gestionnaire FROM bats) THEN 'Gestionnaire' ELSE 'Suppléant' END AS role,
                               (SELECT string_agg(b.nom, ', ' ORDER BY b.nom) FROM bats b WHERE b.id_gestionnaire = c.id OR b.id_suppleant = c.id) AS batiments
                        FROM app.compte c
                        WHERE c.actif AND (c.id IN (SELECT id_gestionnaire FROM bats) OR c.id IN (SELECT id_suppleant FROM bats))) t),
                    'loyers', json_build_object(
                        'attendu', (SELECT coalesce(sum(montant_du), 0) FROM ech),
                        'regle', (SELECT coalesce(sum(regle), 0) FROM ech),
                        'impaye', (SELECT coalesce(sum(impaye), 0) FROM bats),
                        'parMois', (SELECT coalesce(json_agg(t ORDER BY t.mois), '[]') FROM (
                            SELECT m::date AS mois,
                                   coalesce((SELECT sum(montant_du) FROM ech WHERE periode = m), 0) AS attendu,
                                   coalesce((SELECT sum(regle) FROM ech WHERE periode = m), 0) AS regle,
                                   coalesce((SELECT montant FROM recu WHERE mois = m), 0) AS encaisse
                            FROM generate_series((date_trunc('month', current_date) - interval '11 months')::date,
                                                 date_trunc('month', current_date)::date, interval '1 month') m) t)),
                    'interventions', (SELECT coalesce(json_agg(t ORDER BY t."aRepondre" DESC, t.ouverte DESC, t."signaleLe" DESC), '[]') FROM (
                        SELECT i.id, i.reference, i.titre, b.nom AS batiment, b.id AS "idBatiment", l.numero AS "numeroLot",
                               i.statut, (SELECT libelle FROM ref.valeur WHERE domaine = 'URGENCE' AND code = i.urgence) AS urgence,
                               (SELECT libelle FROM ref.valeur WHERE domaine = 'CATEGORIE_INTERVENTION' AND code = i.categorie) AS categorie,
                               i.signale_le AS "signaleLe", i.cloture_le AS "clotureLe", i.constat, i.devis_montant AS devis,
                               i.cout_reel AS cout, i.imputation, tra.seuil_accord(i.id_batiment) AS seuil,
                               i.accord_decision AS "accordDecision", i.accord_motif AS "accordMotif", i.accord_le AS "accordLe",
                               p.nom AS prestataire,
                               i.statut IN ('SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE') AS ouverte,
                               (i.statut = 'EN_COURS' AND i.devis_montant > tra.seuil_accord(i.id_batiment) AND i.accord_decision IS NULL) AS "aRepondre"
                        FROM tra.intervention i
                        JOIN bats b ON b.id = i.id_batiment
                        LEFT JOIN pat.lot l ON l.id = i.id_lot
                        LEFT JOIN tra.prestataire p ON p.id = i.id_prestataire
                        WHERE i.statut <> 'ANNULEE'
                          AND (i.statut IN ('SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE') OR i.signale_le > now() - interval '6 months')
                        LIMIT 60) t))::text""", String.class, idProprietaire));
    }

    /** L'intervention telle que le propriétaire peut y répondre : son bâtiment, son statut, son devis et le seuil. */
    public Optional<JsonNode> interventionPourAccord(int id) {
        return jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM (
                    SELECT i.id, i.reference, i.titre, i.id_batiment AS "idBatiment", b.id_proprietaire AS "idProprietaire", b.nom AS batiment,
                           b.id_gestionnaire AS "idGestionnaire", i.statut, i.devis_montant AS devis, tra.seuil_accord(i.id_batiment) AS seuil,
                           i.accord_decision AS "accordDecision"
                    FROM tra.intervention i JOIN pat.batiment b ON b.id = i.id_batiment WHERE i.id = ?) t""", String.class, id)
                .stream().findFirst().map(json::lire);
    }
}
