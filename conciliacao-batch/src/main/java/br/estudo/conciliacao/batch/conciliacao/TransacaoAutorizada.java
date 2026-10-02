package br.estudo.conciliacao.batch.conciliacao;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Autorização do emissor, com os campos usados na conciliação (sem o PAN). */
public record TransacaoAutorizada(
        long id,
        String nsu,
        String codigoAutorizacao,
        LocalDateTime dataTransacao,
        BigDecimal valor,
        int parcelas) {

    public ChaveTransacao chave() {
        return new ChaveTransacao(nsu, codigoAutorizacao);
    }
}
