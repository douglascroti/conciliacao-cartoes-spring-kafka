# Comandos de execução e validação

Guia prático para subir, operar e validar o projeto. Cada etapa do roadmap acrescenta uma seção.
Os comandos estão em **PowerShell** (Windows); onde a sintaxe muda no bash, há uma observação.

> **Antivírus com inspeção HTTPS (AVG, Avast etc.):** se o Maven falhar com `PKIX path building failed`,
> o antivírus está reassinando os certificados. Faça o Java confiar nos certificados do Windows:
> ```powershell
> [Environment]::SetEnvironmentVariable('MAVEN_OPTS','-Djavax.net.ssl.trustStoreType=Windows-ROOT','User')
> ```
> e abra um terminal novo. Não coloque isso em `.mvn/jvm.config`: quebraria o build em Linux/Docker.

---

## Etapa 1 — Estrutura Maven e infraestrutura

### Build

```powershell
.\mvnw.cmd verify          # bash: ./mvnw verify
```

Esperado: `BUILD SUCCESS` para os módulos `conciliacao-eventos`, `conciliacao-lambda` e `conciliacao-batch`.

### Subir e conferir a infra

```powershell
docker compose up -d --wait   # retorna quando todos os serviços estão saudáveis
docker compose ps -a          # localstack, kafka e postgres "healthy"; kafka-init e flyway "Exited (0)"
docker logs kafka-init        # lista os tópicos criados
```

### LocalStack / S3

```powershell
curl.exe http://localhost:4566/_localstack/health      # status dos serviços emulados
docker exec localstack awslocal s3 ls                   # bucket "conciliacao"
"ola" | docker exec -i localstack awslocal s3 cp - s3://conciliacao/entrada/teste.txt
docker exec localstack awslocal s3 ls s3://conciliacao/entrada/
```

`awslocal` é a AWS CLI já apontada para o LocalStack, disponível dentro do container
(não é preciso instalar a AWS CLI no Windows).

### Kafka — produzir e consumir manualmente

Em dois terminais:

```powershell
# terminal A: consumidor
docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.resultado --from-beginning

# terminal B: produtor (digite linhas e Enter; Ctrl+C para sair)
docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic conciliacao.resultado
```

Detalhes dos tópicos:

```powershell
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe
```

> Dentro do container do Kafka usa-se `localhost:9092`. Outros containers usam `kafka:9092`
> e aplicações rodando no Windows usam `localhost:9094` (ver ADR 0002).

### PostgreSQL

```powershell
docker exec -it postgres psql -U conciliacao -d conciliacao -c "select version();"
```

Cliente gráfico (DBeaver, IntelliJ): `localhost:5432`, banco/usuário/senha `conciliacao`.

### O que persiste entre reinícios

```powershell
docker compose down; docker compose up -d --wait
```

| Componente | Persiste? | Por quê |
| --- | --- | --- |
| Mensagens e tópicos do Kafka | Sim | volume `kafka-data` |
| Dados do PostgreSQL | Sim | volume `postgres-data` |
| Bucket, objetos e Lambda no LocalStack | **Não** | o LocalStack não guarda estado; os scripts em `infra/localstack/init` recriam tudo |

Para apagar tudo, inclusive os volumes:

```powershell
docker compose down -v
```

### Uso de memória

```powershell
docker stats --no-stream
```

---

## Etapa 2 — Lambda: upload no S3 → validação → Kafka

### Ordem para subir do zero

```powershell
.\mvnw.cmd package              # gera conciliacao-lambda/target/conciliacao-lambda-*-aws.jar
docker compose up -d --wait     # o LocalStack publica a Lambda ao iniciar (infra/localstack/init/02-deploy-lambda.sh)
```

Se o LocalStack subir antes do JAR existir, o log avisa e basta publicar depois:

```powershell
.\scripts\deploy-lambda.ps1     # recompila o módulo da Lambda e publica (cria ou atualiza)
```

Use o mesmo script sempre que alterar o código da Lambda.

### Conferir a Lambda e a migration

```powershell
docker exec localstack awslocal lambda list-functions --query "Functions[].[FunctionName,Runtime,MemorySize]" --output table
docker exec localstack awslocal s3api get-bucket-notification-configuration --bucket conciliacao
docker logs flyway                                   # migration V1 aplicada
docker exec postgres psql -U conciliacao -d conciliacao -c "\d arquivo_recebido"
```

### Cenário 1 — arquivo válido gera um evento

```powershell
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
```

`/exemplos` dentro do container é a pasta `infra/exemplos` do projeto.

Ver o evento (chave e valor):

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --from-beginning --formatter-property print.key=true --timeout-ms 8000
```

O `TimeoutException` no final é esperado: é o `--timeout-ms` encerrando o consumidor após 8 s sem mensagens novas.

Ver o registro de idempotência:

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select nome_arquivo, etag, data_referencia, status, recebido_em from arquivo_recebido order by recebido_em"
```

### Cenário 2 — reenvio do mesmo arquivo não publica

```powershell
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
```

O log da Lambda mostra `Duplicado: ...` e o tópico continua com um evento só para esse arquivo.

### Cenário 3 — arquivos inválidos vão para `rejeitados/`

```powershell
docker exec localstack awslocal s3 cp /exemplos/invalidos/conciliacao_20261002.csv s3://conciliacao/entrada/   # cabeçalho errado
docker exec localstack awslocal s3 cp /exemplos/invalidos/vendas_outubro.csv s3://conciliacao/entrada/         # nome fora do padrão
docker exec localstack awslocal s3 ls s3://conciliacao --recursive
docker exec localstack awslocal s3api head-object --bucket conciliacao --key rejeitados/conciliacao_20261002.csv --query Metadata
```

### Cenário 4 — Kafka fora do ar (rollback e retentativa)

```powershell
docker stop kafka
# arquivo válido com outra data (gerado a partir do exemplo e enviado pela entrada padrão)
(Get-Content infra\exemplos\conciliacao_20261001.csv -Raw) -replace '2026-10-01','2026-10-05' | docker exec -i localstack awslocal s3 cp - s3://conciliacao/entrada/conciliacao_20261005.csv
```

Após ~15 s o log mostra `Falha ao publicar evento ...` e o arquivo **não** está em
`arquivo_recebido` (o registro foi desfeito). Depois:

```powershell
docker start kafka
```

Em 1 a 2 minutos a retentativa automática da Lambda publica o evento e o registro aparece.

> Se a Lambda estiver fria (deploy recente) quando o Kafka estiver fora, a falha acontece já na
> criação do producer, antes do handler; a retentativa também resolve (ver ADR 0004).

### Logs da Lambda

```powershell
docker exec localstack awslocal logs filter-log-events --log-group-name /aws/lambda/receber-arquivo-conciliacao --query "events[].message" --output text
```

Linhas úteis: `Publicado`, `Duplicado`, `Rejeitado` e o `REPORT` de cada invocação
(`Init Duration` só aparece no cold start).

### Invocar a Lambda manualmente (sem upload)

O arquivo `infra/exemplos/evento-s3.json` reproduz a notificação que o S3 envia (com o ETag real
do arquivo de exemplo, então o resultado esperado é `Duplicado` se o cenário 1 já rodou):

```powershell
docker exec localstack awslocal lambda invoke --function-name receber-arquivo-conciliacao --payload fileb:///exemplos/evento-s3.json /tmp/saida.json
```

> O payload precisa ter o formato completo do S3. Um JSON qualquer chega à função sem registros
> e gera o aviso `Evento sem registros S3 recebido`.

### Testes unitários

```powershell
.\mvnw.cmd -pl conciliacao-lambda -am test
```

### Recomeçar os testes do zero

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "truncate arquivo_recebido"
docker compose restart localstack     # bucket recriado vazio e Lambda republicada
```

---

## Idempotência: PostgreSQL ou DynamoDB

A Lambda guarda o registro de idempotência (nome + ETag) no provedor definido por
`IDEMPOTENCIA_PROVEDOR`: `postgres` (padrão) ou `dynamodb` (no próprio LocalStack, sem container novo).
A tabela `arquivo-recebido` do DynamoDB é criada sempre pelo init (`03-criar-tabela-dynamo.sh`).

### Trocar o provedor

No `.env`:

```
IDEMPOTENCIA_PROVEDOR=dynamodb
```

```powershell
docker compose up -d --wait           # recria o LocalStack, que republica a Lambda com a variável nova
```

Para testar sem editar o `.env` (a variável do terminal tem prioridade sobre o `.env`):

```powershell
$env:IDEMPOTENCIA_PROVEDOR='dynamodb'; docker compose up -d --wait; Remove-Item Env:IDEMPOTENCIA_PROVEDOR
```

No bash: `IDEMPOTENCIA_PROVEDOR=dynamodb docker compose up -d --wait`.

### Conferir

```powershell
# provedor configurado na Lambda
docker exec localstack awslocal lambda get-function-configuration --function-name receber-arquivo-conciliacao --query "Environment.Variables.IDEMPOTENCIA_PROVEDOR" --output text

# tabela e itens no DynamoDB
docker exec localstack awslocal dynamodb describe-table --table-name arquivo-recebido --query "Table.[TableStatus,KeySchema]"
docker exec localstack awslocal dynamodb scan --table-name arquivo-recebido --query "Items[].[nomeArquivo.S,etag.S,id.S,recebidoEm.S]" --output table
```

No log da Lambda, a primeira linha de cada cold start mostra o provedor:
`Idempotência no PostgreSQL` ou `Idempotência no DynamoDB (tabela arquivo-recebido)`.
Com DynamoDB não aparece `Conexão com o banco aberta`: nenhuma conexão JDBC é criada.

Os cenários 1 a 4 da etapa 2 valem para os dois provedores. Diferenças com DynamoDB:

- o registro está no DynamoDB, não em `arquivo_recebido` (essa linha passa a ser criada pelo job Batch);
- o LocalStack não guarda estado, então a idempotência zera a cada recriação do container.

> Para medir cold start, use um container limpo e um upload por vez. Forçar vários cold starts
> seguidos (`update-function-configuration` em loop) pode deixar o LocalStack preso com
> `timed out during startup`; nesse caso, `docker compose up -d --wait --force-recreate localstack`.

---

## Diagnóstico — memória do Kafka

O Kafka é uma aplicação Java; a memória tem três camadas: o container, a heap da JVM
(limitada por `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m` no compose) e o disco dos logs de mensagens.

```powershell
# 1. Memória do container (RSS total: heap + metaspace + threads + buffers)
docker stats kafka --no-stream

# 2. Heap da JVM pelo log de GC: "antes->depois(reservado)", ex.: 260M->172M(289M)
docker exec kafka tail -5 /opt/kafka/logs/kafkaServer-gc.log

# 3. Espaço em disco ocupado pelas mensagens
docker exec kafka du -sh /var/lib/kafka/data
```

A imagem `apache/kafka` traz só o JRE (sem `jcmd`/`jstat`), por isso a heap é observada pelo log de GC.
Se o uso da heap encostar no `-Xmx` com pausas de GC frequentes, aumente o `KAFKA_HEAP_OPTS`.

---

## Diagnóstico — ver o conteúdo dos tópicos Kafka

O Kafka não é uma tabela: cada tópico é um **log só de acréscimo**, dividido em partições, e
cada mensagem tem uma posição fixa (**offset**). Não existe `SELECT ... WHERE`: "consultar" é
ler o log a partir de uma posição. Ler **não apaga** a mensagem (diferente de SQS/RabbitMQ);
ela fica até expirar a retenção (7 dias por padrão).

| PostgreSQL | Kafka |
| --- | --- |
| tabela | tópico (dividido em partições) |
| linha | mensagem (chave + valor + timestamp) |
| `id` / posição | partição + offset |
| `SELECT *` | consumer com `--from-beginning` |
| `WHERE` | não existe: quem lê filtra |

Quantas mensagens por partição (`tópico:partição:próximo-offset`, ou seja, o total na partição):

```powershell
docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido
```

Ler tudo, com data, partição, offset e chave:

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --from-beginning --timeout-ms 6000 --formatter-property print.timestamp=true --formatter-property print.partition=true --formatter-property print.offset=true --formatter-property print.key=true
```

Ler uma mensagem exata (partição 1, offset 0):

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --partition 1 --offset 0 --max-messages 1
```

"Filtrar" no lado de quem lê (`Select-String` é o grep do PowerShell):

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --from-beginning --timeout-ms 6000 | Select-String "20261004"
```

Consumer groups (quem consome e quanto falta ler, o *lag*):

```powershell
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
```

Ruídos esperados: o aviso sobre `KIP-848` e o `TimeoutException` ao final (é o `--timeout-ms` encerrando a leitura).

---

## Diagnóstico — validar a idempotência (PostgreSQL)

A identidade do arquivo é **nome + ETag** (o ETag é o hash do conteúdo calculado pelo S3).
A garantia vem da constraint `UNIQUE (nome_arquivo, etag)` combinada com
`INSERT ... ON CONFLICT DO NOTHING RETURNING id` (ver ADR 0003). Equivalências com a
alternativa DynamoDB que foi descartada:

| DynamoDB (descartado) | PostgreSQL (adotado) |
| --- | --- |
| chave da tabela `nome#etag` | `UNIQUE (nome_arquivo, etag)` |
| `PutItem` com `attribute_not_exists` | `INSERT ... ON CONFLICT DO NOTHING` |
| `ConditionalCheckFailedException` = duplicado | `RETURNING` sem linha = duplicado |

### A) A regra existe no banco

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select conname, pg_get_constraintdef(oid) from pg_constraint where conrelid='arquivo_recebido'::regclass and contype='u'"
```

Esperado: `uk_arquivo_recebido_nome_etag | UNIQUE (nome_arquivo, etag)`.

### B) O mecanismo direto no SQL

Rode o mesmo `INSERT` duas vezes: a primeira devolve o `id` (`INSERT 0 1`), a segunda não
devolve linha (`INSERT 0 0`). É exatamente o que a Lambda faz.

```powershell
1..2 | % { docker exec postgres psql -U conciliacao -d conciliacao -c "insert into arquivo_recebido (bucket, chave, nome_arquivo, etag, tamanho_bytes, data_referencia) values ('conciliacao','entrada/teste_sql.csv','teste_sql.csv','etag-manual',1,'2026-10-01') on conflict (nome_arquivo, etag) do nothing returning id" }
docker exec postgres psql -U conciliacao -d conciliacao -c "delete from arquivo_recebido where nome_arquivo='teste_sql.csv'"   # limpa o teste
```

### C) Pela Lambda: mesmo arquivo de novo → ignorado

```powershell
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
docker exec postgres psql -U conciliacao -d conciliacao -c "select nome_arquivo, etag, recebido_em from arquivo_recebido where nome_arquivo='conciliacao_20261001.csv'"
```

O número de linhas não muda, o log da Lambda mostra `Duplicado` e o `kafka-get-offsets.sh` continua com o mesmo total.

### D) Pela Lambda: mesmo nome, conteúdo diferente → aceito

Simula a adquirente reenviando o arquivo corrigido: ETag novo, arquivo novo.

```powershell
(Get-Content infra\exemplos\conciliacao_20261001.csv -Raw) -replace '150.90','151.90' | docker exec -i localstack awslocal s3 cp - s3://conciliacao/entrada/conciliacao_20261001.csv
```

Após alguns segundos (até ~15 s se a Lambda estiver fria), a consulta do item C mostra **duas**
linhas com ETags diferentes, e o tópico ganha um evento.

### E) Falha na publicação não "queima" o arquivo

É o cenário 4 da etapa 2 (Kafka fora do ar): o registro é desfeito e a retentativa da Lambda
publica quando o Kafka volta. Sem esse rollback, a retentativa veria o arquivo como duplicado
e ele nunca seria processado.
