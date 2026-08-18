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

# Quarkus fast-jar layout: lib/ changes rarely, app code often — copy in
# that order so image pushes stay small.
COPY --from=build /build/target/quarkus-app/lib/ ./lib/
COPY --from=build /build/target/quarkus-app/*.jar ./
COPY --from=build /build/target/quarkus-app/app/ ./app/
COPY --from=build /build/target/quarkus-app/quarkus/ ./quarkus/

# Railway injects PORT; application.properties picks it up.
EXPOSE 8080
ENV QUARKUS_PROFILE=prod
CMD ["java", "-jar", "quarkus-run.jar"]
