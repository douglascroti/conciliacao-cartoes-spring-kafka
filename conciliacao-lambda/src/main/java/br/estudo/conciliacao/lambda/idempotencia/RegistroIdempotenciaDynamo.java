package br.estudo.conciliacao.lambda.idempotencia;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Registro de idempotência no DynamoDB. Tabela com chave composta {@code nomeArquivo}
 * (partição) + {@code etag} (ordenação), o equivalente ao {@code UNIQUE (nome_arquivo, etag)}
 * do Postgres.
 *
 * <p>O {@code id} é gerado aqui (no Postgres quem gera é o banco), e é ele que segue no evento
 * para o job Batch criar o registro de status do arquivo.
 */
public class RegistroIdempotenciaDynamo implements RegistroIdempotencia {

    private static final Logger log = LoggerFactory.getLogger(RegistroIdempotenciaDynamo.class);

    private final DynamoDbClient dynamo;
    private final String tabela;

    public RegistroIdempotenciaDynamo(DynamoDbClient dynamo, String tabela) {
        this.dynamo = dynamo;
        this.tabela = tabela;
    }

    @Override
    public Optional<ArquivoRegistrado> registrar(String bucket, String chave, String nomeArquivo,
                                                 String etag, long tamanhoBytes, LocalDate dataReferencia) {
        UUID id = UUID.randomUUID();
        Instant recebidoEm = Instant.now();
        Map<String, AttributeValue> item = Map.of(
                "nomeArquivo", texto(nomeArquivo),
                "etag", texto(etag),
                "id", texto(id.toString()),
                "bucket", texto(bucket),
                "chave", texto(chave),
                "tamanhoBytes", AttributeValue.fromN(Long.toString(tamanhoBytes)),
                "dataReferencia", texto(dataReferencia.toString()),
                "recebidoEm", texto(recebidoEm.toString()));
        try {
            // A condição torna o PutItem atômico como o ON CONFLICT: só grava se ainda não existe
            // item com essa chave (nomeArquivo + etag). Se existe, o DynamoDB recusa com exceção.
            dynamo.putItem(r -> r.tableName(tabela)
                    .item(item)
                    .conditionExpression("attribute_not_exists(nomeArquivo)"));
            return Optional.of(new ArquivoRegistrado(id, nomeArquivo, etag, recebidoEm));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    @Override
    public void remover(ArquivoRegistrado arquivo) {
        try {
            // Só apaga se o item ainda for o deste registro (mesmo id), nunca o de outra execução.
            dynamo.deleteItem(r -> r.tableName(tabela)
                    .key(Map.of("nomeArquivo", texto(arquivo.nomeArquivo()), "etag", texto(arquivo.etag())))
                    .conditionExpression("#id = :id")
                    .expressionAttributeNames(Map.of("#id", "id"))
                    .expressionAttributeValues(Map.of(":id", texto(arquivo.id().toString()))));
        } catch (ConditionalCheckFailedException e) {
            log.debug("Registro {} já não existia ao desfazer", arquivo.id());
        }
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }
}
