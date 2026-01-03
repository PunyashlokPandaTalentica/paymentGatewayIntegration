# build stage
FROM maven:3.9.4-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
# copy minimal sources to leverage layer caching (you can adjust)
COPY src ./src
RUN mvn -B -DskipTests package

# runtime stage
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -jar /app/app.jar" ]
