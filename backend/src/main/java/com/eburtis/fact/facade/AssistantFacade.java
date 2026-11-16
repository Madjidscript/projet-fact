package com.eburtis.fact.facade;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.BoucleAgent;
import com.eburtis.fact.assistant.LimiteurAssistant;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.assistant.PromptSysteme;
import com.eburtis.fact.configuration.AssistantProperties;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.repository.AssistantRepository;
import com.eburtis.fact.service.JournalService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prépare la conversation et l'envoie au modèle.
 *
 * Étape 2 : la mémoire. Le modèle ne se souvient de rien ; c'est NOUS qui relisons
 * l'historique dans la base et le lui renvoyons à chaque question :
 *
 *   messages = [ prompt système, contexte de la page, ...les 20 derniers messages, nouvelle question ]
 *
 * Plus l'historique est long, plus chaque appel coûte cher : d'où la limite de 20.
 *
 * Étape 3 : les outils. La conversation part dans BoucleAgent, qui rappelle le modèle tant qu'il
 * demande des outils. Seuls la question et le texte final sont gardés en mémoire.
 *
 * Étape 5 : les actions. Un outil d'écriture ne fait rien dans la boucle : il crée une demande de
 * validation. C'est {@link #valider}, appelé par le clic « Valider », qui exécute l'action.
 */
@Service
public class AssistantFacade {

    public static final String NON_CONFIGURE =
            "Je ne suis pas encore branché : remplissez OPENAI_API_KEY et OPENAI_MODEL dans le fichier .env, puis relancez l'API.";

    /** La mémoire courte : le nombre de messages passés renvoyés au modèle. */
    static final int MEMOIRE = 20;

    /** Une adresse de page Angular raisonnable : on ne transmet jamais au modèle un texte libre venu du navigateur. */
    private static final Pattern PAGE = Pattern.compile("^/[A-Za-z0-9/_\\-?=&.]{0,150}$");
    private static final Pattern FICHE_BATIMENT = Pattern.compile("^/patrimoine/(\\d+)");
    private static final Pattern FICHE_INTERVENTION = Pattern.compile("[?&]intervention=(\\d+)");

    private final BoucleAgent boucleAgent;
    private final AssistantProperties proprietes;
    private final AssistantRepository repository;
    private final LimiteurAssistant limiteur;
    private final List<OutilAssistant> outils;
    private final JournalService journalService;
    private final ObjectMapper json;

    public AssistantFacade(BoucleAgent boucleAgent, AssistantProperties proprietes, AssistantRepository repository,
                           LimiteurAssistant limiteur, List<OutilAssistant> outils, JournalService journalService, ObjectMapper json) {
        this.boucleAgent = boucleAgent;
        this.proprietes = proprietes;
        this.repository = repository;
        this.limiteur = limiteur;
        this.outils = outils;
        this.journalService = journalService;
        this.json = json;
    }

    public Map<String, Object> repondre(String texte, String page, Utilisateur utilisateur) {
        if (texte == null || texte.isBlank()) {
            throw ErreurMetier.invalide("Le message est vide.");
        }
        if (texte.length() > 4000) {
            throw ErreurMetier.invalide("Le message est trop long (4 000 caractères au plus).");
        }
        if (!proprietes.configure()) {
            return Map.of("reponse", NON_CONFIGURE, "actions", List.of());
        }
        limiteur.verifier(utilisateur.id());          // PROTECTION 1 : 20 questions par minute
        String question = texte.trim();

        // 1. On relit la mémoire AVANT d'enregistrer la question, pour ne pas l'envoyer deux fois.
        List<Map<String, Object>> historique = repository.derniers(utilisateur.id(), MEMOIRE);

        // 2. On construit ce que le modèle va lire, dans l'ordre.
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", PromptSysteme.construire(utilisateur)));
        String contexte = contexte(page);
        if (contexte != null) {
            messages.add(Map.of("role", "system", "content", contexte));
        }
        for (Map<String, Object> ancien : historique) {
            messages.add(Map.of("role", ancien.get("role"), "content", ancien.get("texte")));
        }
        messages.add(Map.of("role", "user", "content", question));

        // 3. La boucle : le modèle répond, ou demande des outils jusqu'à pouvoir répondre.
        //    Si OpenAI échoue, rien n'est enregistré : la personne peut réessayer.
        BoucleAgent.ReponseAgent reponse = boucleAgent.executer(messages, utilisateur);

        // 4. On garde la question et la réponse : elles feront partie de la mémoire de la prochaine fois.
        repository.enregistrer(utilisateur.id(), "user", question);
        repository.enregistrer(utilisateur.id(), "assistant", reponse.texte());

        // Les actions (ouvrir une page, carte de validation) partent vers Angular ; elles ne sont pas gardées en mémoire.
        return Map.of("reponse", reponse.texte(), "actions", reponse.actions());
    }

    /**
     * L'ASSISTANT SAIT OÙ VOUS ÊTES : la page ouverte devient une ligne de contexte pour le modèle.
     * Rien d'autre que l'adresse n'est transmis, et seulement si elle a la forme d'une adresse.
     */
    public static String contexte(String page) {
        if (page == null || !PAGE.matcher(page).matches()) {
            return null;
        }
        StringBuilder c = new StringBuilder("Contexte : la personne regarde actuellement la page « " + page + " »");
        Matcher batiment = FICHE_BATIMENT.matcher(page);
        Matcher intervention = FICHE_INTERVENTION.matcher(page);
        if (batiment.find()) {
            c.append(", c'est-à-dire la fiche du bâtiment id ").append(batiment.group(1))
                    .append(". Si elle dit « ici », « ce bâtiment » ou « cet immeuble », il s'agit de celui-là.");
        } else if (intervention.find()) {
            c.append(", avec l'intervention id ").append(intervention.group(1)).append(" ouverte.");
        } else {
            c.append('.');
        }
        return c.toString();
    }

    /** Ce que le robot affiche à son ouverture : la conversation en cours. */
    public List<Map<String, Object>> historique(Utilisateur utilisateur) {
        return repository.derniers(utilisateur.id(), 50);
    }

    public void effacer(Utilisateur utilisateur) {
        repository.effacer(utilisateur.id());
    }

    // ----------------------------------------------------------------- les validations
    /**
     * Le clic « Valider ». Cinq vérifications avant d'exécuter :
     * la demande est à ce compte, elle attend encore, elle n'a pas expiré, ses paramètres n'ont pas
     * changé depuis l'affichage (empreinte), et le compte a toujours le droit de l'outil.
     *
     * Tout se passe dans UNE transaction : si l'action échoue, ou si un second clic l'a déjà validée,
     * rien n'est écrit.
     */
    @Transactional
    public Map<String, Object> valider(long id, Utilisateur utilisateur) {
        Map<String, Object> v = attendue(id, utilisateur);
        String parametres = (String) v.get("parametres");
        if (!BoucleAgent.empreinte(parametres).equals(String.valueOf(v.get("empreinte")).trim())) {
            throw ErreurMetier.refuse("Les paramètres de cette demande ont changé depuis leur affichage : rien n'a été fait.");
        }
        OutilAssistant outil = outils.stream().filter(o -> o.nom().equals(v.get("outil"))).findFirst()
                .orElseThrow(() -> ErreurMetier.invalide("Cette action n'existe plus."));
        if (!outil.demandeValidation() || (outil.droit() != null && !utilisateur.peut(outil.droit()))) {
            throw ErreurMetier.refuse("Votre compte n'a plus le droit de faire cette action.");
        }

        ActionsPage actions = new ActionsPage();
        String resultat;
        try {
            resultat = outil.executer(json.readTree(parametres), utilisateur, actions);
        } catch (ErreurMetier refus) {
            throw refus;
        } catch (Exception erreur) {
            throw ErreurMetier.invalide("L'action n'a pas pu être faite : " + erreur.getMessage());
        }
        // WHERE statut = 'EN_ATTENTE' : si un autre clic est passé avant, 0 ligne, et on annule tout.
        if (repository.terminerValidation(id, utilisateur.id(), "VALIDEE", resultat) == 0) {
            throw ErreurMetier.invalide("Cette demande a déjà été traitée.");
        }
        // PROTECTION 4 : toute action de l'assistant est tracée dans le journal de FACT.
        journalService.consigner(utilisateur.signature(), "ASSISTANT", String.valueOf(id),
                "Action validée via l'assistant : " + v.get("resume"));
        String texte = "C'est fait. " + resultat;
        repository.enregistrer(utilisateur.id(), "assistant", texte);   // le modèle le saura à la prochaine question
        return Map.of("reponse", texte, "actions", actions.toutes());
    }

    /** Le clic « Annuler » : rien n'est fait. */
    public Map<String, Object> annuler(long id, Utilisateur utilisateur) {
        attendue(id, utilisateur);
        repository.terminerValidation(id, utilisateur.id(), "ANNULEE", null);
        String texte = "D'accord, rien n'a été fait.";
        repository.enregistrer(utilisateur.id(), "assistant", texte);
        return Map.of("reponse", texte, "actions", List.of());
    }

    /** Une demande de ce compte, encore en attente et pas expirée. */
    private Map<String, Object> attendue(long id, Utilisateur utilisateur) {
        Map<String, Object> v = repository.validation(id, utilisateur.id())
                .orElseThrow(() -> ErreurMetier.introuvable("Demande introuvable."));
        if (!"EN_ATTENTE".equals(v.get("statut"))) {
            String etat = switch (String.valueOf(v.get("statut"))) {
                case "VALIDEE" -> "validée";
                case "ANNULEE" -> "annulée";
                default -> "expirée";
            };
            throw ErreurMetier.invalide("Cette demande a déjà été " + etat + ".");
        }
        if (instant(v.get("expireLe")).isBefore(Instant.now())) {
            repository.terminerValidation(id, utilisateur.id(), "EXPIREE", null);
            throw ErreurMetier.invalide("Cette demande a expiré (10 minutes). Redemandez l'action à l'assistant.");
        }
        return v;
    }

    private static Instant instant(Object valeur) {
        if (valeur instanceof Timestamp t) {
            return t.toInstant();
        }
        if (valeur instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (valeur instanceof Instant i) {
            return i;
        }
        return Instant.parse(String.valueOf(valeur));
    }
}
