package com.eburtis.fact.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages de l'assistant, lus dans application.properties (lignes « fact.assistant. »),
 * qui eux-mêmes viennent du fichier .env : OPENAI_API_KEY, OPENAI_MODEL, ASSISTANT_TOKENS_JOUR.
 */
@ConfigurationProperties(prefix = "fact.assistant")
public record AssistantProperties(String cleApi, String modele, long tokensParJour) {

    public AssistantProperties {
        if (tokensParJour <= 0) {
            tokensParJour = 100_000;
        }
    }

    /** Vrai quand la clé et le modèle sont remplis : sinon on n'appelle jamais OpenAI. */
    public boolean configure() {
        return cleApi != null && !cleApi.isBlank() && modele != null && !modele.isBlank();
    }
}
