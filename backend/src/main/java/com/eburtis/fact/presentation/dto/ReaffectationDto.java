package com.eburtis.fact.presentation.dto;

import java.util.List;

/**
 * Une réaffectation (§ 4.3) : les bâtiments choisis, le nouveau gestionnaire (nul : aucun), et le
 * suppléant si {@code changerSuppleant} est vrai ; sinon chaque bâtiment garde le sien.
 */
public record ReaffectationDto(List<Integer> batiments, Integer idGestionnaire, Boolean changerSuppleant, Integer idSuppleant,
                               String motif) {
}
