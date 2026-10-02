package br.estudo.conciliacao.batch.publicacao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.eventos.ErroLinhaEvento;
import br.estudo.conciliacao.eventos.ResultadoConciliacaoEvento;
import br.estudo.conciliacao.eventos.Topicos;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica os eventos do job: resultados em {@code conciliacao.resultado} e linhas inválidas em
 * {@code conciliacao.erro}.
 *
 * <p>Chave dos resultados: o NSU. Ela espalha o milhão de resultados de um arquivo grande pelas
 * partições, e os consumidores (agenda de recebíveis, relatórios, antifraude) escalam com elas. Com
 * a chave {@code idArquivo}, o arquivo inteiro caía numa partição só. A ordem entre transações
 * diferentes não importa; as mensagens de uma mesma transação continuam na mesma partição. Os
 * erros, poucos e sem NSU confiável (a linha pode estar quebrada), usam o id do arquivo.
 *
 * <p>Envia todas as mensagens de uma vez (o producer agrupa em poucas requisições) e só então
 * espera todas as confirmações, como um {@code await Promise.all(envios)} no Node. Se alguma
 * falhar, a exceção desfaz a transação do chunk: nada fica gravado sem ter sido publicado.
 */
@Component
public class PublicadorConciliacao {

    private static final long TIMEOUT_SEGUNDOS = 30;

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;

    public PublicadorConciliacao(KafkaTemplate<String, String> kafka, JsonMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publicarResultados(UUID idArquivo, LocalDate dataReferencia, List<ResultadoConciliacao> resultados) {
        Instant processadoEm = Instant.now();
        List<ResultadoConciliacaoEvento> eventos = resultados.stream()
                .map(r -> new ResultadoConciliacaoEvento(idArquivo, dataReferencia, r.numeroLinha(), r.nsu(),
                        r.codigoAutorizacao(), r.status(), List.copyOf(r.camposDivergentes()), r.valorArquivo(),
                        r.valorAutorizado(), processadoEm))
                .toList();
        enviarEEsperar(Topicos.RESULTADO, idArquivo, eventos, ResultadoConciliacaoEvento::nsu);
    }

    public void publicarErro(ErroLinhaEvento erro) {
        enviarEEsperar(Topicos.ERRO, erro.idArquivo(), List.of(erro), e -> e.idArquivo().toString());
    }

    private <T> void enviarEEsperar(String topico, UUID idArquivo, List<T> eventos, Function<T, String> chave) {
        CompletableFuture<?>[] envios = eventos.stream()
                .map(evento -> kafka.send(topico, chave.apply(evento), json.writeValueAsString(evento)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(envios).get(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicação em " + topico + " interrompida", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Falha ao publicar em " + topico + " (arquivo " + idArquivo + ")", e);
        }
    }
}
