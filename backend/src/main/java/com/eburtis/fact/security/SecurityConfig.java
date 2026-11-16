package com.eburtis.fact.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Sans session côté serveur : chaque appel à l'API porte son jeton. L'application Angular,
 * fichiers statiques, est publique.
 * <p>
 * Sont publiques par nature les adresses qui alimentent le site web : l'état de santé, les
 * listes de référence (communes, quartiers, types de biens — elles remplissent le moteur de
 * recherche) et, plus tard, le catalogue des annonces et les formulaires de demande
 * (/api/public/). Tout le reste de l'API exige un compte ; les droits sont contrôlés dans
 * chaque contrôleur (SecurityUtils.exigerDroit).
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_URL = {
            "/api/auth/connexion",
            "/api/sante",
            "/api/referentiel/**",
            "/api/public/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtRequestFilter jwtRequestFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Les serveurs de tuiles cartographiques exigent l'origine de la page (pas son chemin).
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_URL).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, rep, ex) -> {
                    rep.setStatus(401);
                    rep.setContentType("application/json");
                    rep.setCharacterEncoding("UTF-8");
                    rep.getWriter().write("{\"statut\":401,\"message\":\"Connexion requise.\"}");
                }))
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Le filtre ne tourne que dans la chaîne de sécurité, pas une seconde fois comme filtre du serveur. */
    @Bean
    public FilterRegistrationBean<JwtRequestFilter> filtreHorsServeur(JwtRequestFilter jwtRequestFilter) {
        FilterRegistrationBean<JwtRequestFilter> inscription = new FilterRegistrationBean<>(jwtRequestFilter);
        inscription.setEnabled(false);
        return inscription;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }
}
