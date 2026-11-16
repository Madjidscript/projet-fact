@echo off
rem FACT Immobilier : le site public, en developpement, sur http://localhost:4320/
rem Lancer aussi demarrer.bat : le site y lit la liste des communes.
cd /d "%~dp0site"
if not exist node_modules call npm install
call npx ng serve --port 4320 --open
