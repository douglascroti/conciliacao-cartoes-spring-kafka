package br.estudo.conciliacao.batch.job;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;

import br.estudo.conciliacao.batch.conciliacao.ChaveTransacao;
import br.estudo.conciliacao.batch.conciliacao.Conciliador;
import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.batch.conciliacao.TransacaoAutorizada;
import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.batch.observabilidade.MetricasConciliacao;
import br.estudo.conciliacao.batch.persistencia.RepositorioConciliacao;
import br.estudo.conciliacao.batch.publicacao.PublicadorConciliacao;

/**
 * Concilia um chunk inteiro de uma vez.
 *
 * <p>Por que no writer e não num {@code ItemProcessor}: o processor recebe um item por vez, e
 * buscar a autorização ali seria uma consulta por linha (1 milhão de consultas num arquivo
 * grande). O writer recebe o lote todo, então faz uma consulta só para as N linhas.
 *
 * <p>Tudo roda dentro da transação do chunk: busca, gravação, publicação e, no commit, a posição
 * de leitura no JobRepository. Uma falha em qualquer passo desfaz o lote inteiro.
 */
public class ConciliacaoLinhasWriter implements ItemWriter<LinhaArquivo> {

    private final RepositorioConciliacao repositorio;
    private final PublicadorConciliacao publicador;
    private final Conciliador conciliador;
    private final MetricasConciliacao metricas;
    private final UUID idArquivo;
    private final LocalDate dataReferencia;

    public ConciliacaoLinhasWriter(RepositorioConciliacao repositorio, PublicadorConciliacao publicador,
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
    public void write(Chunk<? extends LinhaArquivo> chunk) {
        List<LinhaArquivo> linhas = List.copyOf(chunk.getItems());
        Set<String> nsus = linhas.stream().map(LinhaArquivo::nsu).collect(Collectors.toSet());

        Map<ChaveTransacao, TransacaoAutorizada> autorizacoes = repositorio.buscarAutorizacoes(nsus);
        List<ResultadoConciliacao> resultados = conciliador.conciliar(linhas, autorizacoes);

        repositorio.inserirResultados(idArquivo, resultados);
        publicador.publicarResultados(idArquivo, dataReferencia, resultados);
        metricas.registrarResultados(resultados);
    }
}
