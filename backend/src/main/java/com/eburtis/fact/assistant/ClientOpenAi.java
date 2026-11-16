package com.eburtis.fact.assistant;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.configuration.AssistantProperties;
import com.eburtis.fact.exception.ErreurMetier;
import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LE TÉLÉPHONE VERS OPENAI. C'est la seule classe du projet qui parle au modèle.
 *
 * Il n'y a rien de magique : c'est une requête HTTP, comme celles qu'Angular envoie à l'API.
 *
 *   POST https://api.openai.com/v1/chat/completions
 *   Authorization: Bearer <OPENAI_API_KEY>     ← c'est la clé qui paie
 *   { "model": "...",
 *     "messages": [ {"role":"system",...}, {"role":"user",...} ],
 *     "tools":    [ {"type":"function","function":{"name":...,"description":...,"parameters":...}} ] }
 *
 * Le modèle répond par UN message, de deux formes possibles :
 *   - du texte : {"role":"assistant","content":"Vous avez 12 bâtiments."}          → c'est fini ;
 *   - une demande d'outil : {"role":"assistant","tool_calls":[{"id":"call_1",
 *       "function":{"name":"chercher_batiments","arguments":"{\"recherche\":\"Cocody\"}"}}]}  → on exécute.
 *
 * PROTECTION 3 : un délai maximum (OpenAI peut ne jamais répondre) et un seul nouvel essai
 * quand OpenAI est surchargé (429) ou en panne (5xx).
 */
@Component
public class ClientOpenAi {

    /** Ce que rend un appel : le message du modèle, et les tokens facturés pour cet appel. */
    public record ReponseModele(JsonNode message, int tokensEnvoyes, int tokensRecus) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(ClientOpenAi.class);
    private static final Duration CONNEXION_MAX = Duration.ofSeconds(10);
    private static final Duration REPONSE_MAX = Duration.ofSeconds(60);
    private static final long PAUSE_AVANT_NOUVEL_ESSAI_MS = 1500;

    private final AssistantProperties proprietes;
    private final RestClient http;

    @Autowired
    public ClientOpenAi(AssistantProperties proprietes) {
        this(proprietes, "https://api.openai.com/v1");
    }

    /** Pour les tests : on remplace l'adresse d'OpenAI par un faux serveur local. */
    public ClientOpenAi(AssistantProperties proprietes, String adresse) {
        this.proprietes = proprietes;
        SimpleClientHttpRequestFactory delais = new SimpleClientHttpRequestFactory();
        delais.setConnectTimeout((int) CONNEXION_MAX.toMillis());
        delais.setReadTimeout((int) REPONSE_MAX.toMillis());
        this.http = RestClient.builder().baseUrl(adresse).requestFactory(delais).build();
    }

    /**
     * Un tour : envoie la conversation et la liste des outils, renvoie le message du modèle.
     *
     * @param messages la conversation complète (prompt système, historique, résultats d'outils)
     * @param outils   les définitions des outils que ce compte peut demander (liste vide : aucun)
     */
    public ReponseModele appeler(List<Object> messages, List<Map<String, Object>> outils) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("model", proprietes.modele());
        corps.put("messages", messages);
        if (!outils.isEmpty()) {
            corps.put("tools", outils);     // OpenAI refuse une liste d'outils vide : on ne l'envoie pas
        }
        try {
            return envoyer(corps);
        } catch (RestClientException premiere) {
            if (!passager(premiere)) {
                throw echec(premiere);
            }
            LOG.warn("OpenAI indisponible ({}), nouvel essai dans {} ms", premiere.getMessage(), PAUSE_AVANT_NOUVEL_ESSAI_MS);
            pause();
            try {
                return envoyer(corps);
            } catch (RestClientException seconde) {
                throw echec(seconde);
            }
        }
    }

    private ReponseModele envoyer(Map<String, Object> corps) {
        JsonNode reponse = http.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + proprietes.cleApi())
                .contentType(MediaType.APPLICATION_JSON)
                .body(corps)
                .retrieve()
                .body(JsonNode.class);
        JsonNode usage = reponse.path("usage");
        int envoyes = usage.path("prompt_tokens").asInt();
        int recus = usage.path("completion_tokens").asInt();
        LOG.info("Assistant : {} tokens envoyés, {} reçus", envoyes, recus);
        return new ReponseModele(reponse.path("choices").path(0).path("message"), envoyes, recus);
    }

    /** Une panne qui vaut un nouvel essai : délai dépassé, OpenAI surchargé (429) ou en panne (5xx). */
    private static boolean passager(RestClientException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        return e instanceof HttpStatusCodeException h && (h.getStatusCode().value() == 429 || h.getStatusCode().is5xxServerError());
    }

    private static ErreurMetier echec(RestClientException e) {
        // On ne renvoie jamais le détail au navigateur : il pourrait contenir des informations sur la clé.
        LOG.error("L'appel à OpenAI a échoué", e);
        if (e instanceof HttpStatusCodeException h && (h.getStatusCode().value() == 401 || h.getStatusCode().value() == 404)) {
            return new ErreurMetier(HttpStatus.BAD_GATEWAY,
                    "OpenAI refuse la clé ou le nom du modèle : vérifiez OPENAI_API_KEY et OPENAI_MODEL dans .env.");
        }
        return new ErreurMetier(HttpStatus.BAD_GATEWAY,
                "L'assistant n'arrive pas à joindre OpenAI pour le moment. Réessayez dans une minute.");
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE_AVANT_NOUVEL_ESSAI_MS);
        } catch (InterruptedException interrompu) {
            Thread.currentThread().interrupt();
        }
    }
}
