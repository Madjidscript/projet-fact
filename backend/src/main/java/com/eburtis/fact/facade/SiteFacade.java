package com.eburtis.fact.facade;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.AbonnementDto;
import com.eburtis.fact.presentation.dto.DemandeSiteDto;
import com.eburtis.fact.repository.AlerteRepository;
import com.eburtis.fact.repository.DemandeRepository;
import com.eburtis.fact.repository.SiteRepository;
import com.eburtis.fact.service.MessageService;
import com.eburtis.fact.service.StockagePhotos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Le site public : catalogue des biens en ligne (§ 3.2, 3.3), chiffres clés (§ 3.1), demandes
 * (§ 3.3 à 3.5) et alertes de recherche (§ 3.7). Rien ici n'exige de compte ; rien ici ne livre
 * de donnée privée. Les envois sont limités par adresse pour décourager les robots.
 */
@Service
public class SiteFacade {

    private static final Set<String> TYPES = Set.of("VISITE", "MANDAT", "DEVIS", "ESTIMATION", "CONTACT");
    private static final String TELEPHONE = "^\\+?[0-9 .]{8,20}$";
    private static final String EMAIL = "^[^@\\s]+@[^@\\s]+\\.[a-zA-Z]{2,}$";
    private static final Set<String> PIECES_ADMISES = Set.of("application/pdf", "image/jpeg", "image/png", "image/webp");
    private static final int ENVOIS_PAR_HEURE = 8;

    private final SiteRepository siteRepository;
    private final DemandeRepository demandeRepository;
    private final AlerteRepository alerteRepository;
    private final MessageService messageService;
    private final StockagePhotos stockage;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom hasard = new SecureRandom();
    private final Map<String, Deque<Instant>> envois = new ConcurrentHashMap<>();

    public SiteFacade(SiteRepository siteRepository, DemandeRepository demandeRepository, AlerteRepository alerteRepository,
                      MessageService messageService, StockagePhotos stockage) {
        this.siteRepository = siteRepository;
        this.demandeRepository = demandeRepository;
        this.alerteRepository = alerteRepository;
        this.messageService = messageService;
        this.stockage = stockage;
    }

    // ------------------------------------------------------------------ catalogue
    public JsonNode rechercher(SiteRepository.Criteres c) {
        if (positif(c.prixMin()) || positif(c.prixMax()) || (c.pieces() != null && c.pieces() < 0) || (c.surface() != null && c.surface() < 0)) {
            throw ErreurMetier.invalide("Les montants, pièces et surfaces ne peuvent pas être négatifs.");
        }
        return siteRepository.rechercher(c);
    }

    private static boolean positif(Long v) {
        return v != null && v < 0;
    }

    public JsonNode aLaUne() {
        return siteRepository.aLaUne(6);
    }

    public Map<String, Object> fiche(String slug) {
        JsonNode b = siteRepository.fiche(slug).orElseThrow(() -> ErreurMetier.introuvable("Ce bien n'est plus proposé."));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bien", b);
        r.put("similaires", siteRepository.similaires(b.get("id").asInt(), b.get("operation").asText(), b.get("idCommune").asInt(),
                b.get("prix").asLong()));
        return r;
    }

    public byte[] photo(int idPhoto) {
        return stockage.lire(siteRepository.photoPublique(idPhoto).orElseThrow(() -> ErreurMetier.introuvable("Photo introuvable.")));
    }

    /** Les chiffres clés ; un chiffre nul n'est pas rendu (règle du § 3.1). */
    public Map<String, Object> chiffres() {
        JsonNode c = siteRepository.chiffres();
        Map<String, Object> r = new LinkedHashMap<>();
        for (String k : List.of("batiments", "lots", "communes", "enLigne")) {
            if (c.get(k).asLong() > 0) {
                r.put(k, c.get(k).asLong());
            }
        }
        r.put("annees", Year.now().getValue() - 2014);
        return r;
    }

    // ------------------------------------------------------------------ demandes
    @Transactional
    public Map<String, Object> demander(DemandeSiteDto s, List<MultipartFile> fichiers, String poste) {
        if (s.site() != null && !s.site().isBlank()) {
            // Un robot a rempli le champ caché : on fait comme si tout allait bien, sans rien enregistrer.
            return Map.of("reference", "DEM-0000", "message", "Votre demande est bien reçue.");
        }
        limiter(poste);
        String type = s.type() == null ? "" : s.type().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw ErreurMetier.invalide("Type de demande inconnu.");
        }
        String nom = vide(s.nom());
        if (nom == null || nom.length() > 160) {
            throw ErreurMetier.invalide("Indiquez votre nom et vos prénoms.");
        }
        String tel = vide(s.telephone());
        if (tel == null || !tel.matches(TELEPHONE) || tel.replaceAll("\\D", "").length() < 8) {
            throw ErreurMetier.invalide("Indiquez un numéro de téléphone valide (10 chiffres, ou au format international).");
        }
        String email = vide(s.email());
        if (email != null && !email.matches(EMAIL)) {
            throw ErreurMetier.invalide("L'adresse e-mail n'est pas valide.");
        }
        if (!Boolean.TRUE.equals(s.consentement())) {
            throw ErreurMetier.invalide("Merci d'accepter que l'agence utilise vos coordonnées pour vous répondre.");
        }
        String message = vide(s.message());
        if (message != null && message.length() > 4000) {
            throw ErreurMetier.invalide("Votre message est trop long (4 000 caractères au plus).");
        }
        Integer idAnnonce = null, idBatiment = null, idCompte = null;
        String objet;
        LocalDate date = null;
        String creneau = null;
        switch (type) {
            case "VISITE" -> {
                Object[] a = siteRepository.annonceEnLigne(s.slug() == null ? "" : s.slug())
                        .orElseThrow(() -> ErreurMetier.invalide("Ce bien n'est plus proposé : choisissez-en un autre."));
                idAnnonce = (Integer) a[0];
                idBatiment = (Integer) a[1];
                idCompte = (Integer) a[2];
                date = s.dateSouhaitee();
                if (date == null || !date.isAfter(LocalDate.now()) || date.getDayOfWeek() == DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    throw ErreurMetier.invalide("Choisissez un jour ouvré, à partir de demain.");
                }
                if (date.isAfter(LocalDate.now().plusMonths(3))) {
                    throw ErreurMetier.invalide("Choisissez une date dans les trois prochains mois.");
                }
                creneau = vide(s.creneau());
                if (creneau == null) {
                    throw ErreurMetier.invalide("Choisissez un créneau.");
                }
                objet = "demande de visite pour « " + a[3] + " »";
            }
            case "MANDAT" -> objet = "demande pour nous confier votre bien";
            case "DEVIS" -> objet = "demande de devis";
            case "ESTIMATION" -> objet = "demande d'estimation";
            default -> objet = "message";
        }
        if (s.latitude() != null && (s.latitude() < 4 || s.latitude() > 11 || s.longitude() == null || s.longitude() < -9 || s.longitude() > -2)) {
            throw ErreurMetier.invalide("La position indiquée est hors de Côte d'Ivoire.");
        }
        String details;
        try {
            details = mapper.writeValueAsString(nettoyer(s.details()));
        } catch (IOException e) {
            throw ErreurMetier.invalide("Détails illisibles.");
        }
        int id = demandeRepository.creer(type, idAnnonce, idBatiment, idCompte, nom, tel, email, date, creneau, message, details,
                s.latitude(), s.longitude(), true);
        joindre(id, type, fichiers);
        String reference = demandeRepository.reference(id);
        messageService.accuser(id, reference, nom, tel, email, objet);
        alerteRepository.generer();
        return Map.of("reference", reference,
                "message", "Votre " + objet + " est bien reçue. Un conseiller vous rappelle au " + tel + ".");
    }

    /** Pièces jointes (§ 3.5) : plans et photos, PDF ou images, 10 Mo au total. */
    private void joindre(int id, String type, List<MultipartFile> fichiers) {
        if (fichiers == null || fichiers.isEmpty()) {
            return;
        }
        if (!Set.of("DEVIS", "ESTIMATION", "MANDAT").contains(type)) {
            throw ErreurMetier.invalide("Ce formulaire n'accepte pas de pièce jointe.");
        }
        long total = fichiers.stream().mapToLong(MultipartFile::getSize).sum();
        if (total > 10L * 1024 * 1024) {
            throw ErreurMetier.invalide("Les pièces jointes dépassent 10 Mo au total.");
        }
        if (fichiers.size() > 8) {
            throw ErreurMetier.invalide("8 pièces jointes au plus.");
        }
        for (MultipartFile f : fichiers) {
            String ct = f.getContentType() == null ? "" : f.getContentType().toLowerCase(Locale.ROOT);
            if (!PIECES_ADMISES.contains(ct)) {
                throw ErreurMetier.invalide("« " + f.getOriginalFilename() + " » : seuls les PDF et les photos (JPEG, PNG) sont acceptés.");
            }
            try {
                byte[] octets = f.getBytes();
                String chemin;
                String typeRange;
                if ("application/pdf".equals(ct)) {
                    if (octets.length < 5 || !new String(octets, 0, 5).equals("%PDF-")) {
                        throw ErreurMetier.invalide("« " + f.getOriginalFilename() + " » n'est pas un PDF lisible.");
                    }
                    chemin = stockage.enregistrerFichier("demandes", String.valueOf(id), octets, "pdf");
                    typeRange = ct;
                } else {
                    chemin = stockage.enregistrer("demandes", String.valueOf(id), octets).chemin();
                    typeRange = "image/jpeg";
                }
                String nom = f.getOriginalFilename() == null ? "piece" : f.getOriginalFilename().replaceAll("[\\\\/:*?\"<>|]", "_");
                demandeRepository.ajouterPiece(id, chemin, nom.length() > 200 ? nom.substring(0, 200) : nom, typeRange, octets.length);
            } catch (IOException e) {
                throw ErreurMetier.invalide("« " + f.getOriginalFilename() + " » n'a pas pu être lu.");
            }
        }
    }

    // ------------------------------------------------------------------ alertes de recherche
    @Transactional
    public Map<String, Object> abonner(AbonnementDto s, String poste) {
        if (s.site() != null && !s.site().isBlank()) {
            return Map.of("message", "Alerte enregistrée.");
        }
        limiter(poste);
        String contact = vide(s.contact());
        if (contact == null) {
            throw ErreurMetier.invalide("Indiquez votre e-mail ou votre numéro WhatsApp.");
        }
        String canal;
        if (contact.matches(EMAIL)) {
            canal = "EMAIL";
            contact = contact.toLowerCase(Locale.ROOT);
        } else if (contact.matches(TELEPHONE) && contact.replaceAll("\\D", "").length() >= 8) {
            canal = "WHATSAPP";
        } else {
            throw ErreurMetier.invalide("Indiquez un e-mail ou un numéro WhatsApp valide.");
        }
        if (!Boolean.TRUE.equals(s.consentement())) {
            throw ErreurMetier.invalide("Merci d'accepter de recevoir ces alertes.");
        }
        if (demandeRepository.abonnementsActifs(contact) >= 5) {
            throw ErreurMetier.invalide("Vous avez déjà 5 alertes : arrêtez-en une avant d'en créer une autre.");
        }
        Map<String, Object> c = nettoyer(s.criteres());
        c.keySet().retainAll(Set.of("operation", "types", "idCommune", "idQuartier", "prixMin", "prixMax", "pieces", "surface", "libelle"));
        if (!c.containsKey("operation")) {
            c.put("operation", "LOCATION");
        }
        byte[] b = new byte[18];
        hasard.nextBytes(b);
        String jeton = java.util.HexFormat.of().formatHex(b);
        try {
            demandeRepository.abonner(contact, canal, mapper.writeValueAsString(c), jeton);
        } catch (IOException e) {
            throw ErreurMetier.invalide("Critères illisibles.");
        }
        return Map.of("message", "C'est noté : nous vous préviendrons dès qu'un bien correspondant sera publié.");
    }

    public Map<String, Object> desabonner(String jeton) {
        int n = jeton == null ? 0 : demandeRepository.desabonner(jeton);
        return Map.of("arrete", n > 0, "message", n > 0 ? "Vous ne recevrez plus cette alerte." : "Cette alerte était déjà arrêtée.");
    }

    // ------------------------------------------------------------------ outils
    private void limiter(String poste) {
        Instant maintenant = Instant.now();
        Deque<Instant> l = envois.computeIfAbsent(poste == null ? "?" : poste, k -> new ArrayDeque<>());
        synchronized (l) {
            while (!l.isEmpty() && l.peekFirst().isBefore(maintenant.minusSeconds(3600))) {
                l.pollFirst();
            }
            if (l.size() >= ENVOIS_PAR_HEURE) {
                throw new ErreurMetier(HttpStatus.TOO_MANY_REQUESTS, "Trop d'envois depuis cet appareil : réessayez dans une heure, ou appelez-nous.");
            }
            l.addLast(maintenant);
        }
    }

    /** Ne garde que des valeurs simples (textes courts, nombres, booléens, listes de textes). */
    private static Map<String, Object> nettoyer(Map<String, Object> d) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (d == null) {
            return r;
        }
        d.forEach((k, v) -> {
            if (r.size() >= 30 || k == null || k.length() > 40 || v == null) {
                return;
            }
            if (v instanceof String t && !t.isBlank()) {
                r.put(k, t.length() > 500 ? t.substring(0, 500) : t.trim());
            } else if (v instanceof Number || v instanceof Boolean) {
                r.put(k, v);
            } else if (v instanceof List<?> l) {
                r.put(k, l.stream().filter(x -> x instanceof String || x instanceof Number).limit(20).map(String::valueOf).toList());
            }
        });
        return r;
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
