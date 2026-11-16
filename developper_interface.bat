@echo off
rem Interface Angular en mode developpement (rechargement a chaud) : http://localhost:4201/
rem L'API doit tourner a cote (demarrer.bat) : les appels /api lui sont transmis.
cd /d "%~dp0frontend"
if not exist node_modules call npm install
call npm start
