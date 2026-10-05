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

### Testes

```powershell
.\mvnw.cmd package                                    # só os unitários (*Test), sem Docker
.\mvnw.cmd verify                                     # unitários + integração (*IT) com Testcontainers
.\mvnw.cmd -pl conciliacao-batch -am verify           # só um módulo (e os de que ele depende)
.\mvnw.cmd -pl conciliacao-lambda -am verify "-Dit.test=RegistroIdempotenciaDynamoIT"   # um teste de integração
```

Os testes de integração sobem seus próprios containers (LocalStack, PostgreSQL, Kafka), separados do
docker-compose: não precisam do ambiente no ar nem interferem nele. Precisam do Docker e do
`LOCALSTACK_AUTH_TOKEN` (variável de ambiente ou `.env`); sem o token, os que usam o LocalStack são
pulados. Relatórios em `<módulo>\target\failsafe-reports`; o log do serviço nos testes do batch fica em
`conciliacao-batch\target\batch-it.log`.

### Recomeçar os testes do zero

```powershell
.\scripts\limpar-ambiente.ps1                                  # mostra o que seria feito, sem fazer
.\scripts\limpar-ambiente.ps1 -Executar                        # limpa tudo (~30 s)
.\scripts\limpar-ambiente.ps1 -Executar -ManterAutorizacoes -ManterMassa
```

Só para desenvolvimento. Para o serviço, esvazia as tabelas do Postgres (resultados, linhas
inválidas, arquivos, autorizações e metadados do Spring Batch; o histórico do Flyway fica, então as
migrations não rodam de novo), recria a tabela do DynamoDB, esvazia o bucket, apaga os logs da
Lambda, apaga e recria os tópicos e o consumer group do Kafka, apaga `conciliacao-batch\logs` e
`massa\`, e sobe o serviço de novo. Containers, volumes e imagens não são recriados.

O Kafka apaga os tópicos em duas fases: o disco só é liberado ~1 min depois (`file.delete.delay.ms`).

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

## Etapa 3 — Serviço Batch: evento do Kafka → job Spring Batch

### Serviço no container (padrão)

O `docker compose up -d --wait` já sobe o serviço `conciliacao-batch` e o Kafka UI
(http://localhost:8080). Depois de alterar o código do serviço:

```powershell
docker compose up -d --build conciliacao-batch       # reconstrói a imagem e recria o container
docker logs -f conciliacao-batch                     # log (em UTC dentro do container)
```

Migrations novas: `docker compose run --rm flyway` (o compose só roda o Flyway na criação do container).

### Rodar o serviço no host (IDE/debug)

Pare o container antes: duas instâncias no mesmo grupo dividiriam as partições, e a recuperação
de jobs interrompidos assume uma instância só.

```powershell
docker compose stop conciliacao-batch
.\mvnw.cmd -pl conciliacao-batch -am package -DskipTests
cd conciliacao-batch; java -jar target\conciliacao-batch-0.1.0-SNAPSHOT.jar
```

No host, o log também vai para `conciliacao-batch\logs\batch.log`. Para acompanhar de outro terminal:

```powershell
Get-Content conciliacao-batch\logs\batch.log -Wait -Tail 20 -Encoding UTF8
```

Para voltar ao container: pare o `java -jar` e rode `docker compose up -d conciliacao-batch`.

Saúde do serviço (banco, disco) e métricas:

```powershell
curl.exe -s localhost:8081/actuator/health
curl.exe -s localhost:8081/actuator/metrics/spring.batch.job   # 404 até o primeiro job desde a subida
```

Variáveis úteis: `JOB_TAMANHO_CHUNK` (padrão 1000), `JOB_EXECUCOES_SIMULTANEAS` (padrão 2),
`SERVER_PORT` (padrão 8081). Ex.: `$env:JOB_TAMANHO_CHUNK='2'` antes do `java -jar`.

### Primeira subida: ignorar eventos antigos

O grupo `conciliacao-batch` começa do início do tópico (`earliest`). Se o tópico tem eventos de
testes anteriores cujos arquivos já não existem no S3 (o LocalStack não guarda estado), posicione o
grupo no fim **com o serviço parado**:

```powershell
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group conciliacao-batch --topic conciliacao.arquivo-recebido --reset-offsets --to-latest --execute
```

Situação do grupo (offset confirmado e atraso por partição):

```powershell
docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group conciliacao-batch --describe
```

### Cenário 1 — arquivo enviado vira um job concluído

```powershell
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
```

No log: `Evento recebido` (thread do consumer), `Job iniciado`, `Job finalizado ... COMPLETED`
e `Resumo do arquivo ...` com a contagem por status (thread `job-N`). Se o arquivo já foi recebido antes, a Lambda o
trata como duplicado e nenhum job é criado; use outro nome/data.

### Cenário 2 — evento repetido não reprocessa

Reenvia ao tópico o último evento de um arquivo, simulando entrega dupla (troque o nome):

```powershell
$msg = docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --from-beginning --formatter-property print.key=true --formatter-property key.separator='|' --timeout-ms 6000 2>$null | Select-String 'conciliacao_20261001.csv' | Select-Object -Last 1 | ForEach-Object Line
$msg | docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --reader-property parse.key=true --reader-property key.separator='|'
```

No log: `Evento duplicado: arquivo ... já foi processado`.

### Cenário 3 — mensagem inválida não trava o consumer

```powershell
'teste|isto nao e json' | docker exec -i kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic conciliacao.arquivo-recebido --reader-property parse.key=true --reader-property key.separator='|'
```

Uma linha `ERROR` do `DefaultErrorHandler` (`maxAttempts=0`, sem retentativa) e o serviço segue `UP`.

### Cenário 4 — conciliação com todos os status (3b)

Carregar as autorizações de teste (uma por status) e liberar o arquivo de exemplo, caso ele já
tenha sido recebido antes:

```powershell
docker compose run --rm flyway                       # V3: transacao_autorizada e resultado_conciliacao
Get-Content infra\exemplos\transacoes_autorizadas_20261001.sql | docker exec -i postgres psql -U conciliacao -d conciliacao
docker exec postgres psql -U conciliacao -d conciliacao -c "delete from arquivo_recebido where nome_arquivo='conciliacao_20261001.csv'"
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
```

No log: `Resumo do arquivo conciliacao_20261001.csv: {AUSENTE_NO_ARQUIVO=1, CONCILIADA=1, DIVERGENTE=2, NAO_ENCONTRADA=1}`.

Resultados no banco:

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select r.numero_linha linha, r.nsu, r.status, r.campos_divergentes, r.valor_arquivo, r.valor_autorizado from resultado_conciliacao r join arquivo_recebido a on a.id = r.id_arquivo where a.nome_arquivo='conciliacao_20261001.csv' order by r.numero_linha nulls last"
```

Resultados no Kafka (chave = id do arquivo, todos na mesma partição):

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.resultado --from-beginning --formatter-property print.key=true --formatter-property print.partition=true --timeout-ms 6000
```

> No Windows, pare o serviço antes de recompilar: o `java -jar` mantém o JAR aberto e o
> `package` falha com `Unable to rename ... .jar.original`.

### Cenário 5 — linhas inválidas são puladas (3c)

`infra/exemplos/conciliacao_20261003.csv` tem 3 linhas válidas e 4 inválidas (mês 13, valor
negativo, campos faltando, parcelas em texto):

```powershell
docker compose run --rm flyway                       # V4: colunas de acompanhamento em arquivo_recebido
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261003.csv s3://conciliacao/entrada/
```

No log: um `Linha N ... inválida, pulada: <motivo>` por linha e o job `COMPLETED`. As linhas
inválidas vão para `conciliacao.erro` (número da linha e motivo, nunca o conteúdo da linha):

```powershell
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic conciliacao.erro --from-beginning --timeout-ms 5000
```

O arquivo é tratado como corrompido, e o job falha, quando as linhas inválidas passam de
`JOB_PERCENTUAL_MAXIMO_LINHAS_INVALIDAS` (padrão 1%) das linhas lidas até ali, com tolerância mínima de
`JOB_MINIMO_LINHAS_INVALIDAS` (padrão 100) para arquivos pequenos.

### Status do arquivo

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select nome_arquivo, status, linhas_processadas, linhas_invalidas, iniciado_em, finalizado_em, left(mensagem_erro, 120) erro from arquivo_recebido order by recebido_em desc limit 10"
```

`RECEBIDO` (Lambda) → `PROCESSANDO` → `CONCLUIDO` ou `FALHA`. Com `IDEMPOTENCIA_PROVEDOR=dynamodb`
a linha é criada pelo job.

### Arquivo grande para testar falhas

Para ter tempo de provocar uma falha no meio, gere 20.000 linhas e rode o serviço com chunk de 100
(`$env:JOB_TAMANHO_CHUNK='100'` antes do `java -jar`):

```powershell
$linhas = 1..20000 | ForEach-Object { '{0:D9};C{1:D5};2026-10-04T{2:D2}:{3:D2}:{4:D2};{5}.{6:D2};411111******1111;5411;{7}' -f (400000000 + $_), $_, ([int][math]::Floor($_ / 3600) % 24), ([int][math]::Floor($_ / 60) % 60), ($_ % 60), (10 + $_ % 500), ($_ % 100), (1 + $_ % 12) }
@('nsu;codigo_autorizacao;data_transacao;valor;pan_mascarado;mcc;parcelas') + $linhas | docker exec -i localstack awslocal s3 cp - s3://conciliacao/entrada/conciliacao_20261004.csv
```

### Cenário 6 — Kafka fora do ar no meio do job e restart manual

Logo depois do `Job iniciado` no log:

```powershell
docker stop kafka
```

Em ~30 s: `Job finalizado ... FAILED`, com menos linhas gravadas que lidas (o chunk em andamento
foi desfeito) e o arquivo em `FALHA`. Depois:

```powershell
docker start kafka
curl.exe -s -X POST localhost:8081/execucoes/<id-da-execução>/reiniciar     # id no log ou em batch_job_execution
```

A nova execução continua da linha seguinte ao último chunk confirmado. Conferir que não há
duplicatas (`resultados` = `linhas_distintas` = total de linhas):

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select a.status, (select count(*) from resultado_conciliacao r where r.id_arquivo=a.id) resultados, (select count(distinct numero_linha) from resultado_conciliacao r where r.id_arquivo=a.id) linhas_distintas from arquivo_recebido a where nome_arquivo='conciliacao_20261004.csv' and status <> 'RECEBIDO'"
```

### Cenário 7 — queda do serviço no meio do job e recuperação automática

Logo depois do `Job iniciado`, mate o processo (simula falta de memória ou queda da máquina):

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*conciliacao-batch*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

A execução fica `STARTED` em `batch_job_execution` e o arquivo em `PROCESSANDO`. Ao subir o serviço
de novo, o log mostra `Execução N ... estava interrompida; reiniciada como execução M` **antes** de
`Consumo de arquivoRecebido iniciado`, e o job termina do ponto em que parou.

### JobRepository: execuções, steps e parâmetros

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select e.job_execution_id exec, e.status, e.start_time, e.end_time, s.read_count lidas, s.write_count gravadas, s.commit_count commits from batch_job_execution e join batch_step_execution s using (job_execution_id) order by 1 desc limit 10"
docker exec postgres psql -U conciliacao -d conciliacao -c "select job_execution_id, parameter_name, parameter_value, identifying from batch_job_execution_params order by 1 desc limit 10"
```

> No Git Bash, prefixe os `docker exec` que têm caminhos (`/opt/kafka/...`, `/aws/lambda/...`) com
> `MSYS_NO_PATHCONV=1`; senão o Git Bash converte o caminho para `C:/Program Files/Git/...`.

---

## Etapa 4 — Gerador de massa de dados e teste de volume

### Gerar a massa

```powershell
.\mvnw.cmd -pl gerador-dados -am package
java -jar gerador-dados\target\gerador-dados-0.1.0-SNAPSHOT.jar --linhas 1000000 --data 2026-10-15
```

Saída em `massa\` (fora do git), em ~2 s para 1 milhão de linhas:

| Arquivo | Conteúdo |
| --- | --- |
| `conciliacao_20261015.csv` | arquivo da adquirente (~68 MB) |
| `transacoes_autorizadas_20261015.csv` | autorizações do emissor, no formato do `COPY` (~68 MB) |
| `gabarito_20261015.json` | quantidade esperada de cada status, para conferir o resultado do job |

Distribuição padrão das linhas do arquivo: 4% divergente (valor, parcelas ou data), 3% não encontrada,
0,5% inválida e o restante (92,5%) conciliada; mais 2,5% de autorizações que não estão no arquivo
(`AUSENTE_NO_ARQUIVO`). Tudo ajustável:

```powershell
java -jar gerador-dados\target\gerador-dados-0.1.0-SNAPSHOT.jar --linhas 50000 --data 2026-10-16 --semente 7 `
  --pct-divergente 10 --pct-nao-encontrada 5 --pct-invalida 1 --pct-ausente 3 --saida massa
```

A mesma semente gera arquivos idênticos. O NSU começa pelo `MMdd` da data, então massas de datas
diferentes não colidem na chave única de `transacao_autorizada`.

> As divergências de DATA são autorizações do dia seguinte. Não processe o arquivo do dia
> seguinte com a mesma massa, senão elas aparecem lá como `AUSENTE_NO_ARQUIVO`.

### Teste de 1 milhão de linhas

```powershell
docker compose run --rm flyway                                         # migrations até a V5 (linha_invalida)
.\scripts\carregar-autorizacoes.ps1 massa\transacoes_autorizadas_20261015.csv   # COPY: ~17 s para 1 milhão
.\scripts\enviar-arquivo.ps1 massa\conciliacao_20261015.csv
docker logs -f conciliacao-batch                                       # até "Resumo do arquivo ..."
.\scripts\conferir-gabarito.ps1 massa\gabarito_20261015.json
```

O `conferir-gabarito.ps1` compara cada status, as divergências por campo e as linhas inválidas com o
gabarito, mostra a duração (soma das execuções, se houve restart) e sai com código 1 se algo não bater.
Resultado de referência: ~112 s, ~260 MB de memória no container, todos os números conferindo.

A carga das autorizações pode ser repetida: o `COPY` vai para uma tabela temporária e o
`INSERT ... ON CONFLICT DO NOTHING` ignora o que já existe.

Para **repetir** o teste com o mesmo arquivo (a idempotência o descartaria como duplicado), libere o
reenvio antes. Só para testes: apaga o registro de idempotência (Postgres e DynamoDB) e os resultados anteriores.

```powershell
.\scripts\liberar-reenvio.ps1 conciliacao_20261015.csv
```

Em arquivos grandes, a conexão com o S3 pode cair no meio da leitura (no LocalStack, por volta de 75 s).
O log mostra `Leitura de s3://... interrompida no byte N ...; retomando` e a leitura continua do mesmo
ponto (GET com `Range` e `If-Match` do ETag), sem perder nem repetir linhas.

Espaço do Kafka por partição (a retenção de `conciliacao.resultado` é 24 h ou 512 MB por partição,
verificada a cada 5 min e só em segmentos fechados de 128 MB):

```powershell
docker exec kafka sh -c 'du -sh /var/lib/kafka/data/conciliacao.resultado-*'
```

### Medir o desempenho com outro tamanho de chunk

```powershell
.\scripts\medir-desempenho.ps1 -Chunk 5000          # -Data 2026-10-15 por padrão
```

Recria o serviço com o chunk pedido, libera o reenvio, envia o arquivo, amostra memória e CPU a
cada 3 s, confere o gabarito e termina com uma linha `RESULTADO chunk=... memoria_max=... gabarito=...`.
Depois das medições, volte ao padrão com `docker compose up -d conciliacao-batch`.

Tempo por step (o step do arquivo concentra quase tudo):

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select e.job_execution_id, s.step_name, s.read_count, s.commit_count, round(extract(epoch from s.end_time - s.start_time)::numeric, 1) segundos from batch_job_execution e join batch_step_execution s using (job_execution_id) order by 1 desc, s.step_execution_id limit 6"
```

Linhas inválidas de um arquivo:

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select numero_linha, motivo from linha_invalida where id_arquivo = (select id from arquivo_recebido where nome_arquivo='conciliacao_20261015.csv' order by recebido_em desc limit 1) order by numero_linha limit 20"
```

---

## Etapa 7 — Observabilidade (Prometheus, Grafana, Tempo)

Endereços: Grafana http://localhost:3000 (sem login = leitura; `admin`/`admin` para editar),
Prometheus http://localhost:9090, Tempo http://localhost:3200.

**Métricas** (o endpoint e a mesma consulta no Prometheus):

```powershell
(Invoke-WebRequest -UseBasicParsing http://localhost:8081/actuator/prometheus).Content -split "`n" | Select-String '^conciliacao_'
(Invoke-WebRequest -UseBasicParsing 'http://localhost:9090/api/v1/query?query=sum%20by%20(status)%20(conciliacao_resultados_total)').Content
```

Conferir com o banco (devem bater, a menos que o container tenha reiniciado e zerado os contadores):

```powershell
docker exec postgres psql -U conciliacao -d conciliacao -c "select status, count(*) from resultado_conciliacao group by 1"
```

**Coleta saudável:** Prometheus → Status → Target health (os dois alvos `UP`).

**Trace de um arquivo:** copie o `traceId` do log e cole no Grafana em Explorar → Tempo.

```powershell
docker logs --since 5m conciliacao-batch | Select-String "Evento recebido"      # [traceId-spanId] na linha
docker exec localstack awslocal logs filter-log-events --log-group-name /aws/lambda/receber-arquivo-conciliacao --filter-pattern traceId --query 'events[].message' --output text
```

Ou pela API do Tempo (lista os spans):

```bash
curl -s localhost:3200/api/v2/traces/<traceId> | grep -o '"name":"[^"]*"' | sort | uniq -c
```

**Alertas:** http://localhost:9090/alerts. Validar a sintaxe depois de editar as regras:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)/infra/prometheus:/cfg" --entrypoint promtool prom/prometheus:v3.5.0 check rules /cfg/alertas.yml
docker compose restart prometheus     # recarrega as regras (a API /-/reload não está habilitada)
```

Provocar `ArquivoComFalha`: arquivo com mais de 100 linhas inválidas e acima de 1% (ex.: 5 válidas e
300 com valor negativo), enviado com `.\scripts\enviar-arquivo.ps1`. Provocar `ServicoBatchFora`:
`docker stop conciliacao-batch`, esperar ~1,5 min, `docker start conciliacao-batch`.

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
