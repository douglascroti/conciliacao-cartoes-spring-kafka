package br.estudo.conciliacao.eventos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Evento publicado em {@link Topicos#ARQUIVO_RECEBIDO} quando um arquivo de conciliação
 * válido e inédito chega ao S3.
 *
 * <p>É um {@code record}: classe imutável cujo construtor, getters ({@code evento.bucket()}),
 * {@code equals}, {@code hashCode} e {@code toString} são gerados pelo compilador.
 *
 * @param idArquivo      identificador do arquivo (gerado no registro de idempotência); chave da mensagem Kafka
 * @param bucket         bucket S3 onde o arquivo está
 * @param chave          chave (caminho) do objeto no bucket, ex.: {@code entrada/conciliacao_20261001.csv}
 * @param nomeArquivo    nome do arquivo sem o prefixo
 * @param etag           ETag do objeto no S3 (hash do conteúdo)
 * @param tamanhoBytes   tamanho do arquivo
 * @param dataReferencia data de movimento, extraída do nome do arquivo
 * @param recebidoEm     instante em que o recebimento foi registrado
 */
public record ArquivoRecebidoEvento(
        UUID idArquivo,
        String bucket,
        String chave,
        String nomeArquivo,
        String etag,
        long tamanhoBytes,
        LocalDate dataReferencia,
        Instant recebidoEm) {
}
