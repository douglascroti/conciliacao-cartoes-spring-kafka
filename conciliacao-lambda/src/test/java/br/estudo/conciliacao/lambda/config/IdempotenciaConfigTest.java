package br.estudo.conciliacao.lambda.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotencia;
import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotenciaDynamo;
import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotenciaPostgres;
import br.estudo.conciliacao.lambda.infra.ConexaoBanco;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Confere quais beans existem para cada valor de {@code conciliacao.idempotencia.provedor}.
 * O ApplicationContextRunner sobe só as configurações indicadas, sem banco nem LocalStack
 * (a ConexaoBanco só conecta no primeiro uso e o DynamoDbClient não chama a AWS ao ser criado).
 */
class IdempotenciaConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(Propriedades.class, IdempotenciaConfig.class)
            .withPropertyValues(
                    "conciliacao.banco.url=jdbc:postgresql://localhost:5432/conciliacao",
                    "conciliacao.banco.usuario=conciliacao",
                    "conciliacao.banco.senha=conciliacao",
                    "conciliacao.idempotencia.tabela-dynamo=arquivo-recebido")
            .withSystemProperties("aws.region=us-east-1");

    @Test
    void semPropriedadeUsaPostgres() {
        contexto.run(ctx -> {
            assertThat(ctx).getBean(RegistroIdempotencia.class).isInstanceOf(RegistroIdempotenciaPostgres.class);
            assertThat(ctx).hasSingleBean(ConexaoBanco.class).doesNotHaveBean(DynamoDbClient.class);
        });
    }

    @Test
    void dynamodbNaoCriaConexaoComOBanco() {
        contexto.withPropertyValues("conciliacao.idempotencia.provedor=dynamodb").run(ctx -> {
            assertThat(ctx).getBean(RegistroIdempotencia.class).isInstanceOf(RegistroIdempotenciaDynamo.class);
            assertThat(ctx).hasSingleBean(DynamoDbClient.class).doesNotHaveBean(ConexaoBanco.class);
        });
    }

    @Test
    void provedorInvalidoImpedeASubida() {
        contexto.withPropertyValues("conciliacao.idempotencia.provedor=postgress")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RecebimentoProperties.class)
    static class Propriedades {
    }
}
