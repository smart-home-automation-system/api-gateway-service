FROM amd64/eclipse-temurin:21.0.6_7-jdk-alpine AS builder
WORKDIR /application
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM amd64/eclipse-temurin:21.0.6_7-jdk-alpine
WORKDIR /application
COPY --from=builder /application/extracted/dependencies/ ./
COPY --from=builder /application/extracted/spring-boot-loader/ ./
COPY --from=builder /application/extracted/snapshot-dependencies/ ./
COPY --from=builder /application/extracted/application/ ./

RUN apk add --no-cache tzdata
ENV TZ="Europe/Warsaw"

VOLUME /tmp
USER nobody:nobody

ENTRYPOINT ["java", "-XshowSettings:vm", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "application.jar"]
EXPOSE 6200 8200