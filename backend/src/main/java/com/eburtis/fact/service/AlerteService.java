package com.eburtis.fact.service;

import com.eburtis.fact.repository.AlerteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Les alertes automatiques du § 4.7. Elles sont recalculées toutes les dix minutes (et une
 * minute après le démarrage, une fois les échéances de loyer produites), puis aussitôt après
 * chaque signalement d'intervention. Le canal est l'application : les envois par e-mail, SMS
 * ou WhatsApp attendent les comptes d'envoi de l'agence.
 */
@Service
public class AlerteService {

    private static final Logger LOG = LoggerFactory.getLogger(AlerteService.class);

    private final AlerteRepository alerteRepository;

    public AlerteService(AlerteRepository alerteRepository) {
        this.alerteRepository = alerteRepository;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void generer() {
        int n = alerteRepository.generer();
        if (n > 0) {
            LOG.info("{} alerte(s) créée(s)", n);
        }
    }
}
