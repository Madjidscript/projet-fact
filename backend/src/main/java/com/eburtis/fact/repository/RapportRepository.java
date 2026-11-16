package com.eburtis.fact.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Les indicateurs du § 4.8, calculés par la base sur le périmètre du compte : chaque requête part
 * des bâtiments non archivés qu'il voit (CTE {@code bats}, alias {@code v} pour la condition du
 * périmètre). Les montants sont en FCFA, les taux en pourcentage arrondi au dixième.
 */
@Repository
public class RapportRepository {

    private static final String OCCUPE = "l.statut IN ('OCCUPE', 'EN_PREAVIS')";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public RapportRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private JsonNode requete(String perimetre, List<Object> paramsPerimetre, String suite, Object... autres) {
        List<Object> p = new ArrayList<>(paramsPerimetre);
        p.addAll(List.of(autres));
        String sql = "WITH bats AS (SELECT v.* FROM pat.v_batiment v WHERE v.archive_le IS NULL AND " + perimetre + ") " + suite;
        return json.lire(jdbc.queryForObject(sql, String.class, p.toArray()));
    }

    /** Taux d'occupation : lots occupés / lots gérés, par commune, gestionnaire, propriétaire, bâtiment. */
    public JsonNode occupation(String perimetre, List<Object> params) {
        String groupe = """
                (SELECT coalesce(json_agg(t ORDER BY t.taux DESC NULLS LAST, t.libelle), '[]') FROM (
                    SELECT %s AS libelle, count(*) AS lots, count(*) FILTER (WHERE %s) AS occupes,
                           round(100.0 * count(*) FILTER (WHERE %s) / nullif(count(*), 0), 1) AS taux
                    FROM lots l GROUP BY 1) t)""";
        return requete(perimetre, params, """
                , lots AS (
                    SELECT l.*, b.nom AS batiment, b.commune, coalesce(b.gestionnaire, 'Sans gestionnaire') AS gestionnaire,
                           b.proprietaire
                    FROM pat.lot l JOIN bats b ON b.id = l.id_batiment
                    WHERE l.archive_le IS NULL AND l.statut <> 'HORS_GESTION')
                SELECT json_build_object(
                    'lots', (SELECT count(*) FROM lots),
                    'occupes', (SELECT count(*) FROM lots l WHERE %1$s),
                    'vacants', (SELECT count(*) FROM lots l WHERE l.statut IN ('VACANT', 'A_LOUER', 'A_VENDRE')),
                    'taux', (SELECT round(100.0 * count(*) FILTER (WHERE %1$s) / nullif(count(*), 0), 1) FROM lots l),
                    'parCommune', %2$s,
                    'parGestionnaire', %3$s,
                    'parProprietaire', %4$s,
                    'parBatiment', %5$s)::text""".formatted(OCCUPE,
                groupe.formatted("l.commune", OCCUPE, OCCUPE), groupe.formatted("l.gestionnaire", OCCUPE, OCCUPE),
                groupe.formatted("l.proprietaire", OCCUPE, OCCUPE), groupe.formatted("l.batiment", OCCUPE, OCCUPE)));
    }

    /**
     * Taux de recouvrement : loyers réglés / loyers attendus des échéances exigibles de la période,
     * par mois, gestionnaire et bâtiment ; avec l'argent effectivement reçu chaque mois.
     */
    public JsonNode recouvrement(String perimetre, List<Object> params, LocalDate du, LocalDate au) {
        String groupe = """
                (SELECT coalesce(json_agg(t ORDER BY t.taux NULLS LAST, t.libelle), '[]') FROM (
                    SELECT %s AS libelle, sum(e.montant_du) AS attendu, sum(e.regle) AS regle,
                           round(100.0 * sum(e.regle) / nullif(sum(e.montant_du), 0), 1) AS taux
                    FROM ech e GROUP BY 1) t)""";
        return requete(perimetre, params, """
                , ech AS (
                    SELECT e.*, b.nom AS batiment, coalesce(b.gestionnaire, 'Sans gestionnaire') AS gestionnaire
                    FROM loc.v_echeance e
                    JOIN loc.bail x ON x.id = e.id_bail
                    JOIN pat.lot l ON l.id = x.id_lot
                    JOIN bats b ON b.id = l.id_batiment
                    WHERE e.periode BETWEEN ?::date AND ?::date AND e.date_exigibilite <= current_date),
                recu AS (
                    SELECT date_trunc('month', c.date_paiement)::date AS mois, sum(c.montant) AS montant
                    FROM loc.encaissement c
                    JOIN loc.bail x ON x.id = c.id_bail
                    JOIN pat.lot l ON l.id = x.id_lot
                    JOIN bats b ON b.id = l.id_batiment
                    WHERE c.annule_le IS NULL AND c.date_paiement BETWEEN ?::date AND (?::date + interval '1 month' - interval '1 day')
                    GROUP BY 1)
                SELECT json_build_object(
                    'attendu', (SELECT coalesce(sum(montant_du), 0) FROM ech),
                    'regle', (SELECT coalesce(sum(regle), 0) FROM ech),
                    'taux', (SELECT round(100.0 * sum(regle) / nullif(sum(montant_du), 0), 1) FROM ech),
                    'encaisse', (SELECT coalesce(sum(montant), 0) FROM recu),
                    'parMois', (SELECT coalesce(json_agg(t ORDER BY t.mois), '[]') FROM (
                        SELECT m::date AS mois,
                               coalesce((SELECT sum(montant_du) FROM ech WHERE periode = m), 0) AS attendu,
                               coalesce((SELECT sum(regle) FROM ech WHERE periode = m), 0) AS regle,
                               (SELECT round(100.0 * sum(regle) / nullif(sum(montant_du), 0), 1) FROM ech WHERE periode = m) AS taux,
                               coalesce((SELECT montant FROM recu WHERE mois = m), 0) AS encaisse
                        FROM generate_series(?::date, ?::date, interval '1 month') m) t),
                    'parGestionnaire', %s,
                    'parBatiment', %s)::text""".formatted(groupe.formatted("e.gestionnaire"), groupe.formatted("e.batiment")),
                du, au, du, au, du, au);
    }

    /** Montant des impayés en retard, par ancienneté (1, 2, 3 mois et plus), et les plus gros dossiers. */
    public JsonNode impayes(String perimetre, List<Object> params) {
        return requete(perimetre, params, """
                , dossiers AS (
                    SELECT vb.*, b.nom AS batiment, coalesce(b.gestionnaire, 'Sans gestionnaire') AS gestionnaire
                    FROM loc.v_bail vb JOIN bats b ON b.id = vb.id_batiment
                    WHERE vb.impaye > 0)
                SELECT json_build_object(
                    'total', (SELECT coalesce(sum(impaye), 0) FROM dossiers),
                    'baux', (SELECT count(*) FROM dossiers),
                    'parAnciennete', (SELECT json_agg(t ORDER BY t.ordre) FROM (
                        SELECT a.ordre, a.libelle, count(d.id) AS baux, coalesce(sum(d.impaye), 0) AS montant
                        FROM (VALUES (1, '1 mois'), (2, '2 mois'), (3, '3 mois et plus')) a(ordre, libelle)
                        LEFT JOIN dossiers d ON least(d.mois_retard, 3) = a.ordre
                        GROUP BY a.ordre, a.libelle) t),
                    'dossiers', (SELECT coalesce(json_agg(t), '[]') FROM (
                        SELECT d.occupant, d.telephone_occupant AS telephone, d.batiment, d.numero_lot AS lot,
                               d.gestionnaire, d.impaye AS montant, d.mois_retard AS "moisRetard",
                               d.retard_depuis AS "retardDepuis", d.id_batiment AS "idBatiment", d.id_lot AS "idLot"
                        FROM dossiers d ORDER BY d.impaye DESC LIMIT 15) t))::text""");
    }

    /**
     * Durée de vacance : jours entre la libération d'un lot et son bail suivant (relocations de la
     * période), par type de lot et par quartier ; avec la vacance en cours des lots libres.
     */
    public JsonNode vacance(String perimetre, List<Object> params, LocalDate du, LocalDate au) {
        String groupe = """
                (SELECT coalesce(json_agg(t ORDER BY t.jours DESC), '[]') FROM (
                    SELECT %s AS libelle, count(*) AS cas, round(avg(g.jours), 0) AS jours FROM %s g GROUP BY 1) t)""";
        return requete(perimetre, params, """
                , lots AS (
                    SELECT l.*, coalesce(b.quartier || ', ', '') || b.commune AS lieu,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type) AS libelle_type
                    FROM pat.lot l JOIN bats b ON b.id = l.id_batiment WHERE l.archive_le IS NULL),
                suites AS (
                    SELECT x.id_lot, x.date_sortie,
                           lead(x.date_effet) OVER (PARTITION BY x.id_lot ORDER BY x.date_effet) AS relocation
                    FROM loc.bail x WHERE x.id_lot IN (SELECT id FROM lots)),
                ecarts AS (
                    SELECT l.libelle_type, l.lieu, (s.relocation - s.date_sortie) AS jours
                    FROM suites s JOIN lots l ON l.id = s.id_lot
                    WHERE s.date_sortie IS NOT NULL AND s.relocation IS NOT NULL AND s.relocation >= s.date_sortie
                      AND s.relocation BETWEEN ?::date AND (?::date + interval '1 month' - interval '1 day')),
                encours AS (
                    SELECT l.libelle_type, l.lieu, (current_date - l.statut_depuis) AS jours
                    FROM lots l WHERE l.statut IN ('VACANT', 'A_LOUER'))
                SELECT json_build_object(
                    'moyenne', (SELECT round(avg(jours), 0) FROM ecarts),
                    'relocations', (SELECT count(*) FROM ecarts),
                    'parType', %s,
                    'parQuartier', %s,
                    'enCours', json_build_object(
                        'lots', (SELECT count(*) FROM encours),
                        'moyenne', (SELECT round(avg(jours), 0) FROM encours),
                        'parType', %s,
                        'parQuartier', %s))::text""".formatted(
                groupe.formatted("g.libelle_type", "ecarts"), groupe.formatted("g.lieu", "ecarts"),
                groupe.formatted("g.libelle_type", "encours"), groupe.formatted("g.lieu", "encours")), du, au);
    }

    /** Loyer moyen au m² : loyer du bail en cours, à défaut loyer de référence, rapporté à la surface. */
    public JsonNode loyerM2(String perimetre, List<Object> params) {
        String groupe = """
                (SELECT coalesce(json_agg(t ORDER BY t.prix DESC), '[]') FROM (
                    SELECT %s AS libelle, count(*) AS lots, round(avg(m.loyer / m.surface)) AS prix,
                           round(avg(m.loyer)) AS "loyerMoyen", round(avg(m.surface)) AS "surfaceMoyenne"
                    FROM mesures m GROUP BY 1) t)""";
        return requete(perimetre, params, """
                , mesures AS (
                    SELECT coalesce(b.quartier || ', ', '') || b.commune AS lieu,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type) AS libelle_type,
                           l.surface, coalesce(x.loyer, l.loyer_reference)::numeric AS loyer
                    FROM pat.lot l JOIN bats b ON b.id = l.id_batiment
                    LEFT JOIN loc.bail x ON x.id_lot = l.id AND x.statut IN ('ACTIF', 'PREAVIS')
                    WHERE l.archive_le IS NULL AND l.surface > 0 AND coalesce(x.loyer, l.loyer_reference) > 0)
                SELECT json_build_object(
                    'moyenne', (SELECT round(avg(loyer / surface)) FROM mesures),
                    'lots', (SELECT count(*) FROM mesures),
                    'parQuartier', %s,
                    'parType', %s)::text""".formatted(groupe.formatted("m.lieu"), groupe.formatted("m.libelle_type")));
    }

    /** Délai de traitement des interventions clôturées dans la période : clôture moins signalement. */
    public JsonNode interventions(String perimetre, List<Object> params, LocalDate du, LocalDate au) {
        String groupe = """
                (SELECT coalesce(json_agg(t ORDER BY t.jours DESC), '[]') FROM (
                    SELECT %s AS libelle, count(*) AS interventions, round(avg(c.jours), 1) AS jours,
                           coalesce(sum(c.cout_reel), 0) AS cout
                    FROM closes c GROUP BY 1) t)""";
        return requete(perimetre, params, """
                , closes AS (
                    SELECT i.*, extract(epoch FROM (i.cloture_le - i.signale_le)) / 86400 AS jours,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'CATEGORIE_INTERVENTION' AND code = i.categorie) AS libelle_categorie,
                           coalesce(p.nom, 'Sans prestataire') AS prestataire
                    FROM tra.intervention i
                    JOIN bats b ON b.id = i.id_batiment
                    LEFT JOIN tra.prestataire p ON p.id = i.id_prestataire
                    WHERE i.cloture_le IS NOT NULL
                      AND i.cloture_le::date BETWEEN ?::date AND (?::date + interval '1 month' - interval '1 day'))
                SELECT json_build_object(
                    'closes', (SELECT count(*) FROM closes),
                    'delaiMoyen', (SELECT round(avg(jours), 1) FROM closes),
                    'cout', (SELECT coalesce(sum(cout_reel), 0) FROM closes),
                    'parCategorie', %s,
                    'parPrestataire', %s)::text""".formatted(groupe.formatted("c.libelle_categorie"), groupe.formatted("c.prestataire")),
                du, au);
    }
}
