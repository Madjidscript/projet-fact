package com.eburtis.fact.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.assistant.outils.ChercherBatimentsOutil;
import com.eburtis.fact.assistant.outils.ConsulterGuideOutil;
import com.eburtis.fact.assistant.outils.ListerImpayesOutil;
import com.eburtis.fact.assistant.outils.ListerInterventionsOutil;
import com.eburtis.fact.assistant.outils.OuvrirPageOutil;
import com.eburtis.fact.assistant.outils.PointDuJourOutil;
import com.eburtis.fact.assistant.outils.PreparerRelanceOutil;
import com.eburtis.fact.assistant.outils.SignalerInterventionOutil;
import com.eburtis.fact.configuration.AssistantProperties;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROTECTION 6 : L'ÉVALUATION. Des questions types, et l'outil que le VRAI modèle doit choisir
 * au premier tour. On la relance après chaque changement du prompt ou d'une description d'outil :
 * si le score baisse, le changement a cassé quelque chose.
 *
 * Elle appelle vraiment OpenAI (environ 2 centimes), donc elle ne tourne que si les variables
 * OPENAI_API_KEY et OPENAI_MODEL sont définies dans le terminal :
 *
 *   $env:OPENAI_API_KEY="sk-..."; $env:OPENAI_MODEL="..."; .\gradlew.bat test --tests "*EvaluationAssistantTest"
 *
 * Les outils sont construits sans facade : ce test ne regarde que le CHOIX du modèle, rien n'est exécuté.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = "sk-.+")
@EnabledIfEnvironmentVariable(named = "OPENAI_MODEL", matches = ".+")
class EvaluationAssistantTest {

    /** Question → outil attendu au premier tour (« texte » : aucune donnée à chercher, il répond directement). */
    private static final Map<String, String> CAS = new LinkedHashMap<>();

    static {
        CAS.put("Combien de bâtiments ai-je ?", "chercher_batiments");
        CAS.put("Donne-moi la liste de mes immeubles à Cocody", "chercher_batiments");
        CAS.put("Où sont les lots vacants ?", "chercher_batiments");
        CAS.put("Qui ne paie pas son loyer ?", "lister_impayes");
        CAS.put("Quels locataires ont du retard ?", "lister_impayes");
        CAS.put("Quelles pannes sont encore ouvertes ?", "lister_interventions");
        CAS.put("Y a-t-il des travaux urgents ?", "lister_interventions");
        CAS.put("Fais-moi le point de la journée", "point_du_jour");
        CAS.put("Comment je signe un bail ?", "consulter_guide");
        CAS.put("Comment encaisser un loyer sur le terrain ?", "consulter_guide");
        CAS.put("Ouvre la carte", "ouvrir_page");
        CAS.put("Emmène-moi sur la page des loyers", "ouvrir_page");
        CAS.put("Ouvre la fiche du bâtiment Les Palmiers", "chercher_batiments");     // il lui faut d'abord l'id
        CAS.put("Signale une fuite d'eau aux Palmiers", "chercher_batiments");        // idem, avant signaler_intervention
        CAS.put("Bonjour, merci pour ton aide !", "texte");
    }

    @Test
    void leModeleChoisitLeBonOutil() {
        AssistantProperties proprietes = new AssistantProperties(System.getenv("OPENAI_API_KEY"), System.getenv("OPENAI_MODEL"), 1_000_000);
        ClientOpenAi client = new ClientOpenAi(proprietes);
        Utilisateur direction = new Utilisateur(1, "evaluation", "Évaluation", "Direction", "e@fact.test",
                Profil.DIRECTION, Set.of(), false, 1);

        List<OutilAssistant> outils = List.of(new ChercherBatimentsOutil(null), new ListerImpayesOutil(null),
                new ListerInterventionsOutil(null), new PointDuJourOutil(null, null, null), new ConsulterGuideOutil(),
                new OuvrirPageOutil(null), new PreparerRelanceOutil(null), new SignalerInterventionOutil(null, null));
        List<Map<String, Object>> definitions = BoucleAgent.definitions(BoucleAgent.permis(outils, direction));

        List<String> echecs = new ArrayList<>();
        for (Map.Entry<String, String> cas : CAS.entrySet()) {
            List<Object> messages = List.of(
                    Map.of("role", "system", "content", PromptSysteme.construire(direction)),
                    Map.of("role", "user", "content", cas.getKey()));
            JsonNode demandes = client.appeler(messages, definitions).message().path("tool_calls");
            String choisi = demandes.isArray() && !demandes.isEmpty()
                    ? demandes.get(0).path("function").path("name").asText() : "texte";
            String verdict = choisi.equals(cas.getValue()) ? "OK  " : "RATÉ";
            System.out.printf("%s %-55s attendu %-22s choisi %s%n", verdict, cas.getKey(), cas.getValue(), choisi);
            if (!choisi.equals(cas.getValue())) {
                echecs.add(cas.getKey() + " → " + choisi);
            }
        }
        double score = 100.0 * (CAS.size() - echecs.size()) / CAS.size();
        System.out.printf("Score : %.0f %% (%d/%d)%n", score, CAS.size() - echecs.size(), CAS.size());
        // Un modèle n'est pas parfaitement régulier : on exige 80 %, pas 100 %.
        assertThat(score).as("Cas ratés : %s", echecs).isGreaterThanOrEqualTo(80.0);
    }
}
