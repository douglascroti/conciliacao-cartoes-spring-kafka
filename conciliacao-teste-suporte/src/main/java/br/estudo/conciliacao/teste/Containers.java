package br.estudo.conciliacao.teste;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Containers dos testes de integração, com as mesmas imagens do docker-compose.
 *
 * <p>Cada um sobe na primeira vez que um teste pede e é reaproveitado por todas as classes de teste
 * do módulo (padrão "singleton container"): subir o LocalStack leva ~10 s, e fazer isso por classe
 * deixaria a suíte lenta. No fim da execução o Testcontainers (Ryuk) remove tudo.
 *
 * <p>No Node seria um {@code globalSetup} do Jest que sobe os containers uma vez para a suíte inteira.
 */
public final class Containers {

    public static final List<String> TOPICOS = List.of(
            "conciliacao.arquivo-recebido", "conciliacao.resultado", "conciliacao.erro");

    // As imagens abaixo devem ser as mesmas do docker-compose.yml. O Dependabot atualiza só o
    // compose: ao aceitar um PR dele com imagem nova, atualize aqui também.
    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static LocalStackContainer localstack;

    private Containers() {
    }

    /** PostgreSQL com as migrations do projeto (infra/postgres/migrations) já aplicadas. */
    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("conciliacao")
                    .withUsername("conciliacao")
                    .withPassword("conciliacao");
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("filesystem:" + Projeto.raiz().resolve("infra/postgres/migrations"))
                    .load()
                    .migrate();
        }
        return postgres;
    }

    /** Kafka (KRaft) com os tópicos da conciliação criados. */
    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer("apache/kafka:4.3.1");
            kafka.start();
            try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
                admin.createTopics(TOPICOS.stream().map(t -> new NewTopic(t, 3, (short) 1)).toList()).all().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Falha ao criar os tópicos de teste", e);
            }
        }
        return kafka;
    }

    /**
     * LocalStack com S3 e DynamoDB. A classe de teste deve ter {@link ExigeLocalStack}, que a pula
     * inteira quando não há token; o {@code Assumptions} abaixo é só a rede de segurança para quem
     * esquecer a anotação.
     */
    public static synchronized LocalStackContainer localstack() {
        if (localstack == null) {
            String token = Projeto.tokenLocalStack().orElse(null);
            Assumptions.assumeTrue(token != null,
                    "LOCALSTACK_AUTH_TOKEN não definido (nem no ambiente nem no .env): testes com LocalStack pulados");
            localstack = new LocalStackContainer("localstack/localstack:2026.9.0")
                    .withServices("s3", "dynamodb")
                    .withEnv("LOCALSTACK_AUTH_TOKEN", token);
            localstack.start();
        }
        return localstack;
    }
}
