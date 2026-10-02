package br.estudo.conciliacao.lambda.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações tipadas, lidas do application.yml (que por sua vez lê variáveis de ambiente).
 * Equivale a validar e tipar o {@code process.env} num objeto de config no Node.
 */
@ConfigurationProperties(prefix = "conciliacao")
public record RecebimentoProperties(Kafka kafka, Banco banco, S3 s3, Idempotencia idempotencia) {

    public record Kafka(String bootstrapServers, String topico) {
    }

    public record Banco(String url, String usuario, String senha) {
    }

    public record S3(String prefixoEntrada, String prefixoRejeitados, boolean forcePathStyle) {
    }

    /**
     * Onde fica o registro de idempotência. Como é um enum, um valor inválido em
     * IDEMPOTENCIA_PROVEDOR derruba a subida com erro claro (em vez de "bean não encontrado").
     */
    public record Idempotencia(Provedor provedor, String tabelaDynamo) {
    }

    public enum Provedor {
        POSTGRES, DYNAMODB
    }
}
