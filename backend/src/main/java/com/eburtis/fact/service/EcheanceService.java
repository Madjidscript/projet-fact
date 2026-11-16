package com.eburtis.fact.service;

import java.time.LocalDate;

import com.eburtis.fact.repository.LocationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * « Chaque mois, l'application génère le loyer attendu pour chaque bail actif » (§ 4.4).
 * La production tourne au démarrage et chaque nuit : une échéance déjà produite n'est jamais
 * recréée, l'opération peut donc être rejouée sans risque (un serveur arrêté plusieurs jours
 * rattrape les mois manqués à son redémarrage).
 */
@Service
public class EcheanceService implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EcheanceService.class);

    private final LocationRepository locationRepository;

    public EcheanceService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        produire();
    }

    @Scheduled(cron = "0 15 0 * * *")
    public void produire() {
        int n = locationRepository.genererEcheances(LocalDate.now(), null);
        if (n > 0) {
            LOG.info("{} échéance(s) de loyer produite(s)", n);
        }
    }
}
