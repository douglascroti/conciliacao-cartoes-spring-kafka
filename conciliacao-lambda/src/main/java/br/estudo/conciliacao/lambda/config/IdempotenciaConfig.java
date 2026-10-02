package br.estudo.conciliacao.lambda.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotencia;
import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotenciaDynamo;
import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotenciaPostgres;
import br.estudo.conciliacao.lambda.infra.ConexaoBanco;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Escolhe o provedor de idempotência por {@code conciliacao.idempotencia.provedor}.
 *
 * <p>Cada classe interna só é processada se a condição bater ({@code @ConditionalOnProperty}),
 * então só os beans do provedor escolhido existem: com DynamoDB não há {@link ConexaoBanco}
 * (nenhuma conexão JDBC aberta), e com Postgres não há cliente do DynamoDB. As duas
 * dependências estão no JAR; a decisão é feita na subida do contexto, sem recompilar.
 */
@Configuration(proxyBeanMethods = false)
public class IdempotenciaConfig {

    private static final Logger log = LoggerFactory.getLogger(IdempotenciaConfig.class);

    private static final String PROPRIEDADE = "conciliacao.idempotencia.provedor";

    // matchIfMissing: sem a propriedade definida, vale o Postgres (padrão).
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROPRIEDADE, havingValue = "postgres", matchIfMissing = true)
    static class Postgres {

        @Bean(destroyMethod = "close")
        ConexaoBanco conexaoBanco(RecebimentoProperties props) {
            return new ConexaoBanco(props.banco().url(), props.banco().usuario(), props.banco().senha());
        }

        @Bean
        RegistroIdempotencia registroIdempotencia(ConexaoBanco conexaoBanco) {
            log.info("Idempotência no PostgreSQL");
            return new RegistroIdempotenciaPostgres(conexaoBanco);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROPRIEDADE, havingValue = "dynamodb")
    static class Dynamo {

        @Bean(destroyMethod = "close")
        DynamoDbClient dynamoDbClient() {
            // Como no S3Client: região e endpoint vêm de AWS_REGION e AWS_ENDPOINT_URL.
            return DynamoDbClient.builder()
                    .httpClient(UrlConnectionHttpClient.create())
                    .build();
        }

        @Bean
        RegistroIdempotencia registroIdempotencia(DynamoDbClient dynamo, RecebimentoProperties props) {
            String tabela = props.idempotencia().tabelaDynamo();
            log.info("Idempotência no DynamoDB (tabela {})", tabela);
            return new RegistroIdempotenciaDynamo(dynamo, tabela);
        }
    }
}
