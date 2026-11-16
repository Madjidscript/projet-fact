package com.eburtis.fact.security;

import java.io.IOException;
import java.util.List;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.service.CompteService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lit le jeton de chaque requête et retrouve le compte. Un compte désactivé, ou dont le mot
 * de passe a changé depuis l'émission du jeton, n'est plus reconnu. Un compte au mot de
 * passe provisoire n'atteint que l'authentification tant qu'il ne l'a pas changé.
 */
@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private final JwtTokenUtils jwtTokenUtils;
    private final CompteService compteService;

    public JwtRequestFilter(JwtTokenUtils jwtTokenUtils, CompteService compteService) {
        this.jwtTokenUtils = jwtTokenUtils;
        this.compteService = compteService;
    }

    /** Chemin sans le contexte : sous Tomcat, l'adresse commence par le nom du .war. */
    private static String chemin(HttpServletRequest requete) {
        return requete.getRequestURI().substring(requete.getContextPath().length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requete) {
        return !chemin(requete).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requete, HttpServletResponse reponse, FilterChain suite)
            throws ServletException, IOException {
        String entete = requete.getHeader(HttpHeaders.AUTHORIZATION);
        if (entete != null && entete.startsWith("Bearer ")) {
            JwtTokenUtils.Contenu contenu = jwtTokenUtils.lire(entete.substring(7).trim());
            Utilisateur u = contenu == null ? null : compteService.utilisateurActif(contenu.identifiant()).orElse(null);
            if (u != null && u.versionSession() == contenu.version()) {
                String chemin = chemin(requete);
                boolean libre = chemin.startsWith("/api/auth/") || chemin.equals("/api/sante") || chemin.startsWith("/api/referentiel");
                if (u.doitChanger() && !libre) {
                    reponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    reponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    reponse.setCharacterEncoding("UTF-8");
                    reponse.getWriter().write("{\"statut\":403,\"code\":\"MOT_DE_PASSE_PROVISOIRE\","
                            + "\"message\":\"Mot de passe provisoire : changez-le avant de continuer.\"}");
                    return;
                }
                List<SimpleGrantedAuthority> roles = u.droits().stream().map(d -> new SimpleGrantedAuthority("DROIT_" + d)).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, roles));
            }
        }
        suite.doFilter(requete, reponse);
    }
}
