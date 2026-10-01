package br.estudo.conciliacao.lambda.infra;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.stereotype.Component;

import br.estudo.conciliacao.eventos.ArquivoRecebidoEvento;
import br.estudo.conciliacao.lambda.config.RecebimentoProperties;
import tools.jackson.databind.json.JsonMapper;

/** Publica o {@link ArquivoRecebidoEvento} no Kafka. */
@Component
public class PublicadorEventos {

    private static final long TIMEOUT_ENVIO_SEGUNDOS = 15;

    private final KafkaProducer<String, String> produtor;
    private final String topico;
    private final JsonMapper json;

    public PublicadorEventos(KafkaProducer<String, String> produtor, JsonMapper json, RecebimentoProperties props) {
        this.produtor = produtor;
        this.json = json;
        this.topico = props.kafka().topico();
    }

    /**
     * Envia e ESPERA a confirmação do broker. O {@code send()} do Kafka é assíncrono (devolve um
     * Future, parecido com uma Promise); quando o handler da Lambda retorna, a AWS congela o
     * container, e um envio pendente poderia nunca sair. É o equivalente a não esquecer o
     * {@code await} antes do {@code return} no Node.
     */
    public RecordMetadata publicar(ArquivoRecebidoEvento evento) {
        // A chave da mensagem é o id do arquivo: eventos do mesmo arquivo caem na mesma partição.
        var registro = new ProducerRecord<>(topico, evento.idArquivo().toString(), json.writeValueAsString(evento));
        try {
            return produtor.send(registro).get(TIMEOUT_ENVIO_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio ao Kafka interrompido", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Falha ao publicar evento do arquivo " + evento.nomeArquivo(), e);
        }
    }
}
