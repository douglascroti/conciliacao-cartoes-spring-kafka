package br.estudo.conciliacao.gerador;

import java.time.LocalDate;

/**
 * Quantidade esperada de cada resultado, contada durante a geração. Depois do job, os números
 * de {@code resultado_conciliacao} e de {@code arquivo_recebido.linhas_invalidas} devem bater com estes.
 */
public record Gabarito(
        LocalDate dataReferencia,
        long semente,
        long linhasArquivo,
        long conciliadas,
        long divergentesValor,
        long divergentesParcelas,
        long divergentesData,
        long naoEncontradas,
        long invalidas,
        long ausentes,
        long autorizacoes) {

    public long divergentes() {
        return divergentesValor + divergentesParcelas + divergentesData;
    }

    /** JSON escrito à mão: o gerador não precisa de uma biblioteca só para isto. */
    public String json() {
        return """
                {
                  "dataReferencia": "%s",
                  "semente": %d,
                  "linhasArquivo": %d,
                  "autorizacoes": %d,
                  "esperado": {
                    "CONCILIADA": %d,
                    "DIVERGENTE": %d,
                    "NAO_ENCONTRADA": %d,
                    "AUSENTE_NO_ARQUIVO": %d,
                    "linhasInvalidas": %d
                  },
                  "divergentesPorCampo": { "VALOR": %d, "PARCELAS": %d, "DATA": %d }
                }
                """.formatted(dataReferencia, semente, linhasArquivo, autorizacoes, conciliadas, divergentes(),
                naoEncontradas, ausentes, invalidas, divergentesValor, divergentesParcelas, divergentesData);
    }
}
