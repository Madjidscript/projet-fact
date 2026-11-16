package com.eburtis.fact.exception;

import org.springframework.http.HttpStatus;

/** Une erreur dont le message, en français, est destiné à la personne connectée. */
public class ErreurMetier extends RuntimeException {

    private final HttpStatus statut;
    private final String code;

    public ErreurMetier(HttpStatus statut, String message) {
        this(statut, null, message);
    }

    public ErreurMetier(HttpStatus statut, String code, String message) {
        super(message);
        this.statut = statut;
        this.code = code;
    }

    public HttpStatus getStatut() {
        return statut;
    }

    public String getCode() {
        return code;
    }

    public static ErreurMetier introuvable(String message) {
        return new ErreurMetier(HttpStatus.NOT_FOUND, message);
    }

    public static ErreurMetier refuse(String message) {
        return new ErreurMetier(HttpStatus.FORBIDDEN, message);
    }

    public static ErreurMetier invalide(String message) {
        return new ErreurMetier(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
