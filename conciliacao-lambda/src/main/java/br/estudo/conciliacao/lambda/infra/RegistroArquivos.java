package br.estudo.conciliacao.lambda.infra;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

/**
 * Registro de idempotência dos arquivos recebidos (tabela {@code arquivo_recebido}).
 */
@Repository
public class RegistroArquivos {

    /*
     * Checagem e gravação numa única instrução atômica: se (nome_arquivo, etag) já existe,
     * o ON CONFLICT não insere nada e o RETURNING não devolve linha. Não há janela entre
     * "verificar" e "gravar" em que duas execuções simultâneas possam passar.
     */
    private static final String SQL_REGISTRAR = """
            INSERT INTO arquivo_recebido (bucket, chave, nome_arquivo, etag, tamanho_bytes, data_referencia)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (nome_arquivo, etag) DO NOTHING
            RETURNING id, recebido_em
            """;

    private static final String SQL_REMOVER = "DELETE FROM arquivo_recebido WHERE id = ?";

    private final ConexaoBanco conexaoBanco;

    public RegistroArquivos(ConexaoBanco conexaoBanco) {
        this.conexaoBanco = conexaoBanco;
    }

    /** Registra o arquivo; vazio se ele já tinha sido recebido (duplicado). */
    public Optional<ArquivoRegistrado> registrar(String bucket, String chave, String nomeArquivo,
                                                 String etag, long tamanhoBytes, LocalDate dataReferencia) {
        try (PreparedStatement stmt = conexaoBanco.obter().prepareStatement(SQL_REGISTRAR)) {
            stmt.setString(1, bucket);
            stmt.setString(2, chave);
            stmt.setString(3, nomeArquivo);
            stmt.setString(4, etag);
            stmt.setLong(5, tamanhoBytes);
            stmt.setObject(6, dataReferencia);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ArquivoRegistrado(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("recebido_em").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao registrar arquivo " + nomeArquivo, e);
        }
    }

    /** Desfaz o registro, para que uma nova tentativa da Lambda possa processar o arquivo. */
    public void remover(UUID id) {
        try (PreparedStatement stmt = conexaoBanco.obter().prepareStatement(SQL_REMOVER)) {
            stmt.setObject(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao remover registro do arquivo " + id, e);
        }
    }

    public record ArquivoRegistrado(UUID id, Instant recebidoEm) {
    }
}
