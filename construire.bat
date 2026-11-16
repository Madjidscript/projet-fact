@echo off
rem Produit backend\build\libs\fact-immobilier.jar : l'API et l'interface dans un seul fichier.
rem   java -jar backend\build\libs\fact-immobilier.jar
cd /d "%~dp0frontend"
if not exist node_modules call npm install
call npm run build || exit /b 1
cd /d "%~dp0backend"
call gradlew.bat bootJar
