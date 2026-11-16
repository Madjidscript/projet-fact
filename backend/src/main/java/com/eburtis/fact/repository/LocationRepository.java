package com.eburtis.fact.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Occupants, baux, échéances et encaissements (schéma loc). La situation de paiement est
 * calculée par la base (vues loc.v_echeance et loc.v_bail) : elle est toujours à jour.
 * Les lectures de baux reçoivent la condition de périmètre du compte (pat.v_batiment, alias v).
 */
@Repository
public class LocationRepository {

    private static final String OCCUPANT = """
            o.id, o.reference, o.type_personne AS "typePersonne", o.nom, o.prenoms,
            btrim(o.nom || ' ' || coalesce(o.prenoms, '')) AS "nomComplet", o.telephone, o.telephone_2 AS "telephone2",
            o.email, o.piece_type AS "pieceType", o.piece_numero AS "pieceNumero", o.profession, o.employeur,
            o.garant_nom AS "garantNom", o.garant_telephone AS "garantTelephone", o.notes, o.archive_le AS "archiveLe",
            (SELECT count(*) FROM loc.bail b WHERE b.id_occupant = o.id AND b.statut IN ('ACTIF', 'PREAVIS')) AS "bauxActifs\"""";

    private static final String BAIL = """
            vb.id, vb.reference, vb.id_lot AS "idLot", vb.numero_lot AS "numeroLot", vb.etage, vb.type_lot AS "typeLot",
            vb.id_batiment AS "idBatiment", v.nom AS batiment, v.reference AS "referenceBatiment", v.commune, v.quartier,
            v.gestionnaire, v.proprietaire, v.id_proprietaire AS "idProprietaire",
            vb.id_occupant AS "idOccupant", vb.reference_occupant AS "referenceOccupant", vb.occupant,
            CASE WHEN ? THEN vb.telephone_occupant END AS "telephoneOccupant",
            vb.date_effet AS "dateEffet", vb.duree_mois AS "dureeMois", vb.date_fin AS "dateFin", vb.loyer, vb.charges,
            vb.caution, vb.avance_mois AS "avanceMois", vb.jour_exigibilite AS "jourExigibilite", vb.statut,
            vb.date_preavis AS "datePreavis", vb.date_sortie AS "dateSortie", vb.motif_fin AS "motifFin", vb.notes,
            vb.du_a_ce_jour AS "duACeJour", vb.paye, vb.solde, vb.impaye, vb.mois_retard AS "moisRetard",
            vb.retard_depuis AS "retardDepuis", vb.cree_le AS "creeLe", vb.cree_par AS "creePar\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public LocationRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ occupants
    public JsonNode occupants() {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.nom, t.prenoms), '[]')::text FROM (SELECT "
                + OCCUPANT + " FROM loc.occupant o WHERE o.archive_le IS NULL) t", String.class));
    }

    public Optional<JsonNode> occupant(int id) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + OCCUPANT + " FROM loc.occupant o WHERE o.id = ?) t",
                String.class, id).stream().findFirst().map(json::lire);
    }

    public int creerOccupant(OccupantSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO loc.occupant (type_personne, nom, prenoms, telephone, telephone_2, email, piece_type, piece_numero,
                    profession, employeur, garant_nom, garant_telephone, notes, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.typePersonne(), s.nom(), s.prenoms(), s.telephone(), s.telephone2(), s.email(), s.pieceType(), s.pieceNumero(),
                s.profession(), s.employeur(), s.garantNom(), s.garantTelephone(), s.notes(), auteur);
        return id == null ? 0 : id;
    }

    public int modifierOccupant(OccupantSaisieDto s, String auteur) {
        return jdbc.update("""
                UPDATE loc.occupant SET type_personne = ?, nom = ?, prenoms = ?, telephone = ?, telephone_2 = ?, email = ?,
                    piece_type = ?, piece_numero = ?, profession = ?, employeur = ?, garant_nom = ?, garant_telephone = ?,
                    notes = ?, maj_le = now(), maj_par = ?
                WHERE id = ?""",
                s.typePersonne(), s.nom(), s.prenoms(), s.telephone(), s.telephone2(), s.email(), s.pieceType(), s.pieceNumero(),
                s.profession(), s.employeur(), s.garantNom(), s.garantTelephone(), s.notes(), auteur, s.id());
    }

    // ------------------------------------------------------------------ baux
    /**
     * Les baux du périmètre. {@code filtre} : « actifs » (actifs et en préavis), « retard »
     * (actifs en retard), ou nul pour tous. {@code idLot}, {@code idOccupant} restreignent encore.
     */
    public JsonNode baux(String perimetre, List<Object> paramsPerimetre, boolean contacts, String filtre, Integer idLot,
                         Integer idOccupant) {
        return baux(perimetre, paramsPerimetre, contacts, filtre, idLot, idOccupant, null);
    }

    public JsonNode baux(String perimetre, List<Object> paramsPerimetre, boolean contacts, String filtre, Integer idLot,
                         Integer idOccupant, Integer idBatiment) {
        List<Object> params = new ArrayList<>();
        params.add(contacts);
        params.addAll(paramsPerimetre);
        StringBuilder sql = new StringBuilder("SELECT coalesce(json_agg(t), '[]')::text FROM (SELECT ").append(BAIL)
                .append(" FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment WHERE ").append(perimetre);
        if ("actifs".equals(filtre)) {
            sql.append(" AND vb.statut IN ('ACTIF', 'PREAVIS')");
        } else if ("retard".equals(filtre)) {
            sql.append(" AND vb.mois_retard > 0");
        }
        if (idLot != null) {
            sql.append(" AND vb.id_lot = ?");
            params.add(idLot);
        }
        if (idOccupant != null) {
            sql.append(" AND vb.id_occupant = ?");
            params.add(idOccupant);
        }
        if (idBatiment != null) {
            sql.append(" AND vb.id_batiment = ?");
            params.add(idBatiment);
        }
        sql.append(" ORDER BY (vb.statut IN ('ACTIF', 'PREAVIS')) DESC, vb.impaye DESC, v.nom, vb.numero_lot, vb.date_effet DESC) t");
        return json.lire(jdbc.queryForObject(sql.toString(), String.class, params.toArray()));
    }

    public Optional<JsonNode> bail(int id, String perimetre, List<Object> paramsPerimetre, boolean contacts) {
        List<Object> params = new ArrayList<>();
        params.add(contacts);
        params.addAll(paramsPerimetre);
        params.add(id);
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + BAIL
                + " FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment WHERE " + perimetre + " AND vb.id = ?) t",
                String.class, params.toArray()).stream().findFirst().map(json::lire);
    }

    public Optional<Integer> bailActifDuLot(int idLot) {
        return jdbc.queryForList("SELECT id FROM loc.bail WHERE id_lot = ? AND statut IN ('ACTIF', 'PREAVIS')", Integer.class, idLot)
                .stream().findFirst();
    }

    public Optional<Integer> lotDuBail(int idBail) {
        return jdbc.queryForList("SELECT id_lot FROM loc.bail WHERE id = ?", Integer.class, idBail).stream().findFirst();
    }

    public int creerBail(BailSaisieDto s, int idOccupant, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO loc.bail (id_lot, id_occupant, date_effet, duree_mois, loyer, charges, caution, avance_mois,
                    jour_exigibilite, notes, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.idLot(), idOccupant, s.dateEffet(), s.dureeMois(), s.loyer(), s.charges(), s.caution(), s.avanceMois(),
                s.jourExigibilite(), s.notes(), auteur);
        return id == null ? 0 : id;
    }

    public void preavis(int id, LocalDate sortiePrevue, String motif, String auteur) {
        jdbc.update("""
                UPDATE loc.bail SET statut = 'PREAVIS', date_preavis = current_date, date_sortie = ?, motif_fin = ?,
                    maj_le = now(), maj_par = ?
                WHERE id = ?""", sortiePrevue, motif, auteur, id);
    }

    public void terminer(int id, LocalDate sortie, String motif, String auteur) {
        jdbc.update("""
                UPDATE loc.bail SET statut = 'RESILIE', date_sortie = ?, motif_fin = coalesce(?, motif_fin),
                    maj_le = now(), maj_par = ?
                WHERE id = ?""", sortie, motif, auteur, id);
    }

    /** Pose le statut d'un lot, en même temps que la vie de son bail. */
    public void statutLot(int idLot, String statut, String auteur) {
        jdbc.update("UPDATE pat.lot SET statut = ?, maj_le = now(), maj_par = ? WHERE id = ?", statut, auteur, idLot);
    }

    // ------------------------------------------------------------------ échéances
    /** Produit les échéances manquantes jusqu'à ce jour ; rend leur nombre. */
    public int genererEcheances(LocalDate jusqua, Integer idBail) {
        Integer n = jdbc.queryForObject("SELECT loc.generer_echeances(?, ?)", Integer.class, jusqua, idBail);
        return n == null ? 0 : n;
    }

    /** Les échéances d'un bail, la plus récente d'abord, avec ce qui en est réglé. */
    public JsonNode echeances(int idBail) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.periode DESC), '[]')::text FROM (
                    SELECT id, periode, date_exigibilite AS "dateExigibilite", loyer, charges, montant_du AS "montantDu",
                           regle, reste, statut_paiement AS "statutPaiement", en_retard AS "enRetard"
                    FROM loc.v_echeance WHERE id_bail = ?) t""", String.class, idBail));
    }

    /** Les échéances d'un bail dans l'ordre où les paiements les soldent : la plus ancienne d'abord. */
    public List<long[]> echeancesBrutes(int idBail) {
        return jdbc.query("SELECT extract(epoch FROM periode)::bigint, montant_du FROM loc.echeance WHERE id_bail = ? ORDER BY periode",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, idBail);
    }

    // ------------------------------------------------------------------ encaissements
    public JsonNode encaissements(int idBail) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t."datePaiement" DESC, t.id DESC), '[]')::text FROM (
                    SELECT id, numero, date_paiement AS "datePaiement", montant, mode, reference_paiement AS "referencePaiement",
                           notes, origine, annule_le AS "annuleLe", annule_par AS "annulePar",
                           motif_annulation AS "motifAnnulation", cree_le AS "creeLe", cree_par AS "creePar"
                    FROM loc.encaissement WHERE id_bail = ?) t""", String.class, idBail));
    }

    public Optional<JsonNode> encaissement(long id) {
        return jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM (
                    SELECT id, numero, id_bail AS "idBail", date_paiement AS "datePaiement", montant, mode,
                           reference_paiement AS "referencePaiement", notes, origine, annule_le AS "annuleLe",
                           annule_par AS "annulePar", motif_annulation AS "motifAnnulation", cree_le AS "creeLe", cree_par AS "creePar"
                    FROM loc.encaissement WHERE id = ?) t""", String.class, id).stream().findFirst().map(json::lire);
    }

    public long creerEncaissement(EncaissementSaisieDto s, String origine, String auteur) {
        Long id = jdbc.queryForList("""
                INSERT INTO loc.encaissement (id_bail, date_paiement, montant, mode, reference_paiement, notes, origine, cree_par,
                                              cle, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cle) DO NOTHING RETURNING id""", Long.class,
                s.idBail(), s.datePaiement(), s.montant(), s.mode(), s.referencePaiement(), s.notes(), origine, auteur,
                s.cle(), s.latitude(), s.longitude()).stream().findFirst().orElse(null);
        return id == null ? 0 : id;
    }

    /** L'encaissement déjà créé par cet envoi du téléphone, s'il a abouti. */
    public Optional<Long> encaissementParCle(java.util.UUID cle) {
        return jdbc.queryForList("SELECT id FROM loc.encaissement WHERE cle = ?", Long.class, cle).stream().findFirst();
    }

    /** L'encaissement dont le locataire ouvre le reçu par son lien. */
    public Optional<Long> encaissementParJeton(java.util.UUID jeton) {
        return jdbc.queryForList("SELECT id FROM loc.encaissement WHERE jeton_recu = ?", Long.class, jeton).stream().findFirst();
    }

    /** De quoi envoyer le reçu : contacts du locataire, montant, lot, et le jeton du lien. */
    public Optional<JsonNode> pourRecu(long idEncaissement) {
        return jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM (
                    SELECT e.id, e.numero, e.montant, e.date_paiement AS "datePaiement", e.jeton_recu AS jeton,
                           btrim(o.nom || ' ' || coalesce(o.prenoms, '')) AS occupant, o.telephone, o.email,
                           l.numero AS "numeroLot", b.nom AS batiment
                    FROM loc.encaissement e
                    JOIN loc.bail bl ON bl.id = e.id_bail
                    JOIN loc.occupant o ON o.id = bl.id_occupant
                    JOIN pat.lot l ON l.id = bl.id_lot
                    JOIN pat.batiment b ON b.id = l.id_batiment
                    WHERE e.id = ?) t""", String.class, idEncaissement).stream().findFirst().map(json::lire);
    }

    public int annuler(long id, String motif, String auteur) {
        return jdbc.update("""
                UPDATE loc.encaissement SET annule_le = now(), annule_par = ?, motif_annulation = ?
                WHERE id = ? AND annule_le IS NULL""", auteur, motif, id);
    }

    /** Ce que les encaissements valides du bail ont versé avant celui-ci (ordre : date, puis saisie). */
    public long payeAvant(int idBail, long idEncaissement) {
        Long n = jdbc.queryForObject("""
                SELECT coalesce(sum(e.montant), 0) FROM loc.encaissement e, loc.encaissement x
                WHERE x.id = ? AND e.id_bail = ? AND e.annule_le IS NULL
                  AND (e.date_paiement, e.id) < (x.date_paiement, x.id)""", Long.class, idEncaissement, idBail);
        return n == null ? 0 : n;
    }

    /** Les derniers encaissements du périmètre, pour le suivi des loyers. */
    public JsonNode derniersEncaissements(String perimetre, List<Object> paramsPerimetre, int limite) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        params.add(limite);
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t), '[]')::text FROM (
                    SELECT e.id, e.numero, e.date_paiement AS "datePaiement", e.montant, e.mode, e.annule_le AS "annuleLe",
                           vb.id AS "idBail", vb.occupant, vb.numero_lot AS "numeroLot", v.nom AS batiment, v.id AS "idBatiment",
                           e.cree_par AS "creePar"
                    FROM loc.encaissement e
                    JOIN loc.v_bail vb ON vb.id = e.id_bail
                    JOIN pat.v_batiment v ON v.id = vb.id_batiment
                    WHERE\s""" + perimetre + " ORDER BY e.date_paiement DESC, e.id DESC LIMIT ?) t", String.class, params.toArray()));
    }

    /**
     * La synthèse des loyers du périmètre : pour chacun des {@code mois} derniers mois, l'attendu,
     * ce qui en est réglé et ce qui a été encaissé ; à ce jour, les impayés et les baux en retard.
     */
    public JsonNode synthese(String perimetre, List<Object> paramsPerimetre, int mois) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        params.add(mois - 1);
        params.addAll(paramsPerimetre);
        params.addAll(paramsPerimetre);
        params.addAll(paramsPerimetre);
        return json.lire(jdbc.queryForObject("""
                WITH perimetre AS (SELECT v.id FROM pat.v_batiment v WHERE\s""" + perimetre + """
                ), mois AS (
                    SELECT generate_series(date_trunc('month', current_date) - make_interval(months => ?), date_trunc('month', current_date),
                                           interval '1 month')::date AS periode
                ), ech AS (
                    SELECT ve.periode, sum(ve.montant_du) AS attendu, sum(ve.regle) AS regle
                    FROM loc.v_echeance ve JOIN loc.v_bail vb ON vb.id = ve.id_bail
                    WHERE vb.id_batiment IN (SELECT id FROM perimetre) GROUP BY ve.periode
                ), enc AS (
                    SELECT date_trunc('month', e.date_paiement)::date AS periode, sum(e.montant) AS encaisse
                    FROM loc.encaissement e JOIN loc.v_bail vb ON vb.id = e.id_bail
                    WHERE e.annule_le IS NULL AND vb.id_batiment IN (SELECT id FROM perimetre) GROUP BY 1
                )
                SELECT json_build_object(
                    'mois', (SELECT coalesce(json_agg(json_build_object('periode', m.periode, 'attendu', coalesce(ech.attendu, 0),
                                 'regle', coalesce(ech.regle, 0), 'encaisse', coalesce(enc.encaisse, 0)) ORDER BY m.periode), '[]')
                             FROM mois m LEFT JOIN ech USING (periode) LEFT JOIN enc USING (periode)),
                    'impaye', (SELECT coalesce(sum(vb.impaye), 0) FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment
                               WHERE\s""" + perimetre + """
                    ),
                    'bauxActifs', (SELECT count(*) FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment
                                   WHERE vb.statut IN ('ACTIF', 'PREAVIS') AND\s""" + perimetre + """
                    ),
                    'bauxEnRetard', (SELECT count(*) FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment
                                     WHERE vb.mois_retard > 0 AND\s""" + perimetre + """
                    )
                )::text""", String.class, params.toArray()));
    }

    /** Tout ce que porte une quittance : encaissement, bail, lot, bâtiment, occupant, propriétaire. */
    public Optional<JsonNode> pourQuittance(long idEncaissement) {
        return jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM (
                    SELECT e.id, e.numero, e.id_bail AS "idBail", e.date_paiement AS "datePaiement", e.montant, e.mode,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'MODE_PAIEMENT' AND code = e.mode) AS "libelleMode",
                           e.reference_paiement AS "referencePaiement", e.annule_le AS "annuleLe", e.motif_annulation AS "motifAnnulation",
                           e.cree_par AS "creePar",
                           vb.reference AS "referenceBail", vb.occupant, vb.reference_occupant AS "referenceOccupant",
                           vb.numero_lot AS "numeroLot", vb.etage, vb.loyer, vb.charges, vb.solde, vb.id_batiment AS "idBatiment",
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = vb.type_lot) AS "libelleTypeLot",
                           v.nom AS batiment, v.adresse, v.quartier, v.commune, v.proprietaire
                    FROM loc.encaissement e
                    JOIN loc.v_bail vb ON vb.id = e.id_bail
                    JOIN pat.v_batiment v ON v.id = vb.id_batiment
                    WHERE e.id = ?) t""", String.class, idEncaissement).stream().findFirst().map(json::lire);
    }

    /** Le bâtiment d'un bail, pour contrôler le périmètre avant toute écriture. */
    public Optional<Integer> batimentDuBail(int idBail) {
        return jdbc.queryForList("SELECT l.id_batiment FROM loc.bail b JOIN pat.lot l ON l.id = b.id_lot WHERE b.id = ?",
                Integer.class, idBail).stream().findFirst();
    }
}
