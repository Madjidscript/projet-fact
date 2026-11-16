package com.eburtis.fact.presentation.dto;

/**
 * Ce que l'utilisateur écrit à l'assistant.
 *
 * @param texte la question
 * @param page  l'adresse de la page ouverte dans Angular (par exemple « /patrimoine/12 ») : l'assistant
 *              comprend ainsi « ici » ou « ce bâtiment ». Facultative.
 */
public record QuestionAssistantDto(String texte, String page) {
}
