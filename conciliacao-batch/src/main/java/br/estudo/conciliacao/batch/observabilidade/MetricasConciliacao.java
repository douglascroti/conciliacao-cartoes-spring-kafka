package br.estudo.conciliacao.batch.observabilidade;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.eventos.StatusConciliacao;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas de negócio da conciliação, expostas em {@code /actuator/prometheus}.
 *
 * <p>As técnicas (JVM, pool de conexões, Kafka, duração de job e step) já vêm do Spring Boot e do
 * Spring Batch; aqui ficam só as que respondem perguntas do negócio: quanto foi conciliado, quanto
 * divergiu, quantas linhas vieram inválidas, quantos arquivos falharam.
 *
 * <p>Contadores só crescem; taxa e totais por período saem da consulta no Prometheus
 * ({@code rate(...)}, {@code increase(...)}). No Prometheus os nomes ganham o sufixo
 * {@code _total} (ex.: {@code conciliacao_resultados_total}).
 *
 * <p>Chamadas dentro de uma transação (writer e skip listener rodam na transação do chunk) só
 * contam depois do commit: num rollback o chunk é reprocessado, e contar antes contaria duas vezes.
 */
@Component
public class MetricasConciliacao {

    private final Map<StatusConciliacao, Counter> resultados = new EnumMap<>(StatusConciliacao.class);
    private final Counter linhasInvalidas;
    private final Counter arquivosConcluidos;
    private final Counter arquivosComFalha;

    public MetricasConciliacao(MeterRegistry registry) {
        // Contadores criados de antemão: a série aparece com 0 desde a subida, o que deixa
        // gráficos e alertas consistentes mesmo antes do primeiro arquivo.
        for (StatusConciliacao status : StatusConciliacao.values()) {
            resultados.put(status, Counter.builder("conciliacao.resultados")
                    .description("Transações conciliadas, por status do resultado")
                    .tag("status", status.name())
                    .register(registry));
        }
        linhasInvalidas = Counter.builder("conciliacao.linhas.invalidas")
                .description("Linhas do arquivo puladas por estarem fora do layout ou das regras")
                .register(registry);
        arquivosConcluidos = contadorArquivos(registry, "CONCLUIDO");
        arquivosComFalha = contadorArquivos(registry, "FALHA");
    }

    private static Counter contadorArquivos(MeterRegistry registry, String status) {
        return Counter.builder("conciliacao.arquivos")
                .description("Arquivos processados, por status final do job")
                .tag("status", status)
                .register(registry);
    }

    public void registrarResultados(Collection<ResultadoConciliacao> lote) {
        Map<StatusConciliacao, Integer> porStatus = new EnumMap<>(StatusConciliacao.class);
        lote.forEach(r -> porStatus.merge(r.status(), 1, Integer::sum));
        aposCommit(() -> porStatus.forEach((status, qtd) -> resultados.get(status).increment(qtd)));
    }

    public void registrarLinhaInvalida() {
        aposCommit(linhasInvalidas::increment);
    }

    public void registrarArquivo(boolean concluido) {
        aposCommit(concluido ? arquivosConcluidos::increment : arquivosComFalha::increment);
    }

    private static void aposCommit(Runnable acao) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    acao.run();
                }
            });
        } else {
            acao.run();
        }
    }
}
