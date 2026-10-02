package br.estudo.conciliacao.batch.publicacao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.eventos.ResultadoConciliacaoEvento;
import br.estudo.conciliacao.eventos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica os resultados de um lote em {@code conciliacao.resultado}.
 *
 * <p>Envia todas as mensagens do lote de uma vez (o producer agrupa em poucas requisições) e só
 * então espera todas as confirmações, como um {@code await Promise.all(envios)} no Node. Se alguma
 * falhar, a exceção desfaz a transação do chunk: nada fica gravado sem ter sido publicado.
 */
@Component
public class PublicadorResultados {

    private static final long TIMEOUT_SEGUNDOS = 30;

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public PublicadorResultados(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publicar(UUID idArquivo, LocalDate dataReferencia, List<ResultadoConciliacao> resultados) {
        Instant processadoEm = Instant.now();
        String chave = idArquivo.toString();
        CompletableFuture<?>[] envios = resultados.stream()
                .map(r -> new ResultadoConciliacaoEvento(idArquivo, dataReferencia, r.numeroLinha(), r.nsu(),
                        r.codigoAutorizacao(), r.status(), List.copyOf(r.camposDivergentes()), r.valorArquivo(),
                        r.valorAutorizado(), processadoEm))
                .map(evento -> kafka.send(Topicos.RESULTADO, chave, json.writeValueAsString(evento)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(envios).get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação dos resultados interrompida", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Falha ao publicar resultados do arquivo " + idArquivo, e);
        }
    }
}
