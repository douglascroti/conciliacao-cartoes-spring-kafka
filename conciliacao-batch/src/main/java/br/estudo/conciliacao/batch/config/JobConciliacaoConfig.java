package br.estudo.conciliacao.batch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import br.estudo.conciliacao.batch.job.LogExecucaoJobListener;
import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.batch.leitura.LinhaArquivoLineMapper;
import br.estudo.conciliacao.batch.leitura.ObjetoS3Resource;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * O job de conciliação: um step orientado a chunks que lê o arquivo do S3.
 *
 * <p>Chunk = lote. O Spring Batch lê N linhas, processa as N e grava as N numa transação; ao
 * confirmar, salva no JobRepository até onde chegou. Se o processo cair, um restart continua do
 * último lote confirmado em vez de começar do zero.
 */
@Configuration(proxyBeanMethods = false)
public class JobConciliacaoConfig {

    public static final String NOME_JOB = "conciliacaoArquivo";

    private static final Logger log = LoggerFactory.getLogger(JobConciliacaoConfig.class);

    @Bean
    Job conciliacaoArquivo(JobRepository jobRepository, Step conciliarLinhas) {
        return new JobBuilder(NOME_JOB, jobRepository)
                .listener(new LogExecucaoJobListener())
                .start(conciliarLinhas)
                .build();
    }

    @Bean
    Step conciliarLinhas(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                         FlatFileItemReader<LinhaArquivo> leitorArquivo, ConciliacaoProperties props) {
        return new StepBuilder("conciliarLinhas", jobRepository)
                .<LinhaArquivo, LinhaArquivo>chunk(props.job().tamanhoChunk())
                .transactionManager(transactionManager)
                .reader(leitorArquivo)
                .writer(registrarChunk())
                .build();
    }

    /**
     * {@code @StepScope}: um reader novo por execução do step. Sem isso seria um singleton, criado
     * na subida da aplicação, quando ainda não se sabe qual arquivo ler; os parâmetros do job
     * ({@code #{jobParameters[...]}}) só existem quando o job é lançado.
     */
    @Bean
    @StepScope
    FlatFileItemReader<LinhaArquivo> leitorArquivo(S3Client s3,
                                                   @Value("#{jobParameters['bucket']}") String bucket,
                                                   @Value("#{jobParameters['chave']}") String chave) {
        return new FlatFileItemReaderBuilder<LinhaArquivo>()
                .name("leitorArquivo")   // prefixo das chaves que guardam a posição de leitura no restart
                .resource(new ObjetoS3Resource(s3, bucket, chave))
                .encoding("UTF-8")
                .linesToSkip(1)          // cabeçalho (já validado pela Lambda)
                .lineMapper(new LinhaArquivoLineMapper())
                .build();
    }

    // Provisório (3a): só registra o lote. Na 3b vira a gravação do resultado e a publicação no Kafka.
    private static ItemWriter<LinhaArquivo> registrarChunk() {
        return chunk -> {
            var itens = chunk.getItems();
            log.info("Chunk com {} linhas (linhas {} a {})", itens.size(),
                    itens.getFirst().numeroLinha(), itens.getLast().numeroLinha());
            log.debug("Itens do chunk: {}", itens);
        };
    }
}
