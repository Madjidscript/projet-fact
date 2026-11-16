package com.eburtis.fact.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.eburtis.fact.configuration.FactProperties;
import com.eburtis.fact.domain.Profil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au premier démarrage (aucun compte), crée le compte « admin ». Son mot de passe provisoire
 * est écrit dans un fichier, à remettre à l'administrateur puis à détruire ; il doit le changer
 * à la première connexion. Les autres comptes se créent ensuite depuis l'application.
 */
@Component
public class InitialisationComptes implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InitialisationComptes.class);
    private static final String IDENTIFIANT = "admin";

    private final CompteService compteService;
    private final FactProperties proprietes;

    public InitialisationComptes(CompteService compteService, FactProperties proprietes) {
        this.compteService = compteService;
        this.proprietes = proprietes;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!compteService.aucunCompte()) {
            return;
        }
        String impose = proprietes.comptes() == null ? null : proprietes.comptes().motDePasseInitial();
        boolean aleatoire = impose == null || impose.isBlank();
        String motDePasse = aleatoire ? CompteService.motDePasseProvisoire() : impose;
        compteService.creerDeDepart(IDENTIFIANT, "Administrateur", Profil.ADMIN, motDePasse);
        if (!aleatoire) {
            LOG.warn("Compte « {} » créé avec le mot de passe initial de la configuration (à changer à la première connexion).",
                    IDENTIFIANT);
            return;
        }
        String nomFichier = proprietes.comptes() == null || proprietes.comptes().fichierProvisoires() == null
                ? "comptes_provisoires.txt" : proprietes.comptes().fichierProvisoires();
        Path chemin = Path.of(nomFichier).toAbsolutePath();
        if (chemin.getParent() != null) {
            Files.createDirectories(chemin.getParent());
        }
        String contenu = "FACT Immobilier - mot de passe provisoire ("
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + ")\r\n"
                + "À remettre à l'administrateur, puis à détruire. Il se change à la première connexion.\r\n\r\n"
                + String.format("%-16s %s", IDENTIFIANT, motDePasse) + "\r\n";
        Files.writeString(chemin, contenu, StandardCharsets.UTF_8);
        LOG.warn("Compte « {} » créé : mot de passe provisoire dans {}", IDENTIFIANT, chemin);
    }
}
