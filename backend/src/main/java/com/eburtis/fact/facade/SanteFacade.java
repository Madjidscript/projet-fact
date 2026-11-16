package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eburtis.fact.repository.SanteRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * État de la plateforme : version de l'application, base joignable, migrations appliquées.
 * C'est l'adresse que l'application Angular interroge au démarrage pour montrer que les
 * trois briques communiquent (étape 1 du plan de réalisation).
 */
@Service
public class SanteFacade {

    private final SanteRepository santeRepository;
    private final String version;
    private final String sha1;

    public SanteFacade(SanteRepository santeRepository,
                       @Value("${app.version:dev}") String version,
                       @Value("${app.git.sha1:}") String sha1) {
        this.santeRepository = santeRepository;
        this.version = version;
        this.sha1 = sha1;
    }

    public Map<String, Object> sante() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("application", "FACT Immobilier");
        r.put("version", version);
        if (sha1 != null && !sha1.isBlank() && !sha1.startsWith("[")) {
            r.put("revision", sha1.length() > 8 ? sha1.substring(0, 8) : sha1);
        }
        try {
            r.put("base", "disponible");
            r.put("migration", santeRepository.derniereMigration());
            r.put("postgis", santeRepository.versionPostgis());
            r.put("communes", santeRepository.nombreCommunes());
            r.put("quartiers", santeRepository.nombreQuartiers());
        } catch (RuntimeException e) {
            r.put("base", "indisponible");
        }
        return r;
    }
}
