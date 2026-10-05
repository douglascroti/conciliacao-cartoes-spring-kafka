package br.estudo.conciliacao.lambda.idempotencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;

import br.estudo.conciliacao.teste.Containers;
import br.estudo.conciliacao.teste.ExigeLocalStack;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Idempotência no DynamoDB do LocalStack, com a mesma chave da tabela criada pelo Terraform (infra/terraform). */
@ExigeLocalStack
class RegistroIdempotenciaDynamoIT {

    private static final String TABELA = "arquivo-recebido-teste";

    private static DynamoDbClient dynamo;
    private static RegistroIdempotenciaDynamo registro;

    @BeforeAll
    static void criarTabela() {
        LocalStackContainer ls = Containers.localstack();
        dynamo = DynamoDbClient.builder()
                .endpointOverride(ls.getEndpoint())
                .region(Region.of(ls.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ls.getAccessKey(), ls.getSecretKey())))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        dynamo.createTable(r -> r.tableName(TABELA)
                .attributeDefinitions(a -> a.attributeName("nomeArquivo").attributeType(ScalarAttributeType.S),
                        a -> a.attributeName("etag").attributeType(ScalarAttributeType.S))
                .keySchema(k -> k.attributeName("nomeArquivo").keyType(KeyType.HASH),
                        k -> k.attributeName("etag").keyType(KeyType.RANGE))
                .billingMode(BillingMode.PAY_PER_REQUEST));
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(TABELA));
        registro = new RegistroIdempotenciaDynamo(dynamo, TABELA);
    }

    @AfterAll
    static void fechar() {
        dynamo.close();
    }

    @Test
    void mesmoNomeEEtagSoRegistraUmaVez() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";

        var primeiro = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1));
        var segundo = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1));

        assertThat(primeiro).isPresent();
        assertThat(segundo).as("duplicado").isEmpty();
    }

    @Test
    void removerPermiteRegistrarDeNovo() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";
        var arquivo = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1)).orElseThrow();

        registro.remover(arquivo);

        assertThat(registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1))).isPresent();
    }

    @Test
    void removerNaoApagaORegistroDeOutraExecucao() {
        String nome = "conciliacao_" + UUID.randomUUID() + ".csv";
        var vigente = registro.registrar("conciliacao", "entrada/" + nome, nome, "etag-1", 343, LocalDate.of(2026, 10, 1)).orElseThrow();
        // Uma execução antiga, com outro id, tenta desfazer o "mesmo" arquivo: a condição no id impede.
        var antiga = new ArquivoRegistrado(UUID.randomUUID(), nome, "etag-1", vigente.recebidoEm());

        registro.remover(antiga);

        var item = dynamo.getItem(r -> r.tableName(TABELA)
                .key(Map.of("nomeArquivo", AttributeValue.fromS(nome), "etag", AttributeValue.fromS("etag-1")))).item();
        assertThat(item.get("id").s()).isEqualTo(vigente.id().toString());
    }
}
