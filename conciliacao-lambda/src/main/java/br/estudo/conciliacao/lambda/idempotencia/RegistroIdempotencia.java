package br.estudo.conciliacao.lambda.idempotencia;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Garante que o mesmo arquivo (nome + ETag) só gere um evento. A Lambda depende só desta
 * interface; qual implementação existe no contexto é decidido por configuração
 * ({@code conciliacao.idempotencia.provedor}, ver {@code IdempotenciaConfig}).
 *
 * <p>No Node seria um módulo com a mesma "forma" ({@code registrar}/{@code remover}) e duas
 * implementações, escolhidas por um {@code if} no {@code process.env} ao montar as dependências.
 */
public interface RegistroIdempotencia {

    /**
     * Registra o arquivo de forma atômica (verificar e gravar numa só operação).
     *
     * @return o registro criado, ou vazio se o arquivo já tinha sido recebido (duplicado)
     */
    Optional<ArquivoRegistrado> registrar(String bucket, String chave, String nomeArquivo,
                                          String etag, long tamanhoBytes, LocalDate dataReferencia);

    /** Desfaz o registro, para que uma nova tentativa da Lambda possa processar o arquivo. */
    void remover(ArquivoRegistrado arquivo);
}
