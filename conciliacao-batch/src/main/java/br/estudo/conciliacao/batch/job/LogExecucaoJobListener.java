package br.estudo.conciliacao.batch.job;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Uma linha de log no início e outra no fim de cada execução, com status, contadores e duração:
 * o mínimo para acompanhar os jobs pelo log.
 */
public class LogExecucaoJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(LogExecucaoJobListener.class);

    @Override
    public void beforeJob(JobExecution execucao) {
        log.info("Job iniciado: execução {} do arquivo {} ({})", execucao.getId(),
                execucao.getJobParameters().getString("nomeArquivo"),
                execucao.getJobParameters().getString("idArquivo"));
    }

    @Override
    public void afterJob(JobExecution execucao) {
        long lidas = execucao.getStepExecutions().stream().mapToLong(s -> s.getReadCount()).sum();
        long gravadas = execucao.getStepExecutions().stream().mapToLong(s -> s.getWriteCount()).sum();
        Duration duracao = execucao.getStartTime() != null && execucao.getEndTime() != null
                ? Duration.between(execucao.getStartTime(), execucao.getEndTime())
                : Duration.ZERO;
        log.info("Job finalizado: execução {} do arquivo {} com status {} ({} lidas, {} gravadas, {} ms)",
                execucao.getId(), execucao.getJobParameters().getString("nomeArquivo"),
                execucao.getStatus(), lidas, gravadas, duracao.toMillis());
        execucao.getAllFailureExceptions()
                .forEach(e -> log.error("Falha na execução {}: {}", execucao.getId(), e.toString()));
    }
}
