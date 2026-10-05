package br.estudo.conciliacao.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3BucketEntity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3Entity;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3ObjectEntity;

import br.estudo.conciliacao.teste.Containers;
import br.estudo.conciliacao.teste.ExigeLocalStack;
import br.estudo.conciliacao.teste.LeitorTopico;
import br.estudo.conciliacao.teste.Projeto;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * A função da Lambda com o contexto Spring de verdade: S3 (LocalStack), Kafka e PostgreSQL em
 * containers. O evento do S3 é montado à mão, como o LocalStack/AWS o entregaria.
 */
@ExigeLocalStack
@SpringBootTest
class ReceberArquivoFunctionIT {

    private static final String BUCKET = "conciliacao";
    private static final String TOPICO = "conciliacao.arquivo-recebido";

    @Autowired
    ReceberArquivoFunction funcao;

    @Autowired
    S3Client s3;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry props) {
        LocalStackContainer ls = Containers.localstack();
        PostgreSQLContainer pg = Containers.postgres();
        props.add("conciliacao.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        props.add("conciliacao.banco.url", pg::getJdbcUrl);
        props.add("conciliacao.banco.usuario", pg::getUsername);
        props.add("conciliacao.banco.senha", pg::getPassword);
        props.add("conciliacao.s3.force-path-style", () -> "true");
        props.add("conciliacao.idempotencia.provedor", () -> "postgres");
        // O S3Client da Lambda lê endpoint, região e credenciais do ambiente (AWS_ENDPOINT_URL etc.);
        // no teste vão como propriedades de sistema, que o AWS SDK v2 também lê.
        System.setProperty("aws.endpointUrl", ls.getEndpoint().toString());
        System.setProperty("aws.region", ls.getRegion());
        System.setProperty("aws.accessKeyId", ls.getAccessKey());
        System.setProperty("aws.secretAccessKey", ls.getSecretKey());
    }

    @Test
    void arquivoValidoPublicaUmEventoEReenvioNaoPublicaDeNovo() throws Exception {
        String chave = "entrada/conciliacao_20261001.csv";
        byte[] conteudo = Files.readAllBytes(Projeto.raiz().resolve("infra/exemplos/conciliacao_20261001.csv"));
        S3Event evento = enviar(chave, conteudo);

        funcao.accept(evento);
        funcao.accept(evento);   // mesma notificação entregue duas vezes

        var mensagens = LeitorTopico.esperar(TOPICO, r -> r.value().contains("\"chave\":\"" + chave + "\""), 1, Duration.ofSeconds(20));
        assertThat(mensagens).as("um evento só, apesar das duas entregas").hasSize(1);
        String idArquivo = mensagens.getFirst().key();
        assertThat(mensagens.getFirst().value()).contains("\"dataReferencia\":\"2026-10-01\"", "\"idArquivo\":\"" + idArquivo + "\"");
        assertThat(statusNoBanco(idArquivo)).isEqualTo("RECEBIDO");
    }

    @Test
    void cabecalhoInvalidoMoveParaRejeitados() throws Exception {
        String chave = "entrada/conciliacao_20261002.csv";
        byte[] conteudo = Files.readAllBytes(Projeto.raiz().resolve("infra/exemplos/invalidos/conciliacao_20261002.csv"));

        funcao.accept(enviar(chave, conteudo));

        assertThat(existe("entrada/conciliacao_20261002.csv")).isFalse();
        var rejeitado = s3.headObject(r -> r.bucket(BUCKET).key("rejeitados/conciliacao_20261002.csv"));
        assertThat(rejeitado.metadata()).containsEntry("motivo-rejeicao", "CABECALHO_INVALIDO");
        assertThat(LeitorTopico.esperar(TOPICO, r -> r.value().contains(chave), 1, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void nomeForaDoPadraoMoveParaRejeitados() {
        String chave = "entrada/vendas_" + UUID.randomUUID() + ".csv";

        funcao.accept(enviar(chave, "qualquer conteudo".getBytes()));

        assertThat(existe(chave)).isFalse();
        var rejeitado = s3.headObject(r -> r.bucket(BUCKET).key(chave.replace("entrada/", "rejeitados/")));
        assertThat(rejeitado.metadata()).containsEntry("motivo-rejeicao", "NOME_INVALIDO");
    }

    /** Sobe o objeto e devolve a notificação que o S3 enviaria para ele. */
    private S3Event enviar(String chave, byte[] conteudo) {
        if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
            s3.createBucket(r -> r.bucket(BUCKET));
        }
        String etag = s3.putObject(r -> r.bucket(BUCKET).key(chave), RequestBody.fromBytes(conteudo)).eTag().replace("\"", "");
        var objeto = new S3ObjectEntity(chave, (long) conteudo.length, etag, null, null);
        var entidade = new S3Entity("teste", new S3BucketEntity(BUCKET, null, "arn:aws:s3:::" + BUCKET), objeto, "1.0");
        var registro = new S3EventNotificationRecord("us-east-1", "ObjectCreated:Put", "aws:s3",
                "2026-10-02T12:00:00.000Z", "2.1", null, null, entidade, null);
        return new S3Event(List.of(registro));
    }

    private static String statusNoBanco(String idArquivo) throws Exception {
        PostgreSQLContainer pg = Containers.postgres();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
             var ps = c.prepareStatement("select status from arquivo_recebido where id = ?::uuid")) {
            ps.setString(1, idArquivo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private boolean existe(String chave) {
        try {
            s3.headObject(r -> r.bucket(BUCKET).key(chave));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
