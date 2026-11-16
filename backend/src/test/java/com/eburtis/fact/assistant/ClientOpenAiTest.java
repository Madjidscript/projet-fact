package com.eburtis.fact.assistant;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.eburtis.fact.configuration.AssistantProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** La requête envoyée à OpenAI a la bonne forme, et la réponse est bien lue. Aucun vrai appel. */
class ClientOpenAiTest {

    @Test
    void envoieLaCleLeModeleLesMessagesEtLesOutils_puisRendLeMessage() throws Exception {
        AtomicReference<String> cleRecue = new AtomicReference<>();
        AtomicReference<String> corpsRecu = new AtomicReference<>();

        // Un faux OpenAI sur un port libre : il note ce qu'il reçoit et répond comme le vrai.
        HttpServer fauxOpenAi = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fauxOpenAi.createContext("/v1/chat/completions", echange -> {
            cleRecue.set(echange.getRequestHeaders().getFirst("Authorization"));
            corpsRecu.set(new String(echange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reponse = """
                    {"choices":[{"message":{"role":"assistant","content":"Bonjour, je suis là."}}],
                     "usage":{"prompt_tokens":40,"completion_tokens":6}}
                    """.getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(200, reponse.length);
            echange.getResponseBody().write(reponse);
            echange.close();
        });
        fauxOpenAi.start();
        try {
            ClientOpenAi client = new ClientOpenAi(new AssistantProperties("cle-de-test", "modele-de-test", 1000),
                    "http://localhost:" + fauxOpenAi.getAddress().getPort() + "/v1");

            ClientOpenAi.ReponseModele appel = client.appeler(
                    List.of(Map.of("role", "system", "content", "Tu es l'assistant."),
                            Map.of("role", "user", "content", "Bonjour")),
                    List.of(Map.of("type", "function", "function", Map.of("name", "chercher_batiments"))));

            assertThat(appel.message().path("content").asText()).isEqualTo("Bonjour, je suis là.");
            assertThat(appel.tokensEnvoyes()).isEqualTo(40);
            assertThat(appel.tokensRecus()).isEqualTo(6);
            assertThat(cleRecue.get()).isEqualTo("Bearer cle-de-test");
            assertThat(corpsRecu.get())
                    .contains("\"model\":\"modele-de-test\"")
                    .contains("\"content\":\"Bonjour\"")
                    .contains("\"tools\"").contains("chercher_batiments");
        } finally {
            fauxOpenAi.stop(0);
        }
    }
}
