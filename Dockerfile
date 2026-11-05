# Tests are not run here: they need Docker themselves and run in CI through ./mvnw verify.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -q -DskipTests package \
    && java -Djarmode=tools -jar target/stockguard.jar extract --layers --destination /layers

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S stockguard && adduser -S stockguard -G stockguard
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER stockguard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "stockguard.jar"]
