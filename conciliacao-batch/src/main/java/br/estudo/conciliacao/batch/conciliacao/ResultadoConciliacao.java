package br.estudo.conciliacao.batch.conciliacao;

import java.math.BigDecimal;
import java.util.Set;

import br.estudo.conciliacao.eventos.CampoDivergente;
import br.estudo.conciliacao.eventos.StatusConciliacao;

/**
 * Resultado de uma transação, antes de ser gravado e publicado.
 *
 * @param numeroLinha           nulo em {@code AUSENTE_NO_ARQUIVO}
 * @param idTransacaoAutorizada nulo em {@code NAO_ENCONTRADA}
 */
public record ResultadoConciliacao(
        Integer numeroLinha,
        String nsu,
        String codigoAutorizacao,
        StatusConciliacao status,
        Set<CampoDivergente> camposDivergentes,
        BigDecimal valorArquivo,
        BigDecimal valorAutorizado,
        Long idTransacaoAutorizada) {
}
