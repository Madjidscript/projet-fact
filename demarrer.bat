@echo off
rem FACT Immobilier : lance l'API et l'application sur http://localhost:8791/
rem Base par defaut : fact_immobilier sur localhost. Voir README.md.
rem Ajouter « demo » en argument pour charger les donnees fictives de demonstration.
cd /d "%~dp0backend"
if /i "%1"=="demo" (
  call gradlew.bat bootRun --args="--spring.profiles.active=demo"
) else (
  call gradlew.bat bootRun
)
