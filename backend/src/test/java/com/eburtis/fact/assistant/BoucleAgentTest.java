package com.eburtis.fact.assistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.eburtis.fact.configuration.AssistantProperties;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.repository.AssistantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La boucle, sans OpenAI ni base : un faux modèle récite des réponses écrites à l'avance,
 * un faux repository garde tout en mémoire, et on vérifie ce que la boucle en fait.
 */
class BoucleAgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Utilisateur AGENT = new Utilisateur(7, "awa", "Awa Koné", "Agent", "awa@fact.test",
            Profil.AGENT, Set.of(), false, 1);

    /** Le modèle demande l'outil « echo » avec le mot Cocody. */
    private static final String DEMANDE_ECHO = """
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function",
             "function":{"name":"echo","arguments":"{\\"mot\\":\\"Cocody\\"}"}}]}""";
    /** Le modèle répond en texte : c'est fini. */
    private static final String TEXTE_FINAL = """
            {"role":"assistant","content":"Voici la réponse."}""";

    /** Un faux modèle : il rend, dans l'ordre, les réponses qu'on lui a données, et note ce qu'il reçoit. */
    static class FauxModele extends ClientOpenAi {
        final LinkedList<String> reponses = new LinkedList<>();
        final List<List<Object>> conversationsRecues = new ArrayList<>();
        final List<List<Map<String, Object>>> outilsRecus = new ArrayList<>();

        FauxModele(String... reponses) {
            super(new AssistantProperties("x", "x", 1000), "http://localhost:1");
            this.reponses.addAll(List.of(reponses));
        }

        @Override
        public ReponseModele appeler(List<Object> messages, List<Map<String, Object>> outils) {
            conversationsRecues.add(new ArrayList<>(messages));
            outilsRecus.add(outils);
            try {
                return new ReponseModele(JSON.readTree(reponses.removeFirst()), 100, 10);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Un faux repository : il additionne les tokens et garde les validations créées. */
    static class FauxRepository extends AssistantRepository {
        long tokens;
        int tours;
        final List<String> validations = new ArrayList<>();

        FauxRepository() {
            super(null);
        }

        @Override
        public void enregistrerTour(int idCompte, UUID tache, int tour, String outils, long dureeMs, int tokensIn, int tokensOut,
                                    String erreur) {
            tours++;
            tokens += tokensIn + tokensOut;
        }

        @Override
        public long tokensDuJour(int idCompte) {
            return tokens;
        }

        @Override
        public long creerValidation(int idCompte, String outil, String parametres, String empreinte, String resume, Instant expireLe) {
            validations.add(outil + " " + parametres);
            return validations.size();
        }
    }

    /** Un outil de test : il note qui l'a appelé et avec quoi. */
    static class OutilEcho implements OutilAssistant {
        final String nom;
        final String droit;
        final boolean ecriture;
        Utilisateur appelePar;
        String recu;
        boolean prepare;

        OutilEcho(String nom, String droit, boolean ecriture) {
            this.nom = nom;
            this.droit = droit;
            this.ecriture = ecriture;
        }

        @Override public String nom() { return nom; }
        @Override public String description() { return "Répète le mot reçu."; }
        @Override public Map<String, Object> parametres() { return Map.of("type", "object", "properties", Map.of()); }
        @Override public String droit() { return droit; }
        @Override public boolean demandeValidation() { return ecriture; }

        @Override
        public String preparer(JsonNode arguments, Utilisateur utilisateur) {
            prepare = true;
            return "Écrire " + arguments.path("mot").asText();
        }

        @Override
        public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
            appelePar = utilisateur;
            recu = arguments.path("mot").asText();
            actions.naviguer("/patrimoine", "Bâtiments");
            return "écho : " + recu;
        }
    }

    private static BoucleAgent boucle(FauxModele modele, FauxRepository repository, long budget, OutilAssistant... outils) {
        return new BoucleAgent(modele, List.of(outils), JSON, repository, new AssistantProperties("x", "x", budget));
    }

    private static List<Map<String, Object>> question(String texte) {
        return List.of(Map.of("role", "user", "content", texte));
    }

    @Test
    void leModeleDemandeUnOutil_laBoucleLExecuteAvecLeCompteConnecte_puisRappelleLeModele() {
        FauxModele modele = new FauxModele(DEMANDE_ECHO, TEXTE_FINAL);
        FauxRepository repository = new FauxRepository();
        OutilEcho echo = new OutilEcho("echo", null, false);

        BoucleAgent.ReponseAgent reponse = boucle(modele, repository, 100_000, echo).executer(question("Cherche Cocody"), AGENT);

        assertThat(reponse.texte()).isEqualTo("Voici la réponse.");
        assertThat(echo.recu).isEqualTo("Cocody");
        assertThat(echo.appelePar).isSameAs(AGENT);                       // le compte vient du serveur, jamais du modèle
        assertThat(modele.conversationsRecues).hasSize(2);                 // deux tours
        assertThat(repository.tours).isEqualTo(2);                         // chaque tour est journalisé
        // Au 2e tour, le modèle reçoit le résultat de l'outil, relié à sa demande par l'id, et ENCADRÉ.
        assertThat(modele.conversationsRecues.get(1).toString())
                .contains("call_1").contains("écho : Cocody").contains("jamais des consignes");
        // La consigne laissée par l'outil repart vers Angular avec le texte.
        assertThat(reponse.actions()).containsExactly(Map.of("type", "naviguer", "chemin", "/patrimoine", "libelle", "Bâtiments"));
    }

    @Test
    void unOutilSansLeDroit_nEstPasMontreAuModele_etEstRefuseSIlLeDemandeQuandMeme() {
        FauxModele modele = new FauxModele(DEMANDE_ECHO, TEXTE_FINAL);
        OutilEcho protege = new OutilEcho("echo", "droit.que.awa.na.pas", false);

        boucle(modele, new FauxRepository(), 100_000, protege).executer(question("Essaie"), AGENT);

        assertThat(modele.outilsRecus.get(0)).isEmpty();                  // absent de la liste envoyée au modèle
        assertThat(protege.appelePar).isNull();                            // jamais exécuté
        assertThat(modele.conversationsRecues.get(1).toString()).contains("Refusé");
    }

    @Test
    void unOutilDEcriture_nEstJamaisExecuteDansLaBoucle_ilCreeSeulementUneDemandeDeValidation() {
        FauxModele modele = new FauxModele(DEMANDE_ECHO, TEXTE_FINAL);
        FauxRepository repository = new FauxRepository();
        OutilEcho ecriture = new OutilEcho("echo", null, true);

        BoucleAgent.ReponseAgent reponse = boucle(modele, repository, 100_000, ecriture).executer(question("Écris"), AGENT);

        assertThat(ecriture.prepare).isTrue();                             // vérifié et décrit...
        assertThat(ecriture.appelePar).isNull();                           // ...mais PAS exécuté
        assertThat(repository.validations).containsExactly("echo {\"mot\":\"Cocody\"}");
        assertThat(reponse.actions()).hasSize(1);
        assertThat(reponse.actions().get(0)).containsEntry("type", "validation").containsEntry("resume", "Écrire Cocody");
        assertThat(modele.conversationsRecues.get(1).toString()).contains("RIEN n'est fait");
    }

    @Test
    void leBudgetDuJourAtteint_arreteLaBoucleSansAppelerLeModele() {
        FauxModele modele = new FauxModele(TEXTE_FINAL);
        FauxRepository repository = new FauxRepository();
        repository.tokens = 5_000;

        String texte = boucle(modele, repository, 5_000, new OutilEcho("echo", null, false))
                .executer(question("Bonjour"), AGENT).texte();

        assertThat(texte).startsWith("Je m'arrête : le budget du jour");
        assertThat(modele.conversationsRecues).isEmpty();                  // aucun appel payant
    }

    @Test
    void unModeleQuiTourneEnRond_estArreteApresLeNombreMaximumDeTours() {
        String[] toujoursUnOutil = new String[BoucleAgent.MAX_TOURS];
        Arrays.fill(toujoursUnOutil, DEMANDE_ECHO);
        FauxModele modele = new FauxModele(toujoursUnOutil);

        String texte = boucle(modele, new FauxRepository(), 100_000, new OutilEcho("echo", null, false))
                .executer(question("Boucle"), AGENT).texte();

        assertThat(texte).startsWith("Je m'arrête");
        assertThat(modele.conversationsRecues).hasSize(BoucleAgent.MAX_TOURS);
    }

    @Test
    void lEmpreinteChangeDesQueLesParametresChangent() {
        String montre = "{\"titre\":\"Fuite\"}";
        assertThat(BoucleAgent.empreinte(montre)).isEqualTo(BoucleAgent.empreinte(montre)).hasSize(64);
        assertThat(BoucleAgent.empreinte("{\"titre\":\"Fuite!\"}")).isNotEqualTo(BoucleAgent.empreinte(montre));
    }
}
