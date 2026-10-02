package br.estudo.conciliacao.batch.persistencia;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Status de processamento do arquivo em {@code arquivo_recebido}. Roda fora da transação dos
 * chunks (no início e no fim do job), então cada atualização é confirmada na hora.
 */
@Repository
public class RepositorioArquivo {

    /*
     * Upsert por id: com Postgres a Lambda já criou a linha (RECEBIDO) e aqui só muda o status;
     * com DynamoDB a linha não existe e é criada agora. Num restart, FALHA volta a PROCESSANDO.
     */
    private static final String SQL_INICIAR = """
            INSERT INTO arquivo_recebido (id, bucket, chave, nome_arquivo, etag, tamanho_bytes, data_referencia,
                                          recebido_em, status, iniciado_em)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSANDO', now())
            ON CONFLICT (id) DO UPDATE
               SET status = 'PROCESSANDO', iniciado_em = now(), finalizado_em = NULL, mensagem_erro = NULL
            """;

    /*
     * Contagens calculadas do banco, e não dos contadores da execução atual: depois de um restart,
     * a execução nova só conhece as linhas que ela leu. As inválidas vêm de linha_invalida, e não da
     * soma dos skips das execuções: um chunk desfeito numa falha já teve os skips contados e, relido
     * no restart, seria contado de novo (no teste de 1 milhão: 4.964 em vez de 4.960).
     */
    private static final String SQL_FINALIZAR = """
            UPDATE arquivo_recebido
               SET status = ?, mensagem_erro = ?, finalizado_em = now(),
                   linhas_processadas = (SELECT count(*) FROM resultado_conciliacao
                                          WHERE id_arquivo = ? AND numero_linha IS NOT NULL),
                   linhas_invalidas = (SELECT count(*) FROM linha_invalida WHERE id_arquivo = ?)
             WHERE id = ?
            """;

    // A mesma linha registrada de novo (chunk relido num restart) é ignorada: a contagem fica exata.
    private static final String SQL_LINHA_INVALIDA = """
            INSERT INTO linha_invalida (id_arquivo, numero_linha, motivo) VALUES (?, ?, ?)
            ON CONFLICT (id_arquivo, numero_linha) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public RepositorioArquivo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void iniciarProcessamento(UUID id, String bucket, String chave, String nomeArquivo, String etag,
                                     long tamanhoBytes, LocalDate dataReferencia, Instant recebidoEm) {
        jdbc.sql(SQL_INICIAR)
                .params(id, bucket, chave, nomeArquivo, etag, tamanhoBytes, dataReferencia, Timestamp.from(recebidoEm))
                .update();
    }

    public void finalizarProcessamento(UUID id, String status, String mensagemErro) {
        jdbc.sql(SQL_FINALIZAR)
                .params(status, mensagemErro, id, id, id)
                .update();
    }

    public void registrarLinhaInvalida(UUID idArquivo, int numeroLinha, String motivo) {
        jdbc.sql(SQL_LINHA_INVALIDA).params(idArquivo, numeroLinha, motivo).update();
    }
}
