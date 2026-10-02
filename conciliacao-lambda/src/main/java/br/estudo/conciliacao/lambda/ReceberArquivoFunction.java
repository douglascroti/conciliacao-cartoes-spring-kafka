package br.estudo.conciliacao.lambda;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;

import br.estudo.conciliacao.eventos.ArquivoRecebidoEvento;
import br.estudo.conciliacao.lambda.config.RecebimentoProperties;
import br.estudo.conciliacao.lambda.dominio.MotivoRejeicao;
import br.estudo.conciliacao.lambda.dominio.ValidadorArquivo;
import br.estudo.conciliacao.lambda.idempotencia.ArquivoRegistrado;
import br.estudo.conciliacao.lambda.idempotencia.RegistroIdempotencia;
import br.estudo.conciliacao.lambda.infra.ArmazenamentoArquivos;
import br.estudo.conciliacao.lambda.infra.PublicadorEventos;

/**
 * A "função" da Lambda: um {@code Consumer<S3Event>} comum do Java. O Spring Cloud Function
 * converte o JSON da invocação em {@link S3Event} e chama {@link #accept}; este código não
 * conhece nada da AWS Lambda em si (dá para testá-lo chamando {@code accept} direto).
 */
@Component("receberArquivo")
public class ReceberArquivoFunction implements Consumer<S3Event> {

    private static final Logger log = LoggerFactory.getLogger(ReceberArquivoFunction.class);

    private final ValidadorArquivo validador;
    private final ArmazenamentoArquivos armazenamento;
    private final RegistroIdempotencia registro;
    private final PublicadorEventos publicador;
    private final String prefixoEntrada;

    // Injeção pelo construtor: o Spring cria os beans e os passa aqui (sem "new" espalhado).
    // "registro" é a interface; a implementação (Postgres ou DynamoDB) vem de IdempotenciaConfig.
    public ReceberArquivoFunction(ValidadorArquivo validador, ArmazenamentoArquivos armazenamento,
                                  RegistroIdempotencia registro, PublicadorEventos publicador,
                                  RecebimentoProperties props) {
        this.validador = validador;
        this.armazenamento = armazenamento;
        this.registro = registro;
        this.publicador = publicador;
        this.prefixoEntrada = props.s3().prefixoEntrada();
    }

    @Override
    public void accept(S3Event evento) {
        // Payload fora do formato do S3 chega aqui sem registros; sem este aviso, passaria em silêncio.
        if (evento == null || evento.getRecords() == null || evento.getRecords().isEmpty()) {
            log.warn("Evento sem registros S3 recebido; nada a processar");
            return;
        }
        evento.getRecords().forEach(this::processar);
    }

    private void processar(S3EventNotificationRecord registroS3) {
        String bucket = registroS3.getS3().getBucket().getName();
        // A chave chega URL-encoded no evento (espaço vira "+").
        String chave = registroS3.getS3().getObject().getUrlDecodedKey();
        String etag = registroS3.getS3().getObject().geteTag();
        long tamanho = registroS3.getS3().getObject().getSizeAsLong();

        if (!chave.startsWith(prefixoEntrada)) {
            log.info("Ignorado: s3://{}/{} está fora do prefixo {}", bucket, chave, prefixoEntrada);
            return;
        }
        String nomeArquivo = chave.substring(chave.lastIndexOf('/') + 1);

        Optional<LocalDate> dataReferencia = validador.extrairDataReferencia(nomeArquivo);
        if (dataReferencia.isEmpty()) {
            rejeitar(bucket, chave, nomeArquivo, MotivoRejeicao.NOME_INVALIDO);
            return;
        }
        if (tamanho == 0) {
            rejeitar(bucket, chave, nomeArquivo, MotivoRejeicao.ARQUIVO_VAZIO);
            return;
        }
        if (!validador.cabecalhoValido(armazenamento.lerPrimeiraLinha(bucket, chave))) {
            rejeitar(bucket, chave, nomeArquivo, MotivoRejeicao.CABECALHO_INVALIDO);
            return;
        }

        Optional<ArquivoRegistrado> registrado =
                registro.registrar(bucket, chave, nomeArquivo, etag, tamanho, dataReferencia.get());
        if (registrado.isEmpty()) {
            log.warn("Duplicado: {} (etag {}) já foi recebido; nenhum evento publicado", nomeArquivo, etag);
            return;
        }

        ArquivoRegistrado arquivo = registrado.get();
        var eventoKafka = new ArquivoRecebidoEvento(arquivo.id(), bucket, chave, nomeArquivo, etag,
                tamanho, dataReferencia.get(), arquivo.recebidoEm());
        try {
            var metadata = publicador.publicar(eventoKafka);
            log.info("Publicado: {} (id {}, {} bytes) em {}-{}@{}", nomeArquivo, arquivo.id(), tamanho,
                    metadata.topic(), metadata.partition(), metadata.offset());
        } catch (RuntimeException e) {
            // Sem o evento, o registro impediria a retentativa de processar o arquivo: desfaz.
            registro.remover(arquivo);
            throw e;
        }
    }

    private void rejeitar(String bucket, String chave, String nomeArquivo, MotivoRejeicao motivo) {
        String destino = armazenamento.moverParaRejeitados(bucket, chave, nomeArquivo, motivo);
        log.warn("Rejeitado: {} ({}), movido para s3://{}/{}", nomeArquivo, motivo, bucket, destino);
    }
}
