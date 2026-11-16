package com.eburtis.fact.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.presentation.dto.CompteDto;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Les comptes de la plateforme (table app.compte). Aucun mot de passe n'est gardé : seulement
 * son empreinte BCrypt. Aucun compte n'est supprimé : il est désactivé et reste consultable (§ 8).
 */
@Repository
public class CompteRepository {

    private final JdbcTemplate jdbc;

    public CompteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ lecture
    public Optional<Utilisateur> trouverActif(String identifiant) {
        List<Utilisateur> l = jdbc.query("""
                SELECT id, identifiant, nom, fonction, email, profil, droits, doit_changer, version_session
                FROM app.compte WHERE identifiant = ? AND actif""", (rs, i) -> new Utilisateur(
                rs.getInt("id"), rs.getString("identifiant"), rs.getString("nom"), rs.getString("fonction"),
                rs.getString("email"), Profil.valueOf(rs.getString("profil")),
                new LinkedHashSet<>(tableau(rs.getArray("droits"))),
                rs.getBoolean("doit_changer"), rs.getInt("version_session")), identifiant);
        return l.stream().findFirst();
    }

    /** L'identifiant du compte actif qui porte cet e-mail, s'il est le seul à le porter. */
    public Optional<String> identifiantParEmail(String email) {
        List<String> l = jdbc.queryForList("SELECT identifiant FROM app.compte WHERE lower(email) = lower(?) AND actif", String.class, email);
        return l.size() == 1 ? Optional.of(l.get(0)) : Optional.empty();
    }

    public List<CompteDto> lister() {
        return jdbc.query("""
                SELECT id, identifiant, nom, fonction, email, telephone, profil, droits, actif, doit_changer,
                       derniere_connexion, cree_le
                FROM app.compte ORDER BY actif DESC, nom""", (rs, i) -> dto(rs));
    }

    public Optional<CompteDto> trouver(String identifiant) {
        return jdbc.query("""
                SELECT id, identifiant, nom, fonction, email, telephone, profil, droits, actif, doit_changer,
                       derniere_connexion, cree_le
                FROM app.compte WHERE identifiant = ?""", (rs, i) -> dto(rs), identifiant).stream().findFirst();
    }

    public boolean aucunCompte() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app.compte", Integer.class);
        return n == null || n == 0;
    }

    /** Le nombre de comptes actifs qui peuvent gérer les comptes : il doit toujours en rester un. */
    public int administrateursActifs() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app.compte WHERE actif AND profil = 'ADMIN'", Integer.class);
        return n == null ? 0 : n;
    }

    /** L'empreinte du mot de passe d'un compte actif ; EmptyResultDataAccessException si le compte n'existe pas ou est inactif. */
    public String empreinteActive(String identifiant) {
        return jdbc.queryForObject("SELECT mot_de_passe FROM app.compte WHERE identifiant = ? AND actif", String.class, identifiant);
    }

    public String empreinte(int id) {
        return jdbc.queryForObject("SELECT mot_de_passe FROM app.compte WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ écriture
    public void marquerConnexion(String identifiant) {
        jdbc.update("UPDATE app.compte SET derniere_connexion = now() WHERE identifiant = ?", identifiant);
    }

    /** Nouveau mot de passe choisi par son titulaire : ses autres sessions sont fermées. */
    public void changerMotDePasse(int id, String empreinte) {
        jdbc.update("""
                UPDATE app.compte SET mot_de_passe = ?, doit_changer = false, version_session = version_session + 1, maj_le = now()
                WHERE id = ?""", empreinte, id);
    }

    /** Crée un compte au mot de passe provisoire, à changer à la première connexion. */
    public void creer(String identifiant, String nom, String fonction, String email, String telephone, Profil profil,
                      List<String> droits, String empreinte, boolean actif) {
        jdbc.update("""
                INSERT INTO app.compte (identifiant, nom, fonction, email, telephone, profil, droits, mot_de_passe, actif, doit_changer)
                VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, true)""",
                identifiant, nom, fonction, email, telephone, profil.name(), litteral(droits), empreinte, actif);
    }

    /** Met à jour un compte ; le désactiver, ou changer son profil ou ses droits, ferme ses sessions. */
    public void modifier(String identifiant, String nom, String fonction, String email, String telephone, Profil profil,
                         List<String> droits, boolean actif) {
        jdbc.update("""
                UPDATE app.compte SET nom = ?, fonction = ?, email = ?, telephone = ?, maj_le = now(),
                       version_session = version_session
                           + CASE WHEN (actif AND NOT ?) OR profil <> ? OR droits <> ?::text[] THEN 1 ELSE 0 END,
                       profil = ?, droits = ?::text[], actif = ?
                WHERE identifiant = ?""",
                nom, fonction, email, telephone, actif, profil.name(), litteral(droits),
                profil.name(), litteral(droits), actif, identifiant);
    }

    /** Pose un mot de passe provisoire ; rend le nombre de comptes touchés (0 : compte inconnu). */
    public int reinitialiser(String identifiant, String empreinte) {
        return jdbc.update("""
                UPDATE app.compte SET mot_de_passe = ?, doit_changer = true, version_session = version_session + 1, maj_le = now()
                WHERE identifiant = ?""", empreinte, identifiant);
    }

    /** Création du compte de départ, sans effet si l'identifiant existe déjà. */
    public void creerDeDepart(String identifiant, String nom, Profil profil, String empreinte) {
        jdbc.update("""
                INSERT INTO app.compte (identifiant, nom, fonction, profil, mot_de_passe, doit_changer)
                VALUES (?, ?, ?, ?, ?, true) ON CONFLICT (identifiant) DO NOTHING""",
                identifiant, nom, profil.libelle(), profil.name(), empreinte);
    }

    // ------------------------------------------------------------------ outils
    private static String litteral(List<String> valeurs) {
        return "{" + String.join(",", valeurs) + "}";
    }

    private static List<String> tableau(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.asList((String[]) a.getArray());
    }

    private static CompteDto dto(ResultSet rs) throws SQLException {
        String profil = rs.getString("profil");
        return new CompteDto(rs.getInt("id"), rs.getString("identifiant"), rs.getString("nom"), rs.getString("fonction"),
                rs.getString("email"), rs.getString("telephone"), profil,
                Profil.parCode(profil).map(Profil::libelle).orElse(profil),
                tableau(rs.getArray("droits")), rs.getBoolean("actif"), rs.getBoolean("doit_changer"),
                rs.getObject("derniere_connexion", OffsetDateTime.class), rs.getObject("cree_le", OffsetDateTime.class));
    }
}
