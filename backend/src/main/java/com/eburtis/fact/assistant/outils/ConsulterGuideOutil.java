package com.eburtis.fact.assistant.outils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Le guide de l'application : répond à « comment faire ? » à partir de docs/guide-utilisateur.md.
 *
 * Le guide entier fait environ 8 000 tokens : l'envoyer à chaque question coûterait cher. On le
 * découpe donc en sections (« ### ») et on ne renvoie que les deux qui parlent le plus de la question.
 * C'est le principe du RAG (« retrieval augmented generation »), en version simple : chercher le
 * bon passage, puis le donner au modèle.
 *
 * Le fichier est copié dans le jar par build.gradle (processResources).
 */
@Component
public class ConsulterGuideOutil implements OutilAssistant {

    private static final String FICHIER = "assistant/guide-utilisateur.md";
    private static final int SECTIONS_RENDUES = 2;
    private static final int MAX_CARACTERES = 3500;
    /** Les mots trop courants pour aider à trouver la bonne section. */
    private static final Set<String> MOTS_VIDES = Set.of("comment", "faire", "pour", "dans", "avec", "une", "des", "les",
            "est", "que", "qui", "quoi", "mon", "mes", "son", "ses", "sur", "par", "pas", "plus", "peut", "peux", "faut");

    private record Section(String titre, String texte, String cherchable) {
    }

    private List<Section> sections;

    @Override
    public String nom() {
        return "consulter_guide";
    }

    @Override
    public String description() {
        return "Cherche dans le guide d'utilisation officiel de FACT comment faire une tâche dans l'application "
                + "(créer un bâtiment, signer un bail, encaisser un loyer, préparer une tournée, publier une annonce...). "
                + "À utiliser pour toute question « comment je fais ? » ou « à quoi sert ? ».";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "sujet", Map.of("type", "string", "description", "Le sujet en quelques mots, par exemple « signer un bail ».")),
                "required", List.of("sujet"));
    }

    @Override
    public String droit() {
        return null;
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        List<String> mots = mots(arguments.path("sujet").asText(""));
        if (mots.isEmpty()) {
            return "Précise le sujet à chercher dans le guide.";
        }
        List<Section> trouvees = sections().stream()
                .filter(s -> score(s, mots) > 0)
                .sorted(Comparator.comparingInt((Section s) -> score(s, mots)).reversed())
                .limit(SECTIONS_RENDUES)
                .toList();
        if (trouvees.isEmpty()) {
            return "Le guide ne parle pas de ce sujet. Dis-le à la personne, sans inventer de procédure.";
        }
        StringBuilder r = new StringBuilder("Extraits du guide d'utilisation (réponds à partir de ces extraits seulement) :\n");
        for (Section s : trouvees) {
            r.append("\n## ").append(s.titre()).append('\n').append(s.texte()).append('\n');
        }
        return r.length() > MAX_CARACTERES ? r.substring(0, MAX_CARACTERES) + "\n[…]" : r.toString();
    }

    /** Points : chaque mot de la question trouvé dans le titre vaut 3, dans le texte 1. */
    private static int score(Section s, List<String> mots) {
        int points = 0;
        String titre = normaliser(s.titre());
        for (String mot : mots) {
            if (titre.contains(mot)) {
                points += 3;
            }
            if (s.cherchable().contains(mot)) {
                points += 1;
            }
        }
        return points;
    }

    /** Découpe le guide une seule fois, à la première question. */
    private synchronized List<Section> sections() {
        if (sections == null) {
            sections = decouper(lire());
        }
        return sections;
    }

    static List<Section> decouper(String guide) {
        List<Section> resultat = new ArrayList<>();
        String chapitre = "";
        String titre = null;
        StringBuilder texte = new StringBuilder();
        for (String ligne : guide.split("\\R")) {
            if (ligne.startsWith("## ") || ligne.startsWith("### ")) {
                if (titre != null) {
                    resultat.add(new Section(titre, texte.toString().trim(), normaliser(titre + " " + texte)));
                }
                if (ligne.startsWith("## ")) {
                    chapitre = ligne.substring(3).trim();
                    titre = chapitre;
                } else {
                    titre = chapitre + " › " + ligne.substring(4).trim();
                }
                texte.setLength(0);
            } else if (titre != null) {
                texte.append(ligne).append('\n');
            }
        }
        if (titre != null) {
            resultat.add(new Section(titre, texte.toString().trim(), normaliser(titre + " " + texte)));
        }
        return resultat;
    }

    private static String lire() {
        try (InputStream flux = new ClassPathResource(FICHIER).getInputStream()) {
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException absent) {
            return "";
        }
    }

    private static List<String> mots(String texte) {
        List<String> r = new ArrayList<>();
        for (String mot : normaliser(texte).split("[^a-z0-9]+")) {
            if (mot.length() >= 3 && !MOTS_VIDES.contains(mot)) {
                // « baux » et « bail », « loyers » et « loyer » : on garde la racine.
                r.add(mot.length() > 5 ? mot.substring(0, mot.length() - 1) : mot);
            }
        }
        return r;
    }

    private static String normaliser(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }
}
