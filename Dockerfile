# ---- Build stage ----
FROM gradle:8.14.1-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle installDist --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/ai-turbo ./
EXPOSE 8080
ENV PORT=8080
ENTRYPOINT ["/app/bin/ai-turbo"]
