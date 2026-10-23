# syntax=docker/dockerfile:1
#
# Pagina web del tabellone (target wasmJs) servita da nginx, che fa anche da proxy verso RFI e
# Trenord: i due siti non mandano intestazioni CORS, quindi la pagina li chiama sulla stessa
# origine (/rfi/*, /trenord/*).
#
#   docker build -t departure-board-web .
#   docker run --rm -p 8080:8080 departure-board-web      → http://localhost:8080

# --- Build: distribuzione wasmJs con Gradle (non serve l'Android SDK) ---
# Gira sempre sull'architettura di chi compila: il wasm è lo stesso per amd64 e arm64, quindi le
# immagini multi-arch non passano Gradle sotto emulazione.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :composeApp:wasmJsBrowserDistribution

# --- Runtime: nginx senza privilegi di root, in ascolto sulla 8080 ---
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/composeApp/build/dist/wasmJs/productionExecutable/ /usr/share/nginx/html/
EXPOSE 8080
