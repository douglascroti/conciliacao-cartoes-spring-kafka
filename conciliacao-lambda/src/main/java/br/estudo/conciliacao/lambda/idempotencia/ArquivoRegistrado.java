package br.estudo.conciliacao.lambda.idempotencia;

import java.time.Instant;
import java.util.UUID;

/** Resultado de um registro bem-sucedido; nome e ETag permitem desfazê-lo em qualquer provedor. */
public record ArquivoRegistrado(UUID id, String nomeArquivo, String etag, Instant recebidoEm) {
}
