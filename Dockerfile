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
COPY docs/migrations/2026-09-03/media-folders-shares-web-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-03/sales-order-column-lengths-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-03/website-visits-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-03/purchase-line-issue-note-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-04/purchase-order-inspection-cost-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-04/purchase-order-other-costs-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-04/orders-archived-at-postgresql.sql ./migrations/
COPY docs/migrations/2026-09-04/sales-order-extra-lines-postgresql.sql ./migrations/
RUN chmod 0555 ./scripts/run-postgresql-schema-migrations.sh

# Soft memory discipline rather than a tight cap: G1 collects while idle and
# hands freed heap back to the OS within minutes, so a PDF or photo burst no
# longer parks gigabytes (the JVM otherwise sizes its heap at a quarter of the
# host and never shrinks). The 3 GB ceiling is far above anything the app
# needs - the heaviest renders peak around 500 MB - so throughput is never
# throttled; only a runaway would reach it, and then the process exits so the
# platform restarts it instead of thrashing.
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -Xms256m -Xmx3g -XX:SoftMaxHeapSize=1g \
    -XX:G1PeriodicGCInterval=120000 -XX:-G1PeriodicGCInvokesConcurrent \
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 \
    -XX:MaxMetaspaceSize=320m -Xss512k -XX:+ExitOnOutOfMemoryError"

# Railway injects PORT; application.properties picks it up.
EXPOSE 8080
ENV QUARKUS_PROFILE=prod
CMD ["java", "-jar", "quarkus-run.jar"]
