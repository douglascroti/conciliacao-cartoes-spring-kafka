package br.estudo.conciliacao.lambda.idempotencia;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import br.estudo.conciliacao.lambda.infra.ConexaoBanco;

/**
 * Registro de idempotência no PostgreSQL (tabela {@code arquivo_recebido}). Provedor padrão.
 */
public class RegistroIdempotenciaPostgres implements RegistroIdempotencia {

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

    public RegistroIdempotenciaPostgres(ConexaoBanco conexaoBanco) {
        this.conexaoBanco = conexaoBanco;
    }

    @Override
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
                        nomeArquivo,
                        etag,
                        rs.getTimestamp("recebido_em").toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao registrar arquivo " + nomeArquivo, e);
        }
    }

    @Override
    public void remover(ArquivoRegistrado arquivo) {
        try (PreparedStatement stmt = conexaoBanco.obter().prepareStatement(SQL_REMOVER)) {
            stmt.setObject(1, arquivo.id());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao remover registro do arquivo " + arquivo.id(), e);
        }
    }
}
