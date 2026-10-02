package br.estudo.conciliacao.batch.operacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import br.estudo.conciliacao.batch.config.JobConciliacaoConfig;
import br.estudo.conciliacao.batch.consumo.ArquivoRecebidoListener;

/**
 * Na subida do serviço, retoma os jobs interrompidos por uma queda e só então liga o consumer.
 *
 * <p>Se o processo morre no meio de um job (kill, falta de memória, deploy), a execução fica
 * {@code STARTED} no JobRepository para sempre: ninguém a marcou como falha, e o evento do Kafka
 * já foi confirmado. Aqui ela é marcada como falha ({@code recover}) e reiniciada
 * ({@code restart}), continuando do último chunk confirmado.
 *
 * <p>Premissa: <b>uma instância</b> do serviço. Com várias, uma execução "rodando" pode ser de
 * outra instância viva; seria preciso um controle de posse (lease/heartbeat) antes de recuperar.
 */
@Component
public class RecuperacaoExecucoes {

    private static final Logger log = LoggerFactory.getLogger(RecuperacaoExecucoes.class);

    private final JobRepository jobRepository;
    private final JobOperator jobOperator;
    private final KafkaListenerEndpointRegistry listeners;

    public RecuperacaoExecucoes(JobRepository jobRepository, JobOperator jobOperator,
                                KafkaListenerEndpointRegistry listeners) {
        this.jobRepository = jobRepository;
        this.jobOperator = jobOperator;
        this.listeners = listeners;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recuperarEIniciarConsumo() {
        for (JobExecution interrompida : jobRepository.findRunningJobExecutions(JobConciliacaoConfig.NOME_JOB)) {
            try {
                JobExecution recuperada = jobOperator.recover(interrompida);
                JobExecution nova = jobOperator.restart(recuperada);
                log.warn("Execução {} do arquivo {} estava interrompida; reiniciada como execução {}",
                        interrompida.getId(), interrompida.getJobParameters().getString("nomeArquivo"), nova.getId());
            } catch (Exception e) {
                log.error("Falha ao recuperar a execução {}", interrompida.getId(), e);
            }
        }
        listeners.getListenerContainer(ArquivoRecebidoListener.ID_LISTENER).start();
        log.info("Consumo de {} iniciado", ArquivoRecebidoListener.ID_LISTENER);
    }
}
