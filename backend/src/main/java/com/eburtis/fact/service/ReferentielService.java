package com.eburtis.fact.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.domain.Commune;
import com.eburtis.fact.domain.Quartier;
import com.eburtis.fact.domain.Valeur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.repository.ReferentielRepository;

import org.springframework.stereotype.Service;

/**
 * Contrôle des valeurs saisies contre les listes de référence (ref.valeur), et rapprochement
 * des libellés écrits à la main dans un fichier Excel (« Cocody », « cocody », « COCODY »).
 */
@Service
public class ReferentielService {

    private final ReferentielRepository referentielRepository;

    public ReferentielService(ReferentielRepository referentielRepository) {
        this.referentielRepository = referentielRepository;
    }

    /** Refuse un code qui n'est pas dans la liste du domaine. */
    public void exiger(String domaine, String code, String quoi) {
        if (code == null || code.isBlank()) {
            throw ErreurMetier.invalide(quoi + " : valeur obligatoire.");
        }
        if (valeurs(domaine).stream().noneMatch(v -> v.code().equals(code))) {
            throw ErreurMetier.invalide(quoi + " : valeur inconnue (" + code + ").");
        }
    }

    public List<Valeur> valeurs(String domaine) {
        return referentielRepository.valeurs().stream().filter(v -> v.domaine().equals(domaine)).toList();
    }

    /** Le code d'un libellé ou d'un code écrit librement, sans égard à la casse ni aux accents. */
    public Optional<String> code(String domaine, String texte) {
        String cle = cle(texte);
        if (cle.isEmpty()) {
            return Optional.empty();
        }
        return valeurs(domaine).stream()
                .filter(v -> cle(v.code()).equals(cle) || cle(v.libelle()).equals(cle))
                .map(Valeur::code).findFirst();
    }

    /** Les libellés des communes, dans leur ordre d'affichage. */
    public List<String> communes() {
        return referentielRepository.communes().stream().map(Commune::libelle).toList();
    }

    public Optional<Commune> commune(String texte) {
        String cle = cle(texte);
        return referentielRepository.communes().stream()
                .filter(c -> cle(c.libelle()).equals(cle) || cle(c.code()).equals(cle)
                        || cle(c.libelle().replaceFirst("^Le ", "")).equals(cle))
                .findFirst();
    }

    public Optional<Quartier> quartier(int idCommune, String texte) {
        String cle = cle(texte);
        return referentielRepository.quartiers().stream()
                .filter(q -> q.idCommune() == idCommune && cle(q.libelle()).equals(cle)).findFirst();
    }

    /** Minuscules, sans accent, sans ponctuation ni espaces multiples : la forme de comparaison. */
    public static String cle(String texte) {
        if (texte == null) {
            return "";
        }
        String sansAccent = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccent.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }
}
