package com.eburtis.fact.controller;

import java.io.IOException;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.ImportFacade;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Import initial du patrimoine depuis Excel (SIG-10). Réservé à la Direction et à l'administrateur. */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportFacade importFacade;

    public ImportController(ImportFacade importFacade) {
        this.importFacade = importFacade;
    }

    @GetMapping("/modele")
    public ResponseEntity<byte[]> modele() {
        direction();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"modele_import_patrimoine.xlsx\"")
                .body(importFacade.modele());
    }

    /** {@code simuler} vrai (par défaut) : contrôle le fichier sans rien écrire. */
    @PostMapping("/patrimoine")
    public Map<String, Object> importer(@RequestParam("fichier") MultipartFile fichier,
                                        @RequestParam(defaultValue = "true") boolean simuler) throws IOException {
        Utilisateur u = direction();
        if (fichier.isEmpty()) {
            throw ErreurMetier.invalide("Le fichier est vide.");
        }
        return importFacade.importer(fichier.getInputStream(), fichier.getOriginalFilename(), simuler, u);
    }

    private static Utilisateur direction() {
        Utilisateur u = SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE);
        if (!u.estDirection()) {
            throw ErreurMetier.refuse("L'import du patrimoine est réservé à la Direction.");
        }
        return u;
    }
}
