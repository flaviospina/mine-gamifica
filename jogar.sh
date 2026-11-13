#!/bin/sh
# Compila (se preciso) e inicia o Terralume. Argumentos extras sao repassados ao jogo.
cd "$(dirname "$0")" || exit 1
[ -f target/terralume.jar ] || mvn -q -B package || exit 1
exec java -jar target/terralume.jar "$@"
