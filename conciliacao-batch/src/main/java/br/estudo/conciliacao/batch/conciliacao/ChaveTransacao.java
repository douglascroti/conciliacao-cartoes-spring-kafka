package br.estudo.conciliacao.batch.conciliacao;

/**
 * Chave de negócio que liga a linha do arquivo à autorização. Como é um record, {@code equals} e
 * {@code hashCode} comparam os dois campos, e ela funciona direto como chave de {@code Map}
 * (no JS seria preciso montar uma string tipo {@code `${nsu}|${codigo}`}).
 */
public record ChaveTransacao(String nsu, String codigoAutorizacao) {
}
