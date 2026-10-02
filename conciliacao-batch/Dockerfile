# syntax=docker/dockerfile:1
#
# Imagem do serviço de conciliação. Build a partir da raiz do repositório:
#   docker build -f conciliacao-batch/Dockerfile -t conciliacao-batch .
# (o docker compose já faz isso: serviço conciliacao-batch)

# ---- 1) Build: JDK + Maven Wrapper ----
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /src

# POMs e wrapper primeiro: mudar só o código Java não invalida estas camadas.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY conciliacao-eventos/pom.xml conciliacao-eventos/
COPY conciliacao-lambda/pom.xml conciliacao-lambda/
COPY conciliacao-batch/pom.xml conciliacao-batch/
# O POM pai lista todos os módulos: sem os POMs deles o Maven não monta o reactor.
COPY gerador-dados/pom.xml gerador-dados/
COPY conciliacao-teste-suporte/pom.xml conciliacao-teste-suporte/
COPY conciliacao-eventos/src conciliacao-eventos/src
# Os testes do batch são compilados (o -DskipTests só pula a execução) e usam o módulo de suporte.
COPY conciliacao-teste-suporte/src conciliacao-teste-suporte/src
COPY conciliacao-batch/src conciliacao-batch/src

# O cache do ~/.m2 sobrevive entre builds (não vai para a imagem): só baixa o que mudou.
# Os testes rodam no build do host/CI; aqui o foco é empacotar.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl conciliacao-batch -am package -DskipTests

# Separa o JAR em camadas (dependências, loader, código) para a imagem final.
RUN java -Djarmode=tools -jar conciliacao-batch/target/conciliacao-batch-*.jar \
    extract --layers --launcher --destination /extraido

# ---- 2) Execução: só o JRE ----
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
WORKDIR /app

# Usuário sem privilégios: o processo não roda como root dentro do container.
RUN addgroup -S app && adduser -S app -G app && mkdir logs && chown app:app logs
USER app

# Da camada que menos muda para a que mais muda: num rebuild com só código novo, as
# dependências (dezenas de MB) vêm do cache e só a última camada é refeita.
COPY --from=build /extraido/dependencies/ ./
COPY --from=build /extraido/spring-boot-loader/ ./
COPY --from=build /extraido/snapshot-dependencies/ ./
COPY --from=build /extraido/application/ ./

# A JVM respeita o limite de memória do container: heap até 75% dele.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
