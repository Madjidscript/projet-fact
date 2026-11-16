package com.eburtis.fact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * FACT Immobilier — site web et application SIG de gestion du patrimoine.
 * <p>
 * Une API Spring Boot sur une base PostgreSQL / PostGIS (migrations Flyway), et
 * l'application Angular servie par la même application. Se lance par java -jar, ou se
 * déploie en .war sous Tomcat.
 * <p>
 * Références : cahier des charges CDC-FACT-2026-01, spécifications SFD-FACT-2026-01.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class FactApplication extends SpringBootServletInitializer {

    public static final Logger log = LoggerFactory.getLogger(FactApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FactApplication.class, args);
        log.info("""

                ================================================================================================
                                        FACT Immobilier — API démarrée
                ================================================================================================""");
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(FactApplication.class);
    }
}
