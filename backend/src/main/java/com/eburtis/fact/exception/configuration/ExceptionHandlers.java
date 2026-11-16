package com.eburtis.fact.exception.configuration;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eburtis.fact.exception.ErreurMetier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Toutes les erreurs de l'API sortent sous la même forme : {@code {statut, message, code}}.
 * Les refus de la base (contraintes, règles) sont rendus lisibles : les messages rédigés en
 * français dans les scripts SQL sont transmis tels quels.
 */
@RestControllerAdvice
public class ExceptionHandlers {

    private static final Logger LOG = LoggerFactory.getLogger(ExceptionHandlers.class);
    private static final Pattern CONTRAINTE = Pattern.compile("constraint \"([^\"]+)\"");

    /** Les contraintes nommées des scripts SQL, dites en clair. */
    private static final Map<String, String> CONTRAINTES = Map.ofEntries(
            Map.entry("chk_compte_identifiant",
                    "L'identifiant compte 3 à 40 caractères : lettres minuscules, chiffres, point, tiret."),
            Map.entry("chk_compte_email", "L'adresse e-mail n'est pas valide."),
            Map.entry("chk_compte_profil", "Ce profil n'existe pas."),
            Map.entry("compte_identifiant_key", "Cet identifiant est déjà attribué."),
            Map.entry("chk_intervention_titre", "Décrivez la panne en quelques mots (titre)."),
            Map.entry("chk_intervention_montants", "Le devis et le coût ne peuvent pas être négatifs."),
            Map.entry("chk_intervention_imputation", "L'imputation est obligatoire pour clôturer."),
            Map.entry("chk_intervention_annulation", "Le motif de l'annulation est obligatoire."),
            Map.entry("chk_prestataire_nom", "Le nom du prestataire est obligatoire."),
            Map.entry("chk_prestataire_telephone", "Le téléphone du prestataire n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("uq_bail_actif", "Ce lot a déjà un bail actif : terminez-le avant d'en signer un autre."),
            Map.entry("chk_bail_duree", "La durée du bail doit être comprise entre 1 et 240 mois."),
            Map.entry("chk_bail_montants", "Le loyer doit être supérieur à zéro ; charges et caution ne peuvent pas être négatives."),
            Map.entry("chk_bail_avance", "L'avance doit être comprise entre 0 et 24 mois."),
            Map.entry("chk_bail_exigibilite", "Le jour d'exigibilité doit être compris entre 1 et 28."),
            Map.entry("chk_bail_sortie", "La date de sortie ne peut pas précéder le début du bail."),
            Map.entry("chk_encaissement_montant", "Le montant doit être supérieur à zéro."),
            Map.entry("chk_occupant_telephone", "Le téléphone de l'occupant n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("chk_occupant_telephone_2", "Le second téléphone n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("chk_occupant_garant_telephone", "Le téléphone du garant n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("chk_occupant_email", "L'adresse e-mail de l'occupant n'est pas valide."),
            Map.entry("uq_lot_numero", "Ce numéro de porte existe déjà dans ce bâtiment."),
            Map.entry("chk_lot_surface", "La surface doit être supérieure à zéro."),
            Map.entry("chk_lot_pieces", "Le nombre de pièces, de chambres ou de salles d'eau ne peut pas être négatif."),
            Map.entry("chk_lot_montants", "Le loyer et les charges ne peuvent pas être négatifs."),
            Map.entry("chk_lot_etage", "L'étage doit être compris entre -5 et 60."),
            Map.entry("chk_batiment_position", "Cette position est hors de Côte d'Ivoire : latitude et longitude sont peut-être inversées."),
            Map.entry("chk_batiment_niveaux", "Le nombre de niveaux doit être compris entre 0 et 60."),
            Map.entry("chk_batiment_annee", "L'année de construction n'est pas vraisemblable."),
            Map.entry("chk_batiment_suppleant", "Le suppléant doit être une autre personne que le gestionnaire."),
            Map.entry("chk_proprietaire_telephone", "Le numéro de téléphone n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("chk_proprietaire_telephone_2", "Le second numéro de téléphone n'est pas valide : 8 à 19 chiffres, espaces permis, « + » en tête."),
            Map.entry("chk_proprietaire_email", "L'adresse e-mail n'est pas valide."),
            Map.entry("proprietaire_id_compte_key", "Ce compte est déjà rattaché à un autre propriétaire."),
            Map.entry("commune_code_key", "Ce code de commune est déjà attribué."),
            Map.entry("quartier_code_key", "Ce code de quartier est déjà attribué."),
            Map.entry("adm_quartier_id_commune_libelle_key", "Ce quartier existe déjà dans cette commune."));

    @ExceptionHandler(ErreurMetier.class)
    public ResponseEntity<Map<String, Object>> metier(ErreurMetier e) {
        return reponse(e.getStatut(), e.getMessage(), e.getCode());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> requete(Exception e) {
        return reponse(HttpStatus.BAD_REQUEST, "La demande est mal formée.", null);
    }

    /** Photo au-delà de spring.servlet.multipart.max-file-size ou max-request-size. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tropLourd(MaxUploadSizeExceededException e) {
        return reponse(HttpStatus.PAYLOAD_TOO_LARGE, "Le fichier est trop lourd : 12 Mo au plus par photo.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> absente(NoResourceFoundException e) {
        return reponse(HttpStatus.NOT_FOUND, "Adresse inconnue.", null);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> base(DataAccessException e) {
        SQLException sql = cause(e);
        if (sql == null || sql.getSQLState() == null) {
            LOG.error("Erreur d'accès à la base", e);
            return reponse(HttpStatus.SERVICE_UNAVAILABLE, "La base de données ne répond pas. Réessayez dans un instant.", null);
        }
        String etat = sql.getSQLState();
        String detail = premiereLigne(sql.getMessage());
        String message = switch (etat.substring(0, 2)) {
            case "23" -> traduireContrainte(etat, detail);
            case "22" -> "Une valeur saisie n'a pas le bon format : " + detail;
            case "P0" -> detail;                  // RAISE EXCEPTION des règles de la base
            default -> null;
        };
        if (message == null) {
            LOG.error("Erreur SQL {} : {}", etat, sql.getMessage());
            return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "La base de données a refusé l'opération.", etat);
        }
        return reponse(HttpStatus.UNPROCESSABLE_ENTITY, message, etat);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inattendue(Exception e) {
        LOG.error("Erreur inattendue", e);
        return reponse(HttpStatus.INTERNAL_SERVER_ERROR, "Une erreur inattendue est survenue.", null);
    }

    private String traduireContrainte(String etat, String detail) {
        Matcher m = CONTRAINTE.matcher(detail);
        if (!m.find()) {
            return detail;                       // message rédigé par une règle de la base
        }
        String contrainte = m.group(1);
        if (CONTRAINTES.containsKey(contrainte)) {
            return CONTRAINTES.get(contrainte);
        }
        return switch (etat) {
            case "23505" -> "Cette valeur existe déjà : elle doit être unique.";
            case "23503" -> "Cette fiche est liée à d'autres données : l'opération est impossible.";
            case "23502" -> "Un champ obligatoire n'est pas renseigné.";
            default -> detail;
        };
    }

    private static SQLException cause(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s) {
                return s;
            }
            t = t.getCause();
        }
        return null;
    }

    private static String premiereLigne(String texte) {
        if (texte == null) {
            return "";
        }
        String ligne = texte.split("\n")[0];
        return ligne.startsWith("ERROR: ") ? ligne.substring(7) : ligne;
    }

    private static ResponseEntity<Map<String, Object>> reponse(HttpStatus statut, String message, String code) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("statut", statut.value());
        corps.put("message", message);
        corps.put("code", code);
        return ResponseEntity.status(statut).body(corps);
    }
}
