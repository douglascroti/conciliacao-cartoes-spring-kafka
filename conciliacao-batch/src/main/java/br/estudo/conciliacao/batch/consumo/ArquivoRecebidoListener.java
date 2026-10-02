package br.estudo.conciliacao.batch.consumo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import br.estudo.conciliacao.eventos.ArquivoRecebidoEvento;
import br.estudo.conciliacao.eventos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consome {@code conciliacao.arquivo-recebido} e lança o job do arquivo.
 *
 * <p>{@code @KafkaListener} é o equivalente ao {@code consumer.run({ eachMessage })} do kafkajs:
 * o Spring Kafka cuida do poll, do commit do offset (após o método retornar sem exceção) e das
 * retentativas (ver {@code KafkaConfig}).
 */
@Component
public class ArquivoRecebidoListener {

    private static final Logger log = LoggerFactory.getLogger(ArquivoRecebidoListener.class);

    private final JobOperator jobOperator;
    private final Job job;
    private final JsonMapper json;

    public ArquivoRecebidoListener(JobOperator jobOperator, Job job, JsonMapper json) {
        this.jobOperator = jobOperator;
        this.job = job;
        this.json = json;
    }

    @KafkaListener(topics = Topicos.ARQUIVO_RECEBIDO)
    public void receber(String mensagem) throws Exception {
        ArquivoRecebidoEvento evento = json.readValue(mensagem, ArquivoRecebidoEvento.class);
        try {
            JobExecution execucao = jobOperator.start(job, parametros(evento));
            log.info("Evento recebido: {} (id {}) → execução {} agendada", evento.nomeArquivo(),
                    evento.idArquivo(), execucao.getId());
        } catch (JobInstanceAlreadyCompleteException e) {
            // Mesmo idArquivo = mesma JobInstance. Já concluída: evento duplicado, nada a fazer.
            log.warn("Evento duplicado: arquivo {} (id {}) já foi processado", evento.nomeArquivo(), evento.idArquivo());
        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("Evento duplicado: arquivo {} (id {}) está em processamento", evento.nomeArquivo(), evento.idArquivo());
        }
    }

    /**
     * Só o {@code idArquivo} é "identificador": ele define a JobInstance (um arquivo = um job).
     * Os demais parâmetros são informativos; se fossem identificadores, qualquer diferença
     * criaria outra instância e quebraria a proteção contra duplicidade.
     */
    private static JobParameters parametros(ArquivoRecebidoEvento evento) {
        return new JobParametersBuilder()
                .addString("idArquivo", evento.idArquivo().toString(), true)
                .addString("bucket", evento.bucket(), false)
                .addString("chave", evento.chave(), false)
                .addString("nomeArquivo", evento.nomeArquivo(), false)
                .addLocalDate("dataReferencia", evento.dataReferencia(), false)
                .toJobParameters();
    }
}
