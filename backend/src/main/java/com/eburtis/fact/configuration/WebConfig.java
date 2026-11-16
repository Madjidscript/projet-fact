package com.eburtis.fact.configuration;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * L'application Angular est servie par l'API (dossier static). Toute adresse qui n'est ni
 * un fichier ni l'API rend index.html : c'est Angular qui choisit alors la page.
 * <p>
 * Cache du navigateur : les fichiers produits par la compilation portent une empreinte dans
 * leur nom (main-ICY6HVS4.js) et ne changent donc jamais, ils sont gardés un an ; polices et
 * images une semaine ; index.html est revérifié à chaque visite (réponse 304 sans contenu s'il
 * n'a pas changé), pour qu'une nouvelle version soit vue dès son déploiement.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FactProperties proprietes;

    public WebConfig(FactProperties proprietes) {
        this.proprietes = proprietes;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/main-*.js", "/chunk-*.js", "/polyfills-*.js", "/styles-*.css", "/media/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/polices/**")
                .addResourceLocations("classpath:/static/polices/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/marque/**")
                .addResourceLocations("classpath:/static/marque/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        registry.addResourceHandler("/**")
                .setCacheControl(CacheControl.noCache())
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String chemin, Resource dossier) throws IOException {
                        Resource demande = dossier.createRelative(chemin);
                        if (demande.exists() && demande.isReadable()) {
                            return demande;
                        }
                        if (chemin.startsWith("api/") || chemin.contains(".")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (proprietes.cors() != null && proprietes.cors().origines() != null && !proprietes.cors().origines().isEmpty()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(proprietes.cors().origines().toArray(String[]::new))
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .exposedHeaders("Content-Disposition");
        }
    }
}
