package br.estudo.conciliacao.lambda.config;

import java.util.Properties;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Clientes de infraestrutura criados UMA vez, na subida do contexto (cold start), e
 * reaproveitados em todas as invocações enquanto o container da Lambda estiver quente.
 * No Node seria criar o client no escopo do módulo, fora do {@code exports.handler}.
 */
@Configuration(proxyBeanMethods = false)
public class InfraConfig {

    @Bean(destroyMethod = "close")
    KafkaProducer<String, String> produtorKafka(RecebimentoProperties props) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "lambda-recebimento-arquivo");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Só considera enviado quando todas as réplicas em sincronia confirmaram.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // Retentativas internas do producer não duplicam a mensagem no broker.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Limites curtos: a Lambda tem timeout; melhor falhar rápido e deixar a AWS retentar.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        return new KafkaProducer<>(config);
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(RecebimentoProperties props) {
        // Região e endpoint vêm das variáveis AWS_REGION e AWS_ENDPOINT_URL, que a Lambda
        // (e o LocalStack) já definem no ambiente de execução.
        return S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .forcePathStyle(props.s3().forcePathStyle())
                .build();
    }

    // O cliente do registro de idempotência (Postgres ou DynamoDB) fica em IdempotenciaConfig.
}
