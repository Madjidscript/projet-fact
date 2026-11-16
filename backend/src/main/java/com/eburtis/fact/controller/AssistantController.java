package com.eburtis.fact.controller;

import java.util.List;
import java.util.Map;

import com.eburtis.fact.facade.AssistantFacade;
import com.eburtis.fact.presentation.dto.QuestionAssistantDto;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** L'assistant IA : toute personne connectée peut lui écrire. Il ne voit que ce que son compte voit. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantFacade assistantFacade;

    public AssistantController(AssistantFacade assistantFacade) {
        this.assistantFacade = assistantFacade;
    }

    @PostMapping
    public Map<String, Object> repondre(@RequestBody QuestionAssistantDto question) {
        return assistantFacade.repondre(question.texte(), question.page(), SecurityUtils.utilisateurConnecte());
    }

    /** La conversation du compte connecté, et seulement la sienne. */
    @GetMapping("/historique")
    public List<Map<String, Object>> historique() {
        return assistantFacade.historique(SecurityUtils.utilisateurConnecte());
    }

    /** « Nouvelle conversation » : efface le fil du compte connecté. */
    @DeleteMapping("/historique")
    public void effacer() {
        assistantFacade.effacer(SecurityUtils.utilisateurConnecte());
    }

    /** Le clic « Valider » sur une carte : c'est ici, et seulement ici, qu'une action de l'assistant s'exécute. */
    @PostMapping("/validations/{id}/valider")
    public Map<String, Object> valider(@PathVariable long id) {
        return assistantFacade.valider(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/validations/{id}/annuler")
    public Map<String, Object> annuler(@PathVariable long id) {
        return assistantFacade.annuler(id, SecurityUtils.utilisateurConnecte());
    }
}
