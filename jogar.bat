@echo off
rem Compila (se preciso) e inicia o Terralume.
cd /d "%~dp0"
if not exist target\terralume.jar call mvn -q -B package
java -jar target\terralume.jar %*
