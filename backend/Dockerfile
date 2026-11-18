# Image de l'API FACT Immobilier pour Render (contexte de construction : la racine du dépôt,
# car le build embarque aussi docs/guide-utilisateur.md pour l'assistant).

# ---- Étape 1 : compilation ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY backend/ backend/
COPY docs/guide-utilisateur.md docs/guide-utilisateur.md
WORKDIR /app/backend
RUN chmod +x gradlew && ./gradlew clean bootJar -x test --no-daemon

# ---- Étape 2 : exécution ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/backend/build/libs/fact-immobilier.jar app.jar

# Le plan gratuit de Render offre 512 Mo de mémoire : on borne le tas Java.
ENV JAVA_OPTS="-Xmx340m -XX:+UseSerialGC -XX:MaxMetaspaceSize=160m"
# Fichiers déposés (photos, PDF). Sur le plan gratuit, ce disque est effacé à chaque redémarrage.
ENV FACT_DOSSIER_FICHIERS=/app/uploads
ENV FACT_FICHIER_PROVISOIRES=/app/comptes_provisoires.txt

EXPOSE 8791
# Render fournit le port dans PORT ; à défaut, 8791.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8791} -jar app.jar"]
