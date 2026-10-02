package br.estudo.conciliacao.batch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações tipadas do serviço (application.yml, que lê variáveis de ambiente).
 */
@ConfigurationProperties(prefix = "conciliacao")
public record ConciliacaoProperties(S3 s3, Job job) {

    /**
     * @param endpoint       vazio na AWS real; no LocalStack, {@code http://localhost:4566}
     * @param forcePathStyle LocalStack não tem DNS curinga para buckets; na AWS real, false
     */
    public record S3(String endpoint, String regiao, boolean forcePathStyle) {
    }

    /**
     * @param tamanhoChunk          linhas lidas, processadas e gravadas por transação
     * @param execucoesSimultaneas  quantos arquivos são processados ao mesmo tempo
     * @param limiteLinhasInvalidas acima disso o arquivo é considerado corrompido e o job falha
     */
    public record Job(int tamanhoChunk, int execucoesSimultaneas, int limiteLinhasInvalidas) {
    }
}
