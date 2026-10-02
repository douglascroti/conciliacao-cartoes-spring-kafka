package br.estudo.conciliacao.batch.persistencia;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import br.estudo.conciliacao.batch.conciliacao.ChaveTransacao;
import br.estudo.conciliacao.batch.conciliacao.ResultadoConciliacao;
import br.estudo.conciliacao.batch.conciliacao.TransacaoAutorizada;

/**
 * Acesso ao banco da conciliação com SQL explícito (sem ORM): em alto volume, saber exatamente
 * qual consulta roda e quantas vezes é o que define o desempenho.
 */
@Repository
public class RepositorioConciliacao {

    /** Usado também pelo reader do step inverso. */
    public static final String COLUNAS_AUTORIZACAO = "id, nsu, codigo_autorizacao, data_transacao, valor, parcelas";

    /*
     * Uma consulta para o lote inteiro: "nsu = ANY(array)" equivale a um IN com todos os NSUs.
     * O código de autorização é conferido depois, na chave do Map. O índice único (nsu, codigo)
     * atende a busca, porque nsu é a primeira coluna.
     */
    private static final String SQL_BUSCAR_AUTORIZACOES =
            "SELECT " + COLUNAS_AUTORIZACAO + " FROM transacao_autorizada WHERE nsu = ANY(?)";

    private static final String SQL_INSERIR_RESULTADO = """
            INSERT INTO resultado_conciliacao (id_arquivo, numero_linha, nsu, codigo_autorizacao, status,
                                               campos_divergentes, valor_arquivo, valor_autorizado, id_transacao_autorizada)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public RepositorioConciliacao(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<ChaveTransacao, TransacaoAutorizada> buscarAutorizacoes(Collection<String> nsus) {
        return jdbc.sql(SQL_BUSCAR_AUTORIZACOES)
                .param(nsus.toArray(String[]::new))   // o driver do Postgres converte String[] em text[]
                .query(RepositorioConciliacao::mapearAutorizacao)
                .list()
                .stream()
                .collect(Collectors.toMap(TransacaoAutorizada::chave, Function.identity()));
    }

    /**
     * Grava o lote num único batch JDBC (com {@code reWriteBatchedInserts} na URL, o driver junta
     * tudo em poucos INSERTs com vários VALUES). Roda na transação do chunk: se algo falhar
     * depois, inclusive a publicação no Kafka, a gravação é desfeita.
     */
    public void inserirResultados(UUID idArquivo, List<ResultadoConciliacao> resultados) {
        jdbcTemplate.batchUpdate(SQL_INSERIR_RESULTADO, resultados, resultados.size(),
                (PreparedStatement ps, ResultadoConciliacao r) -> {
                    ps.setObject(1, idArquivo);
                    ps.setObject(2, r.numeroLinha(), Types.INTEGER);
                    ps.setString(3, r.nsu());
                    ps.setString(4, r.codigoAutorizacao());
                    ps.setString(5, r.status().name());
                    ps.setString(6, r.camposDivergentes().isEmpty() ? null
                            : r.camposDivergentes().stream().map(Enum::name).collect(Collectors.joining(",")));
                    ps.setBigDecimal(7, r.valorArquivo());
                    ps.setBigDecimal(8, r.valorAutorizado());
                    ps.setObject(9, r.idTransacaoAutorizada(), Types.BIGINT);
                });
    }

    /** Quantidade de resultados por status de um arquivo, para o resumo no fim do job. */
    public Map<String, Long> contarPorStatus(UUID idArquivo) {
        return jdbc.sql("SELECT status, count(*) AS total FROM resultado_conciliacao WHERE id_arquivo = ? GROUP BY status ORDER BY status")
                .param(idArquivo)
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("total")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public long contarLinhasInvalidas(UUID idArquivo) {
        return jdbc.sql("SELECT count(*) FROM linha_invalida WHERE id_arquivo = ?").param(idArquivo).query(Long.class).single();
    }

    public static TransacaoAutorizada mapearAutorizacao(ResultSet rs, int numeroLinha) throws SQLException {
        return new TransacaoAutorizada(
                rs.getLong("id"),
                rs.getString("nsu"),
                rs.getString("codigo_autorizacao"),
                rs.getTimestamp("data_transacao").toLocalDateTime(),
                rs.getBigDecimal("valor"),
                rs.getInt("parcelas"));
    }
}
