package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.eburtis.fact.configuration.FactProperties;
import com.eburtis.fact.domain.Commune;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Valeur;
import com.eburtis.fact.repository.ReferentielRepository;

import org.springframework.stereotype.Service;

/**
 * Le référentiel servi d'un bloc : communes et leurs quartiers, listes de valeurs par
 * domaine, seuils réglables, cadrage de la carte. Le moteur de recherche du site et les
 * formulaires de l'application s'en remplissent au chargement.
 * <p>
 * Ces données changent rarement : elles sont gardées en mémoire et relues sur demande
 * (l'administration des listes de référence appellera {@link #vider()} après modification).
 */
@Service
public class ReferentielFacade {

    private final ReferentielRepository referentielRepository;
    private final FactProperties proprietes;

    private volatile Map<String, Object> cache;

    public ReferentielFacade(ReferentielRepository referentielRepository, FactProperties proprietes) {
        this.referentielRepository = referentielRepository;
        this.proprietes = proprietes;
    }

    /** Tout le référentiel, tel que l'interface le consomme. */
    public Map<String, Object> referentiel() {
        Map<String, Object> connu = cache;
        if (connu != null) {
            return connu;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("communes", referentielRepository.communes());
        r.put("listes", listes());
        r.put("reglages", referentielRepository.reglages());
        r.put("profils", profils());
        r.put("carte", carte());
        cache = r;
        return r;
    }

    /** Les communes seules, sans leurs quartiers : pour une liste déroulante. */
    public List<Commune> communes() {
        return referentielRepository.communes().stream().map(Commune::sansQuartiers).toList();
    }

    /** Les quartiers d'une commune, pour la liste dépendante du moteur de recherche (§ 3.2). */
    public List<?> quartiers(int idCommune) {
        return referentielRepository.quartiers().stream().filter(q -> q.idCommune() == idCommune).toList();
    }

    /** Les listes de valeurs regroupées par domaine : {@code {"TYPE_LOT": [...], ...}}. */
    public Map<String, List<Valeur>> listes() {
        return referentielRepository.valeurs().stream().collect(Collectors.groupingBy(Valeur::domaine,
                LinkedHashMap::new, Collectors.toList()));
    }

    /** Les profils du § 2.1 et les droits que chacun porte. */
    private List<Map<String, Object>> profils() {
        return java.util.Arrays.stream(Profil.values())
                .map(p -> Map.<String, Object>of(
                        "code", p.name(),
                        "libelle", p.libelle(),
                        "description", p.description(),
                        "portee", p.portee().name(),
                        "droits", p.droits().stream().sorted().toList()))
                .toList();
    }

    private Map<String, Object> carte() {
        FactProperties.Carte c = proprietes.carte();
        return Map.of("latitude", c.latitude(), "longitude", c.longitude(), "zoom", c.zoom());
    }

    /** À appeler dès qu'une liste de référence ou un réglage change. */
    public void vider() {
        cache = null;
    }
}
