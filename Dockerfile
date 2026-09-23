FROM docker.io/eclipse-temurin:21.0.9_10-jdk-ubi9-minimal as builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle

RUN chmod +x ./gradlew
# 2. 배포판만 미리 다운로드 (이 레이어는 파일이 안 바뀌면 캐싱됨)
RUN ./gradlew --version

COPY . .

ARG USERNAME
ARG TOKEN
ENV USERNAME=$USERNAME
ENV TOKEN=$TOKEN

RUN ls -al
RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon build -x test
RUN ls -al
RUN ls -al build/libs

FROM docker.io/eclipse-temurin:21.0.9_10-jdk-ubi9-minimal

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar .

RUN ls -al
RUN java --version

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "prompt-box.jar"]