package br.estudo.conciliacao.lambda.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.estudo.conciliacao.lambda.infra.ConexaoBanco;
import br.estudo.conciliacao.teste.Containers;

/** Idempotência no PostgreSQL de verdade (com a migration V1), não num mock. */
class RegistroIdempotenciaPostgresIT {

    private static ConexaoBanco conexao;
    private static RegistroIdempotenciaPostgres registro;

    @BeforeAll
    static void conectar() {
        PostgreSQLContainer pg = Containers.postgres();
        conexao = new ConexaoBanco(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        registro = new RegistroIdempotenciaPostgres(conexao);
    }

    @AfterAll
    static void fechar() {
        conexao.close();
    }

    @Test
    void mesmoNomeEEtagSoRegistraUmaVez() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";

        var primeiro = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1));
        var segundo = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1));

        assertThat(primeiro).isPresent();
        assertThat(primeiro.get().id()).isNotNull();
        assertThat(segundo).as("duplicado").isEmpty();
    }

    @Test
    void mesmoNomeComOutroConteudoEhArquivoNovo() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";

        var original = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1));
        var correcao = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-2", 350, LocalDate.of(2026, 10, 1));

        assertThat(original).isPresent();
        assertThat(correcao).isPresent();
        assertThat(correcao.get().id()).isNotEqualTo(original.get().id());
    }

    @Test
    void removerPermiteRegistrarDeNovo() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";
        var arquivo = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1)).orElseThrow();

        registro.remover(arquivo);

        assertThat(registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1)))
                .as("retentativa depois de desfazer o registro").isPresent();
    }
}
