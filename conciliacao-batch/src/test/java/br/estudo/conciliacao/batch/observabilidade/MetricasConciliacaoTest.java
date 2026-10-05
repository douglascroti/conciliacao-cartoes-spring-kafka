package br.estudo.conciliacao.batch.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.eventos.StatusConciliacao;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricasConciliacaoTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricasConciliacao metricas = new MetricasConciliacao(registry);

    @AfterEach
    void limparTransacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void contadoresExistemComZeroAntesDoPrimeiroArquivo() {
        for (StatusConciliacao status : StatusConciliacao.values()) {
            assertThat(resultados(status)).isZero();
        }
        assertThat(registry.get("conciliacao.linhas.invalidas").counter().count()).isZero();
        assertThat(arquivos("CONCLUIDO")).isZero();
        assertThat(arquivos("FALHA")).isZero();
    }

    @Test
    void foraDeTransacaoContaNaHora() {
        metricas.registrarResultados(List.of(resultado(StatusConciliacao.CONCILIADA),
                resultado(StatusConciliacao.CONCILIADA), resultado(StatusConciliacao.DIVERGENTE)));
        metricas.registrarArquivo(false);

        assertThat(resultados(StatusConciliacao.CONCILIADA)).isEqualTo(2);
        assertThat(resultados(StatusConciliacao.DIVERGENTE)).isEqualTo(1);
        assertThat(arquivos("FALHA")).isEqualTo(1);
    }

    @Test
    void dentroDeTransacaoSoContaDepoisDoCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metricas.registrarResultados(List.of(resultado(StatusConciliacao.NAO_ENCONTRADA)));
        metricas.registrarLinhaInvalida();
        assertThat(resultados(StatusConciliacao.NAO_ENCONTRADA)).isZero();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(resultados(StatusConciliacao.NAO_ENCONTRADA)).isEqualTo(1);
        assertThat(registry.get("conciliacao.linhas.invalidas").counter().count()).isEqualTo(1);
    }

    @Test
    void rollbackNaoConta() {
        TransactionSynchronizationManager.initSynchronization();

        metricas.registrarResultados(List.of(resultado(StatusConciliacao.CONCILIADA)));
        // No rollback o Spring chama afterCompletion(ROLLED_BACK), nunca afterCommit.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(resultados(StatusConciliacao.CONCILIADA)).isZero();
    }

    private double resultados(StatusConciliacao status) {
        return registry.get("conciliacao.resultados").tag("status", status.name()).counter().count();
    }

    private double arquivos(String status) {
        return registry.get("conciliacao.arquivos").tag("status", status).counter().count();
    }

    private static ResultadoConciliacao resultado(StatusConciliacao status) {
        return new ResultadoConciliacao(1, "000000001", "A1B2C3", status, Set.of(), null, null, null);
    }
}
