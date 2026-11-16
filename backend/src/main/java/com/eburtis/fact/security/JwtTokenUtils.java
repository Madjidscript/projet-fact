package com.eburtis.fact.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.eburtis.fact.configuration.FactProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import org.springframework.stereotype.Component;

/** Jetons de session signés (HS256) : identifiant du compte et version de ses sessions. */
@Component
public class JwtTokenUtils {

    private static final String EMETTEUR = "fact-immobilier";

    public record Jeton(String valeur, Instant expiration) {
    }

    public record Contenu(String identifiant, int version) {
    }

    private final Key cle;
    private final int dureeHeures;

    public JwtTokenUtils(FactProperties proprietes) {
        byte[] octets = proprietes.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (octets.length < 32) {
            throw new IllegalStateException("fact.jwt.secret doit compter au moins 32 caractères.");
        }
        this.cle = Keys.hmacShaKeyFor(octets);
        this.dureeHeures = proprietes.jwt().dureeHeures() > 0 ? proprietes.jwt().dureeHeures() : 12;
    }

    public Jeton emettre(String identifiant, int version) {
        Instant maintenant = Instant.now();
        Instant fin = maintenant.plus(dureeHeures, ChronoUnit.HOURS);
        String valeur = Jwts.builder()
                .setSubject(identifiant)
                .claim("ver", version)
                .setIssuer(EMETTEUR)
                .setIssuedAt(Date.from(maintenant))
                .setExpiration(Date.from(fin))
                .signWith(cle, SignatureAlgorithm.HS256)
                .compact();
        return new Jeton(valeur, fin);
    }

    /** Le contenu d'un jeton valide, ou null. */
    public Contenu lire(String valeur) {
        try {
            Claims c = Jwts.parserBuilder().setSigningKey(cle).requireIssuer(EMETTEUR).build()
                    .parseClaimsJws(valeur).getBody();
            Number version = c.get("ver", Number.class);
            return new Contenu(c.getSubject(), version == null ? -1 : version.intValue());
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
