package com.eburtis.fact.assistant;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.eburtis.fact.configuration.AssistantProperties;
import com.eburtis.fact.exception.ErreurMetier;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PROTECTION 3 : OpenAI en panne une fois → un nouvel essai ; clé refusée → message clair, sans nouvel essai. */
class ClientOpenAiPannesTest {

    private static final byte[] REPONSE_OK = """
            {"choices":[{"message":{"role":"assistant","content":"Me revoilà."}}],"usage":{"prompt_tokens":5,"completion_tokens":2}}
            """.getBytes(StandardCharsets.UTF_8);

    /** Un faux OpenAI qui répond {@code premierStatut} au premier appel, puis 200. */
    private static HttpServer faux(int premierStatut, AtomicInteger appels) throws Exception {
        HttpServer serveur = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serveur.createContext("/v1/chat/completions", echange -> {
            echange.getRequestBody().readAllBytes();
            int n = appels.incrementAndGet();
            int statut = n == 1 ? premierStatut : 200;
            byte[] corps = statut == 200 ? REPONSE_OK : "{\"error\":{\"message\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(statut, corps.length);
            echange.getResponseBody().write(corps);
            echange.close();
        });
        serveur.start();
        return serveur;
    }

    private static ClientOpenAi client(HttpServer serveur) {
        return new ClientOpenAi(new AssistantProperties("cle", "modele", 1000),
                "http://localhost:" + serveur.getAddress().getPort() + "/v1");
    }

    @Test
    void openAiEnPanneUneFois_unNouvelEssaiSuffit() throws Exception {
        AtomicInteger appels = new AtomicInteger();
        HttpServer serveur = faux(503, appels);
        try {
            ClientOpenAi.ReponseModele r = client(serveur).appeler(List.of(Map.of("role", "user", "content", "?")), List.of());

            assertThat(r.message().path("content").asText()).isEqualTo("Me revoilà.");
            assertThat(appels.get()).isEqualTo(2);
        } finally {
            serveur.stop(0);
        }
    }

    @Test
    void cleRefusee_pasDeNouvelEssai_etUnMessageQuiDitQuoiVerifier() throws Exception {
        AtomicInteger appels = new AtomicInteger();
        HttpServer serveur = faux(401, appels);
        try {
            assertThatThrownBy(() -> client(serveur).appeler(List.of(Map.of("role", "user", "content", "?")), List.of()))
                    .isInstanceOf(ErreurMetier.class).hasMessageContaining("OPENAI_API_KEY");
            assertThat(appels.get()).isEqualTo(1);
        } finally {
            serveur.stop(0);
        }
    }
}
