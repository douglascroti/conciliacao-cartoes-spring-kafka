package br.estudo.conciliacao.batch.conciliacao;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.eventos.CampoDivergente;
import br.estudo.conciliacao.eventos.StatusConciliacao;

/**
 * Regras de conciliação, sem banco nem Spring: recebe as linhas e as autorizações já buscadas e
 * devolve a classificação. Fica fácil de testar com entradas montadas à mão.
 */
public class Conciliador {

    /** Classifica um lote de linhas, dadas as autorizações encontradas para elas. */
    public List<ResultadoConciliacao> conciliar(List<LinhaArquivo> linhas,
                                                Map<ChaveTransacao, TransacaoAutorizada> autorizacoes) {
        return linhas.stream()
                .map(linha -> conciliar(linha, autorizacoes.get(new ChaveTransacao(linha.nsu(), linha.codigoAutorizacao()))))
                .toList();
    }

    ResultadoConciliacao conciliar(LinhaArquivo linha, TransacaoAutorizada autorizacao) {
        if (autorizacao == null) {
            return new ResultadoConciliacao(linha.numeroLinha(), linha.nsu(), linha.codigoAutorizacao(),
                    StatusConciliacao.NAO_ENCONTRADA, Set.of(), linha.valor(), null, null);
        }
        Set<CampoDivergente> divergentes = EnumSet.noneOf(CampoDivergente.class);
        // compareTo, não equals: no BigDecimal, equals considera a escala e 150.9 != 150.90.
        if (linha.valor().compareTo(autorizacao.valor()) != 0) {
            divergentes.add(CampoDivergente.VALOR);
        }
        if (linha.parcelas() != autorizacao.parcelas()) {
            divergentes.add(CampoDivergente.PARCELAS);
        }
        // Mesmo dia basta: relógios de adquirente e emissor costumam diferir em segundos.
        if (!linha.dataTransacao().toLocalDate().equals(autorizacao.dataTransacao().toLocalDate())) {
            divergentes.add(CampoDivergente.DATA);
        }
        StatusConciliacao status = divergentes.isEmpty() ? StatusConciliacao.CONCILIADA : StatusConciliacao.DIVERGENTE;
        return new ResultadoConciliacao(linha.numeroLinha(), linha.nsu(), linha.codigoAutorizacao(),
                status, divergentes, linha.valor(), autorizacao.valor(), autorizacao.id());
    }

    /** Autorização do dia que não apareceu no arquivo. */
    public ResultadoConciliacao ausente(TransacaoAutorizada autorizacao) {
        return new ResultadoConciliacao(null, autorizacao.nsu(), autorizacao.codigoAutorizacao(),
                StatusConciliacao.AUSENTE_NO_ARQUIVO, Set.of(), null, autorizacao.valor(), autorizacao.id());
    }
}
