package com.eburtis.fact.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.eburtis.fact.exception.ErreurMetier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * PROTECTION 1 : pas plus de 20 questions par minute et par compte.
 * Chaque question coûte de l'argent (les tokens) : sans cette limite, un compte, ou un script
 * qui aurait volé un jeton, pourrait vider le crédit OpenAI en quelques minutes.
 */
@Component
public class LimiteurAssistant {

    public static final int QUESTIONS_PAR_MINUTE = 20;
    private static final Duration FENETRE = Duration.ofMinutes(1);

    private final Map<Integer, Deque<Instant>> questions = new ConcurrentHashMap<>();

    /** Compte une question ; lève une erreur 429 au-delà de la limite. */
    public void verifier(int idCompte) {
        Deque<Instant> liste = questions.computeIfAbsent(idCompte, c -> new ArrayDeque<>());
        synchronized (liste) {
            Instant limite = Instant.now().minus(FENETRE);
            while (!liste.isEmpty() && liste.peekFirst().isBefore(limite)) {
                liste.removeFirst();
            }
            if (liste.size() >= QUESTIONS_PAR_MINUTE) {
                throw new ErreurMetier(HttpStatus.TOO_MANY_REQUESTS,
                        "Doucement : pas plus de " + QUESTIONS_PAR_MINUTE + " questions par minute. Réessayez dans un instant.");
            }
            liste.addLast(Instant.now());
        }
    }
}
