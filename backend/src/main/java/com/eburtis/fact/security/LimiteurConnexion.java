package com.eburtis.fact.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Cinq essais manqués en dix minutes bloquent la connexion cinq minutes depuis le poste concerné. */
@Component
public class LimiteurConnexion {

    private static final int ESSAIS = 5;
    private static final Duration FENETRE = Duration.ofMinutes(10);
    private static final Duration BLOCAGE = Duration.ofMinutes(5);

    private final Map<String, Deque<Instant>> echecs = new ConcurrentHashMap<>();

    public boolean bloque(String poste) {
        Deque<Instant> liste = echecs.get(poste);
        if (liste == null) {
            return false;
        }
        synchronized (liste) {
            purger(liste);
            return liste.size() >= ESSAIS && liste.peekLast().plus(BLOCAGE).isAfter(Instant.now());
        }
    }

    public void echec(String poste) {
        Deque<Instant> liste = echecs.computeIfAbsent(poste, p -> new ArrayDeque<>());
        synchronized (liste) {
            purger(liste);
            liste.addLast(Instant.now());
        }
    }

    public void reussite(String poste) {
        echecs.remove(poste);
    }

    private static void purger(Deque<Instant> liste) {
        Instant limite = Instant.now().minus(FENETRE);
        while (!liste.isEmpty() && liste.peekFirst().isBefore(limite)) {
            liste.removeFirst();
        }
    }
}
