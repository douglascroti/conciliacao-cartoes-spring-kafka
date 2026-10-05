package br.estudo.conciliacao.batch.job;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import br.estudo.conciliacao.batch.conciliacao.Conciliador;
import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.batch.conciliacao.TransacaoAutorizada;
import br.estudo.conciliacao.batch.observabilidade.MetricasConciliacao;
import br.estudo.conciliacao.batch.persistencia.RepositorioConciliacao;
import br.estudo.conciliacao.batch.publicacao.PublicadorConciliacao;

/** Grava e publica como {@code AUSENTE_NO_ARQUIVO} as autorizações do dia que não vieram no arquivo. */
public class AusentesWriter implements ItemWriter<TransacaoAutorizada> {

    private final RepositorioConciliacao repositorio;
    private final PublicadorConciliacao publicador;
    private final Conciliador conciliador;
    private final MetricasConciliacao metricas;
    private final UUID idArquivo;
    private final LocalDate dataReferencia;

    public AusentesWriter(RepositorioConciliacao repositorio, PublicadorConciliacao publicador,
                          Conciliador conciliador, MetricasConciliacao metricas,
                          UUID idArquivo, LocalDate dataReferencia) {
        this.repositorio = repositorio;
        this.publicador = publicador;
        this.conciliador = conciliador;
        this.metricas = metricas;
        this.idArquivo = idArquivo;
        this.dataReferencia = dataReferencia;
    }

    @Override
    public void write(Chunk<? extends TransacaoAutorizada> chunk) {
        List<ResultadoConciliacao> resultados = chunk.getItems().stream().map(conciliador::ausente).toList();
        repositorio.inserirResultados(idArquivo, resultados);
        publicador.publicarResultados(idArquivo, dataReferencia, resultados);
        metricas.registrarResultados(resultados);
    }
}
