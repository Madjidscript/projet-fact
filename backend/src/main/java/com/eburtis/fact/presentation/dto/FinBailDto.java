package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;

/** Préavis ({@code dateSortie} prévue) ou fin d'un bail ({@code dateSortie} effective), avec son motif. */
public record FinBailDto(LocalDate dateSortie, String motif) {
}
