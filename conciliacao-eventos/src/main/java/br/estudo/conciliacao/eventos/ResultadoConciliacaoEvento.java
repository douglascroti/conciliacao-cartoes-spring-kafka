package br.estudo.conciliacao.eventos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Evento publicado em {@link Topicos#RESULTADO} para cada transação conciliada. Sem PAN: a
 * transação é identificada por NSU + código de autorização.
 *
 * <p>A entrega é "pelo menos uma vez": num restart do job, um lote pode ser publicado de novo.
 * Consumidores devem tratar {@code (idArquivo, nsu, codigoAutorizacao)} como chave de idempotência.
 *
 * @param idArquivo          arquivo de origem; chave da mensagem Kafka (resultados do mesmo arquivo
 *                           ficam na mesma partição, em ordem)
 * @param numeroLinha        linha no arquivo; nulo em {@code AUSENTE_NO_ARQUIVO}
 * @param camposDivergentes  vazio, exceto em {@code DIVERGENTE}
 * @param valorArquivo       nulo em {@code AUSENTE_NO_ARQUIVO}
 * @param valorAutorizado    nulo em {@code NAO_ENCONTRADA}
 */
public record ResultadoConciliacaoEvento(
        UUID idArquivo,
        LocalDate dataReferencia,
        Integer numeroLinha,
        String nsu,
        String codigoAutorizacao,
        StatusConciliacao status,
        List<CampoDivergente> camposDivergentes,
        BigDecimal valorArquivo,
        BigDecimal valorAutorizado,
        Instant processadoEm) {
}
