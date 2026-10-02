package br.estudo.conciliacao.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import br.estudo.conciliacao.eventos.ArquivoRecebidoEvento;
import br.estudo.conciliacao.eventos.Topicos;
import br.estudo.conciliacao.teste.Containers;
import br.estudo.conciliacao.teste.LeitorTopico;
import br.estudo.conciliacao.teste.Projeto;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

/**
 * O serviço inteiro, como em produção: o evento chega pelo Kafka, o job lê o arquivo do S3
 * (LocalStack), concilia com as autorizações no PostgreSQL e publica resultados e erros no Kafka.
 */
@SpringBootTest(properties = "logging.file.name=target/batch-it.log")
class ConciliacaoJobIT {

    private static final String BUCKET = "conciliacao";
    private static final Duration LIMITE = Duration.ofSeconds(60);

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    S3Client s3;

    @Autowired
    JdbcTemplate banco;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry props) {
        PostgreSQLContainer pg = Containers.postgres();
        LocalStackContainer ls = Containers.localstack();
        props.add("spring.datasource.url", pg::getJdbcUrl);
        props.add("spring.datasource.username", pg::getUsername);
        props.add("spring.datasource.password", pg::getPassword);
        props.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        props.add("conciliacao.s3.endpoint", () -> ls.getEndpoint().toString());
        props.add("conciliacao.s3.regiao", ls::getRegion);
    }

    @BeforeAll
    static void carregarAutorizacoes() throws Exception {
        PostgreSQLContainer pg = Containers.postgres();
        String sql = Files.readString(Projeto.raiz().resolve("infra/exemplos/transacoes_autorizadas_20261001.sql"));
        try (var c = java.sql.DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void conciliaTodosOsStatusEPublicaOsResultados() throws Exception {
        ArquivoRecebidoEvento evento = enviar("conciliacao_20261001.csv", "conciliacao_20261001.csv", LocalDate.of(2026, 10, 1));

        await().atMost(LIMITE).until(() -> "CONCLUIDO".equals(status(evento.idArquivo())));

        assertThat(contagemPorStatus(evento.idArquivo())).isEqualTo(Map.of(
                "CONCILIADA", 1L, "DIVERGENTE", 2L, "NAO_ENCONTRADA", 1L, "AUSENTE_NO_ARQUIVO", 1L));
        assertThat(banco.queryForObject("select campos_divergentes from resultado_conciliacao where id_arquivo = ? and nsu = '000123457'",
                String.class, evento.idArquivo())).isEqualTo("VALOR");

        var mensagens = LeitorTopico.esperar(Topicos.RESULTADO,
                r -> r.value().contains(evento.idArquivo().toString()), 5, LIMITE);
        assertThat(mensagens).hasSize(5);
        // A chave é o NSU (espalha os resultados pelas partições); nenhuma mensagem leva o PAN.
        assertThat(mensagens).allSatisfy(r -> {
            assertThat(r.value()).contains("\"nsu\":\"" + r.key() + "\"");
            assertThat(r.value()).doesNotContain("******");
        });
    }

    @Test
    void eventoRepetidoNaoReprocessa() throws Exception {
        ArquivoRecebidoEvento evento = enviar("conciliacao_20261001.csv", "repetido_conciliacao_20261001.csv", LocalDate.of(2026, 10, 1));
        await().atMost(LIMITE).until(() -> "CONCLUIDO".equals(status(evento.idArquivo())));

        publicar(evento);   // o mesmo evento entregue de novo

        // Dá tempo de o listener consumir a repetição; a JobInstance do arquivo continua sendo uma só.
        await().pollDelay(Duration.ofSeconds(3)).atMost(LIMITE).untilAsserted(() -> {
            assertThat(execucoes(evento.idArquivo())).isEqualTo(1);
            assertThat(contagemPorStatus(evento.idArquivo()).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(5);
        });
    }

    @Test
    void linhasInvalidasSaoPuladasRegistradasEPublicadas() throws Exception {
        ArquivoRecebidoEvento evento = enviar("conciliacao_20261003.csv", "conciliacao_20261003.csv", LocalDate.of(2026, 10, 3));

        await().atMost(LIMITE).until(() -> "CONCLUIDO".equals(status(evento.idArquivo())));

        assertThat(banco.queryForList("select numero_linha from linha_invalida where id_arquivo = ? order by 1",
                Integer.class, evento.idArquivo())).containsExactly(3, 4, 5, 6);
        assertThat(banco.queryForObject("select linhas_invalidas from arquivo_recebido where id = ?",
                Integer.class, evento.idArquivo())).isEqualTo(4);
        assertThat(contagemPorStatus(evento.idArquivo())).isEqualTo(Map.of("NAO_ENCONTRADA", 3L));

        var erros = LeitorTopico.esperar(Topicos.ERRO, r -> r.value().contains(evento.idArquivo().toString()), 4, LIMITE);
        assertThat(erros).hasSize(4);
        // O motivo nunca traz o conteúdo da linha (que tem o PAN mascarado).
        assertThat(erros).allSatisfy(r -> assertThat(r.value()).doesNotContain("******"));
    }

    /**
     * Sobe um arquivo de infra/exemplos no S3 e publica o evento que a Lambda publicaria. Cada teste
     * usa um nome próprio: arquivo_recebido tem UNIQUE (nome, etag), e o mesmo nome + conteúdo com
     * outro id (algo que a Lambda impede em produção) deixaria o status sem acompanhamento.
     */
    private ArquivoRecebidoEvento enviar(String exemplo, String arquivo, LocalDate dataReferencia) throws Exception {
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
            s3.createBucket(r -> r.bucket(BUCKET));
        }
        Path origem = Projeto.raiz().resolve("infra/exemplos").resolve(exemplo);
        String chave = "entrada/" + arquivo;
        String etag = s3.putObject(r -> r.bucket(BUCKET).key(chave), RequestBody.fromFile(origem)).eTag().replace("\"", "");
        var evento = new ArquivoRecebidoEvento(UUID.randomUUID(), BUCKET, chave, arquivo, etag, Files.size(origem),
                dataReferencia, Instant.now());
        publicar(evento);
        return evento;
    }

    private void publicar(ArquivoRecebidoEvento evento) throws Exception {
        kafka.send(Topicos.ARQUIVO_RECEBIDO, evento.idArquivo().toString(), json.writeValueAsString(evento)).get();
    }

    private String status(UUID idArquivo) {
        return banco.query("select status from arquivo_recebido where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, idArquivo);
    }

    private Map<String, Long> contagemPorStatus(UUID idArquivo) {
        return banco.queryForList("select status, count(*) total from resultado_conciliacao where id_arquivo = ? group by status", idArquivo)
                .stream().collect(Collectors.toMap(l -> (String) l.get("status"), l -> ((Number) l.get("total")).longValue()));
    }

    private int execucoes(UUID idArquivo) {
        return banco.queryForObject("""
                select count(*) from batch_job_execution_params
                 where parameter_name = 'idArquivo' and parameter_value = ?
                """, Integer.class, idArquivo.toString());
    }
}
