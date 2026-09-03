# Build and run on Java 25.
#
# Railway's default buildpacks ship an older JDK; this Dockerfile pins the
# toolchain so the platform has no say in it. Two stages: Maven builds the
# fast-jar, a bare JRE runs it. The Maven stage caches dependencies in a
# separate layer, so code-only pushes rebuild in seconds instead of minutes.

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies first: this layer only invalidates when the pom changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

# Railway runs schema migrations in this same image before starting the app.
# The PostgreSQL client keeps that path independent from Quarkus/Hibernate, so
# schema validation cannot run before the additive DDL has been applied.
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# Quarkus fast-jar layout: lib/ changes rarely, app code often — copy in
# that order so image pushes stay small.
COPY --from=build /build/target/quarkus-app/lib/ ./lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ ./app/
COPY --from=build /build/target/quarkus-app/quarkus/ ./quarkus/

COPY scripts/run-postgresql-schema-migrations.sh ./scripts/
COPY docs/migrations/2026-08-27/public-pickup-locations-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-01/product-supplier-agreement-photos-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-01/product-line-discount-target-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-03/document-media-manager-postgresql.sql ./migrations/
RUN chmod 0555 ./scripts/run-postgresql-schema-migrations.sh

# Railway injects PORT; application.properties picks it up.
EXPOSE 8080
ENV QUARKUS_PROFILE=prod
CMD ["java", "-jar", "quarkus-run.jar"]
