package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;

/** La fin anticipée d'un mandat : sa date et son motif. */
public record ResiliationDto(LocalDate date, String motif) {
}
