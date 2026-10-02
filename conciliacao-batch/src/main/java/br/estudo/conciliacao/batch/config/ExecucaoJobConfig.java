package br.estudo.conciliacao.batch.config;

import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Faz o JobOperator rodar os jobs em threads próprias, sem prender o consumer do Kafka.
 *
 * <p>Sem isso, {@code jobOperator.start} só retornaria ao fim do job. Um arquivo de 1 milhão de
 * linhas levaria minutos, o consumer passaria do {@code max.poll.interval.ms} (5 min), o Kafka o
 * consideraria morto e entregaria o mesmo evento a outro consumidor. Com o executor, o
 * {@code start} grava a execução no JobRepository e retorna; o offset é confirmado com o job já
 * registrado no banco, e é o banco que garante que nada se perde (restart na 3c).
 */
@Configuration(proxyBeanMethods = false)
public class ExecucaoJobConfig {

    // @BatchTaskExecutor: indica à autoconfiguração do Spring Batch que é este o executor dos jobs.
    @Bean
    @BatchTaskExecutor
    TaskExecutor executorJobs(ConciliacaoProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("job-");
        // Limite de arquivos em paralelo; os demais esperam na fila.
        executor.setCorePoolSize(props.job().execucoesSimultaneas());
        executor.setMaxPoolSize(props.job().execucoesSimultaneas());
        executor.setQueueCapacity(100);
        // No desligamento, espera os jobs em andamento terminarem (até 30 s) em vez de matá-los.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
