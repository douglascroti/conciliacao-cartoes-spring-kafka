package br.estudo.conciliacao.batch.config;

import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import br.estudo.conciliacao.batch.conciliacao.Conciliador;
import br.estudo.conciliacao.batch.conciliacao.TransacaoAutorizada;
import br.estudo.conciliacao.batch.job.AusentesWriter;
import br.estudo.conciliacao.batch.job.ConciliacaoLinhasWriter;
import br.estudo.conciliacao.batch.job.LimitePercentualLinhasInvalidas;
import br.estudo.conciliacao.batch.job.LinhaInvalidaSkipListener;
import br.estudo.conciliacao.batch.job.LogExecucaoJobListener;
import br.estudo.conciliacao.batch.job.StatusArquivoJobListener;
import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.batch.leitura.LinhaArquivoLineMapper;
import br.estudo.conciliacao.batch.leitura.ObjetoS3Resource;
import br.estudo.conciliacao.batch.persistencia.RepositorioArquivo;
import br.estudo.conciliacao.batch.persistencia.RepositorioConciliacao;
import br.estudo.conciliacao.batch.publicacao.PublicadorConciliacao;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * O job de conciliação, com dois steps em sequência:
 * <ol>
 *   <li>{@code conciliarLinhas}: lê o arquivo do S3 e concilia cada linha com as autorizações;</li>
 *   <li>{@code registrarAusentes}: autorizações do dia que não vieram no arquivo.</li>
 * </ol>
 *
 * <p>Chunk = lote. O Spring Batch lê N itens, grava os N numa transação e, ao confirmar, salva no
 * JobRepository até onde chegou. Se o processo cair, um restart continua do último lote
 * confirmado em vez de começar do zero. O segundo step só roda se o primeiro terminar bem.
 */
@Configuration(proxyBeanMethods = false)
public class JobConciliacaoConfig {

    public static final String NOME_JOB = "conciliacaoArquivo";

    /*
     * Autorizações do dia sem resultado neste arquivo. O NOT EXISTS também torna o step seguro
     * num restart: o que já foi gravado como ausente deixa de aparecer na consulta.
     */
    private static final String SQL_AUSENTES = "SELECT " + RepositorioConciliacao.COLUNAS_AUTORIZACAO + """
             FROM transacao_autorizada t
            WHERE t.data_transacao >= ? AND t.data_transacao < ?
              AND NOT EXISTS (SELECT 1 FROM resultado_conciliacao r
                               WHERE r.id_arquivo = ? AND r.nsu = t.nsu AND r.codigo_autorizacao = t.codigo_autorizacao)
            ORDER BY t.id
            """;

    @Bean
    Job conciliacaoArquivo(JobRepository jobRepository, Step conciliarLinhas, Step registrarAusentes,
                           RepositorioConciliacao repositorio, RepositorioArquivo repositorioArquivo) {
        return new JobBuilder(NOME_JOB, jobRepository)
                .listener(new StatusArquivoJobListener(repositorioArquivo))
                .listener(new LogExecucaoJobListener(repositorio))
                .start(conciliarLinhas)
                .next(registrarAusentes)
                .build();
    }

    @Bean
    Conciliador conciliador() {
        return new Conciliador();
    }

    // ---- Step 1: linhas do arquivo ----

    @Bean
    Step conciliarLinhas(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                         FlatFileItemReader<LinhaArquivo> leitorArquivo, ConciliacaoLinhasWriter conciliacaoLinhasWriter,
                         LinhaInvalidaSkipListener linhaInvalidaSkipListener, ConciliacaoProperties props) {
        return new StepBuilder("conciliarLinhas", jobRepository)
                .<LinhaArquivo, LinhaArquivo>chunk(props.job().tamanhoChunk())
                .transactionManager(transactionManager)
                .reader(leitorArquivo)
                .writer(conciliacaoLinhasWriter)   // sem processor: a conciliação é feita por lote no writer
                // Linha fora do layout é pulada (e publicada em conciliacao.erro) sem parar o arquivo.
                // Só erros de leitura: falha de banco ou de Kafka no writer continua derrubando o chunk.
                // Acima do limite (percentual das linhas lidas), o arquivo é tratado como corrompido e o job falha.
                .faultTolerant()
                .skipPolicy(new LimitePercentualLinhasInvalidas(props.job().percentualMaximoLinhasInvalidas(),
                        props.job().minimoLinhasInvalidas()))
                .skipListener(linhaInvalidaSkipListener)
                .build();
    }

    @Bean
    @StepScope
    LinhaInvalidaSkipListener linhaInvalidaSkipListener(PublicadorConciliacao publicador,
                                                        @Value("#{jobParameters['idArquivo']}") String idArquivo,
                                                        @Value("#{jobParameters['nomeArquivo']}") String nomeArquivo) {
        return new LinhaInvalidaSkipListener(publicador, UUID.fromString(idArquivo), nomeArquivo);
    }

    /**
     * {@code @StepScope}: um bean novo por execução do step. Sem isso seria um singleton, criado
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

    @Bean
    @StepScope
    ConciliacaoLinhasWriter conciliacaoLinhasWriter(RepositorioConciliacao repositorio, PublicadorConciliacao publicador,
                                                    Conciliador conciliador,
                                                    @Value("#{jobParameters['idArquivo']}") String idArquivo,
                                                    @Value("#{jobParameters['dataReferencia']}") LocalDate dataReferencia) {
        return new ConciliacaoLinhasWriter(repositorio, publicador, conciliador, UUID.fromString(idArquivo), dataReferencia);
    }

    // ---- Step 2: autorizações ausentes no arquivo ----

    @Bean
    Step registrarAusentes(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           JdbcCursorItemReader<TransacaoAutorizada> leitorAusentes, AusentesWriter ausentesWriter,
                           ConciliacaoProperties props) {
        return new StepBuilder("registrarAusentes", jobRepository)
                .<TransacaoAutorizada, TransacaoAutorizada>chunk(props.job().tamanhoChunk())
                .transactionManager(transactionManager)
                .reader(leitorAusentes)
                .writer(ausentesWriter)
                .build();
    }

    /**
     * Cursor: o banco entrega as linhas aos poucos ({@code fetchSize}), sem carregar tudo na memória.
     * {@code saveState(false)}: num restart, a consulta é refeita do zero (o NOT EXISTS já exclui o
     * que foi gravado). Pular as N primeiras, como faz o reader de arquivo, pularia linhas erradas.
     */
    @Bean
    @StepScope
    JdbcCursorItemReader<TransacaoAutorizada> leitorAusentes(DataSource dataSource,
                                                             @Value("#{jobParameters['idArquivo']}") String idArquivo,
                                                             @Value("#{jobParameters['dataReferencia']}") LocalDate dataReferencia) {
        return new JdbcCursorItemReaderBuilder<TransacaoAutorizada>()
                .name("leitorAusentes")
                .dataSource(dataSource)
                .sql(SQL_AUSENTES)
                .queryArguments(dataReferencia.atStartOfDay(), dataReferencia.plusDays(1).atStartOfDay(),
                        UUID.fromString(idArquivo))
                .rowMapper(RepositorioConciliacao::mapearAutorizacao)
                .fetchSize(1000)
                .saveState(false)
                .build();
    }

    @Bean
    @StepScope
    AusentesWriter ausentesWriter(RepositorioConciliacao repositorio, PublicadorConciliacao publicador,
                                  Conciliador conciliador,
                                  @Value("#{jobParameters['idArquivo']}") String idArquivo,
                                  @Value("#{jobParameters['dataReferencia']}") LocalDate dataReferencia) {
        return new AusentesWriter(repositorio, publicador, conciliador, UUID.fromString(idArquivo), dataReferencia);
    }
}
