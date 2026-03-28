# ---------- BUILD STAGE ----------
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY services/auth-service/pom.xml services/auth-service/pom.xml
COPY services/policy-service/pom.xml services/policy-service/pom.xml
COPY services/claims-service/pom.xml services/claims-service/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
RUN mvn -B -q -pl services/claims-service -am dependency:go-offline

COPY services services
RUN mvn -B -q -pl services/claims-service -am clean package -DskipTests

# ---------- RUNTIME STAGE ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /workspace/services/claims-service/target/*jar app.jar

EXPOSE 8083
ENTRYPOINT ["java","-jar","app.jar"]
