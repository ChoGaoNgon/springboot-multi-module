# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
ARG APP_MODULE=api
WORKDIR /app

# Copy ALL pom files (dependency-cache layer)
COPY pom.xml pom.xml
COPY framework/pom.xml framework/pom.xml
COPY security/pom.xml security/pom.xml
COPY entity/pom.xml entity/pom.xml
COPY dto/pom.xml dto/pom.xml
COPY persistence/pom.xml persistence/pom.xml
COPY business/pom.xml business/pom.xml
COPY business/business-interface/pom.xml business/business-interface/pom.xml
COPY business/business-implementation/pom.xml business/business-implementation/pom.xml
COPY application/pom.xml application/pom.xml
COPY application/api/pom.xml application/api/pom.xml
COPY application/batch/pom.xml application/batch/pom.xml
COPY mybatis-generator/pom.xml mybatis-generator/pom.xml
COPY mybatis-schema-migration/pom.xml mybatis-schema-migration/pom.xml
RUN mvn -am -pl application/${APP_MODULE} -DskipTests dependency:go-offline

# Copy library + target app source
COPY framework framework
COPY security security
COPY entity entity
COPY dto dto
COPY persistence persistence
COPY business business
COPY application/${APP_MODULE} application/${APP_MODULE}
RUN mvn -am -pl application/${APP_MODULE} clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime
ARG APP_MODULE=api
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --no-create-home app \
    && mkdir -p /var/log/app/archived && chown -R app:app /var/log/app
COPY --from=build /app/application/${APP_MODULE}/target/${APP_MODULE}-0.0.1-SNAPSHOT.jar app.jar
RUN chown app:app app.jar
USER app
CMD ["java", "-jar", "app.jar"]
