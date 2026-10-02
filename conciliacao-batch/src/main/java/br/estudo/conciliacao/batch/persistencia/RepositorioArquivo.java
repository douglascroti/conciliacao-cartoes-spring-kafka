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
     * a execução nova só conhece as linhas que ela leu. As linhas inválidas somam os skips de todas
     * as execuções do job (JobInstance) deste arquivo.
     */
    private static final String SQL_FINALIZAR = """
            UPDATE arquivo_recebido
               SET status = ?, mensagem_erro = ?, finalizado_em = now(),
                   linhas_processadas = (SELECT count(*) FROM resultado_conciliacao
                                          WHERE id_arquivo = ? AND numero_linha IS NOT NULL),
                   linhas_invalidas = (SELECT coalesce(sum(s.read_skip_count), 0)
                                         FROM batch_step_execution s
                                         JOIN batch_job_execution e ON e.job_execution_id = s.job_execution_id
                                        WHERE e.job_instance_id = ? AND s.step_name = 'conciliarLinhas')
             WHERE id = ?
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

    public void finalizarProcessamento(UUID id, long idJobInstance, String status, String mensagemErro) {
        jdbc.sql(SQL_FINALIZAR)
                .params(status, mensagemErro, id, idJobInstance, id)
                .update();
    }
}
