package com.eburtis.fact.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * La mémoire, le journal des tours et les validations de l'assistant
 * (tables app.assistant_message, app.assistant_tour, app.assistant_validation).
 * Chaque requête filtre sur id_compte : un compte ne lit jamais les données d'un autre.
 */
@Repository
public class AssistantRepository {

    private final JdbcTemplate jdbc;

    public AssistantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ la mémoire
    public void enregistrer(int idCompte, String role, String texte) {
        jdbc.update("INSERT INTO app.assistant_message (id_compte, role, texte) VALUES (?, ?, ?)", idCompte, role, texte);
    }

    /**
     * Les {@code nombre} derniers messages du compte, du plus ancien au plus récent :
     * c'est l'ordre dans lequel le modèle doit les lire.
     */
    public List<Map<String, Object>> derniers(int idCompte, int nombre) {
        return jdbc.queryForList("""
                SELECT role, texte, cree_le AS "creeLe" FROM (
                    SELECT id, role, texte, cree_le FROM app.assistant_message
                    WHERE id_compte = ? ORDER BY id DESC LIMIT ?) t
                ORDER BY id""", idCompte, nombre);
    }

    /** « Nouvelle conversation » : le compte efface son propre fil. */
    public int effacer(int idCompte) {
        return jdbc.update("DELETE FROM app.assistant_message WHERE id_compte = ?", idCompte);
    }

    // ------------------------------------------------------------ le journal des tours
    public void enregistrerTour(int idCompte, UUID tache, int tour, String outils, long dureeMs, int tokensIn, int tokensOut,
                                String erreur) {
        jdbc.update("""
                INSERT INTO app.assistant_tour (id_compte, tache, tour, outils, duree_ms, tokens_in, tokens_out, erreur)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                idCompte, tache, tour, tronquer(outils, 400), (int) Math.min(dureeMs, Integer.MAX_VALUE), tokensIn, tokensOut,
                tronquer(erreur, 400));
    }

    /** Les tokens consommés par le compte depuis minuit (heure d'Abidjan, qui est UTC). */
    public long tokensDuJour(int idCompte) {
        Long n = jdbc.queryForObject("""
                SELECT coalesce(sum(tokens_in + tokens_out), 0) FROM app.assistant_tour
                WHERE id_compte = ? AND cree_le >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'""",
                Long.class, idCompte);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- les validations
    public long creerValidation(int idCompte, String outil, String parametres, String empreinte, String resume, Instant expireLe) {
        Long id = jdbc.queryForObject("""
                INSERT INTO app.assistant_validation (id_compte, outil, parametres, empreinte, resume, expire_le)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, idCompte, outil, parametres, empreinte, resume, Timestamp.from(expireLe));
        return id == null ? 0 : id;
    }

    /** Une validation du compte, et seulement du compte : celle d'un autre est « introuvable ». */
    public Optional<Map<String, Object>> validation(long id, int idCompte) {
        return jdbc.queryForList("""
                SELECT id, outil, parametres, empreinte, resume, statut, expire_le AS "expireLe", resultat
                FROM app.assistant_validation WHERE id = ? AND id_compte = ?""", id, idCompte).stream().findFirst();
    }

    /**
     * Passe une validation EN_ATTENTE à un autre statut. Renvoie 0 si elle n'était plus en attente :
     * deux clics rapides sur « Valider » n'exécutent donc l'action qu'une fois.
     */
    public int terminerValidation(long id, int idCompte, String statut, String resultat) {
        return jdbc.update("""
                UPDATE app.assistant_validation SET statut = ?, resultat = ?, traite_le = now()
                WHERE id = ? AND id_compte = ? AND statut = 'EN_ATTENTE'""", statut, resultat, id, idCompte);
    }

    private static String tronquer(String texte, int max) {
        return texte == null || texte.length() <= max ? texte : texte.substring(0, max);
    }
}
