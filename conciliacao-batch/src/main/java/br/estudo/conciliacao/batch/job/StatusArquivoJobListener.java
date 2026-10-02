package br.estudo.conciliacao.batch.job;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.dao.DuplicateKeyException;

import br.estudo.conciliacao.batch.persistencia.RepositorioArquivo;

/** Mantém {@code arquivo_recebido.status} em dia: PROCESSANDO no início, CONCLUIDO ou FALHA no fim. */
public class StatusArquivoJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(StatusArquivoJobListener.class);

    private static final int TAMANHO_MAXIMO_MENSAGEM = 1000;

    private final RepositorioArquivo repositorio;

    public StatusArquivoJobListener(RepositorioArquivo repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public void beforeJob(JobExecution execucao) {
        JobParameters p = execucao.getJobParameters();
        try {
            repositorio.iniciarProcessamento(UUID.fromString(p.getString("idArquivo")), p.getString("bucket"),
                    p.getString("chave"), p.getString("nomeArquivo"), p.getString("etag"), p.getLong("tamanhoBytes"),
                    p.getLocalDate("dataReferencia"), Instant.parse(p.getString("recebidoEm")));
        } catch (DuplicateKeyException e) {
            // Mesmo nome + ETag já registrado com outro id (ex.: trocou o provedor de idempotência
            // e o DynamoDB, zerado, aceitou o arquivo de novo). O status não impede a conciliação.
            log.warn("Arquivo {} já registrado com outro id; status não acompanhado", p.getString("nomeArquivo"));
        }
    }

    @Override
    public void afterJob(JobExecution execucao) {
        boolean concluido = execucao.getStatus() == BatchStatus.COMPLETED;
        String mensagem = concluido ? null : execucao.getAllFailureExceptions().stream()
                .map(DescricaoErro::descrever)
                .collect(Collectors.joining(" | "));
        if (mensagem != null && mensagem.length() > TAMANHO_MAXIMO_MENSAGEM) {
            mensagem = mensagem.substring(0, TAMANHO_MAXIMO_MENSAGEM);
        }
        repositorio.finalizarProcessamento(UUID.fromString(execucao.getJobParameters().getString("idArquivo")),
                execucao.getJobInstance().getInstanceId(), concluido ? "CONCLUIDO" : "FALHA", mensagem);
    }
}
