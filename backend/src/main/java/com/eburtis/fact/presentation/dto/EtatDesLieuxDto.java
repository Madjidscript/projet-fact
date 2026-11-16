package com.eburtis.fact.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Un état des lieux saisi sur le téléphone, peut-être hors connexion. Les photos arrivent à part,
 * nommées « p{rang de la pièce}-{n}.jpg » ; les deux signatures arrivent en images PNG.
 */
public record EtatDesLieuxDto(UUID cle, Integer idBail, String type, OffsetDateTime faitLe, List<Piece> pieces,
                              Map<String, String> compteurs, Integer clesRemises, String observations, String signataire,
                              Double latitude, Double longitude) {

    public record Piece(String piece, List<Element> elements, String note) {
    }

    /** Un élément d'une pièce : son état (BON, USAGE, MAUVAIS, HORS_SERVICE) et une note. */
    public record Element(String nom, String etat, String note) {
    }
}
