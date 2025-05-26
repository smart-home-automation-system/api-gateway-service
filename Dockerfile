FROM amd64/eclipse-temurin:21.0.6_7-jdk-alpine AS builder
WORKDIR /application
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=layertools -jar application.jar extract

FROM amd64/eclipse-temurin:21.0.6_7-jdk-alpine
WORKDIR /application
COPY --from=builder application/dependencies/ ./
COPY --from=builder application/spring-boot-loader/ ./
COPY --from=builder application/snapshot-dependencies/ ./
COPY --from=builder application/application/ ./

RUN apk add --no-cache tzdata
ENV TZ="Europe/Warsaw"

VOLUME /tmp
USER nobody:nobody

ENTRYPOINT ["java", "-XshowSettings:vm", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
EXPOSE 6200 9200