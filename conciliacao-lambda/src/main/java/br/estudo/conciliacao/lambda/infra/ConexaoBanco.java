package br.estudo.conciliacao.lambda.infra;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uma única conexão JDBC, aberta sob demanda e reaproveitada entre invocações.
 *
 * <p>Sem pool de conexões de propósito: cada container de Lambda processa um evento por vez,
 * então um pool só ocuparia mais conexões no Postgres. Se a conexão cair (container
 * congelado por muito tempo, banco reiniciado), ela é reaberta na próxima chamada.
 * Na AWS real, com muitas Lambdas em paralelo, o recomendado é o RDS Proxy (ver ADR 0003).
 */
public class ConexaoBanco implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConexaoBanco.class);

    private final String url;
    private final String usuario;
    private final String senha;
    private Connection conexao;

    public ConexaoBanco(String url, String usuario, String senha) {
        this.url = url;
        this.usuario = usuario;
        this.senha = senha;
    }

    public synchronized Connection obter() throws SQLException {
        if (conexao == null || !conexao.isValid(2)) {
            close();
            conexao = DriverManager.getConnection(url, usuario, senha);
            log.info("Conexão com o banco aberta");
        }
        return conexao;
    }

    @Override
    public synchronized void close() {
        if (conexao != null) {
            try {
                conexao.close();
            } catch (SQLException e) {
                log.debug("Falha ao fechar conexão antiga", e);
            }
            conexao = null;
        }
    }
}
