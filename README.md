# 💳 Conciliação de Cartões — Event-Driven com Spring Batch, Kafka e AWS

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?logo=springboot)
![Spring Batch](https://img.shields.io/badge/Spring%20Batch-alta%20volumetria-6DB33F?logo=spring)
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-KRaft-231F20?logo=apachekafka)
![AWS](https://img.shields.io/badge/AWS-S3%20%7C%20Lambda-FF9900?logo=amazonaws)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?logo=postgresql)
![Status](https://img.shields.io/badge/status-em%20desenvolvimento-yellow)

Simulação do processo de **conciliação de transações de cartão** entre uma **adquirente** e um **emissor**, construída com arquitetura orientada a eventos e processamento em lote de alta volumetria.

O projeto reproduz um cenário real do mercado de meios de pagamento: todo dia a adquirente envia um arquivo com as transações capturadas, e o emissor precisa confrontar cada linha com o que foi autorizado, identificando transações conciliadas, divergentes ou não encontradas.

---

## 📌 Índice

- [Contexto de negócio](#-contexto-de-negócio)
- [Arquitetura](#-arquitetura)
- [Stack](#-stack)
- [Decisões técnicas](#-decisões-técnicas)
- [Estrutura do repositório](#-estrutura-do-repositório)
- [Como executar](#-como-executar)
- [Eventos Kafka](#-eventos-kafka)
- [Desempenho](#-desempenho)
- [Formato do arquivo de conciliação](#-formato-do-arquivo-de-conciliação)
- [Segurança e PCI-DSS](#-segurança-e-pci-dss)
- [Roadmap](#-roadmap)
- [Autor](#-autor)

---

## 🏦 Contexto de negócio

No fluxo de cartões, a **autorização** acontece em tempo real, mas a confirmação financeira chega depois, em lote:

1. O portador faz uma compra e o **emissor autoriza** a transação.
2. Ao fim do dia, a **adquirente** envia o arquivo de transações capturadas (clearing).
3. O emissor **concilia** o arquivo com a sua base de autorizações.
4. Divergências (valor, data, transação inexistente) precisam ser tratadas antes da **liquidação** e do lançamento em fatura.

Este projeto implementa a etapa 3 de ponta a ponta, desde o recebimento do arquivo até a publicação do resultado de cada transação para os sistemas interessados.

---

## 🏗 Arquitetura

```mermaid
flowchart LR
    A[Adquirente] -->|upload CSV| S3[(S3<br/>conciliacao/entrada)]
    S3 -->|ObjectCreated| L[Lambda<br/>Java 21 + Spring Cloud Function]
    L -->|idempotência nome + ETag| ID[(PostgreSQL<br/>ou DynamoDB)]
    L -->|conciliacao.arquivo-recebido| K{{Kafka}}
    K --> C[Serviço de Conciliação<br/>Spring Boot]
    C -->|dispara| B[Job Spring Batch<br/>leitura em chunks]
    B -->|lê arquivo| S3
    B <-->|transações autorizadas| DB[(PostgreSQL)]
    B -->|conciliacao.resultado| K
    B -->|conciliacao.erro| K
```

**Fluxo resumido**

1. A adquirente sobe o arquivo no bucket S3 `conciliacao`, prefixo `entrada/`.
2. O evento do S3 dispara uma **Lambda** leve, que valida nome, layout e duplicidade (nome + ETag) e publica um único evento `arquivo-recebido` no Kafka.
3. O **serviço de conciliação** consome o evento e inicia um **job Spring Batch**.
4. O job lê o arquivo em **chunks**, confronta cada linha com as transações autorizadas no PostgreSQL e classifica o resultado.
5. Cada resultado é publicado no Kafka; linhas inválidas vão para um tópico de erro sem interromper o processamento.

A Lambda atua apenas como **gatilho**: o processamento pesado fica no batch, que suporta arquivos com milhões de linhas, restart e controle transacional, sem o limite de tempo de execução de uma função serverless.

---

## 🧰 Stack

| Camada | Tecnologia |
| --- | --- |
| Linguagem | Java 21 |
| Framework | Spring Boot 4, Spring Batch, Spring Kafka, Spring Cloud Function |
| Mensageria | Apache Kafka (modo KRaft, sem ZooKeeper) |
| Cloud (emulada localmente) | AWS S3, Lambda e DynamoDB via LocalStack |
| Banco de dados | PostgreSQL |
| Build | Maven multi-módulo (com Maven Wrapper) |
| Containers | Docker, Docker Compose, Dockerfiles multi-stage |

---

## 🧭 Decisões técnicas

Os principais pontos de arquitetura:

- **Lambda como gatilho, não como processador:** evita o limite de 15 minutos e mantém a função simples e barata.
- **Spring Batch para alta volumetria:** processamento em chunks, restart a partir do ponto de falha via `JobRepository`, skip de linhas inválidas e tamanho de chunk configurável.
- **Kafka para desacoplamento:** múltiplos consumidores podem reagir ao resultado da conciliação (agenda de recebíveis, relatórios, antifraude) sem acoplamento ao job.
- **Idempotência na entrada:** o mesmo arquivo reenviado, ou o mesmo evento do S3 entregue duas vezes, não gera reprocessamento. A chave é nome + ETag, gravada de forma atômica: `UNIQUE` + `ON CONFLICT` no PostgreSQL ou `PutItem` condicional no DynamoDB, escolhido por `IDEMPOTENCIA_PROVEDOR` sem recompilar.
- **Linhas inválidas não param o arquivo:** cada linha fora do layout é pulada e publicada em `conciliacao.erro` com o número e o motivo, sem dados do cartão; acima de um limite configurável, o arquivo é considerado corrompido.
- **Restart do ponto de falha:** se o job cai no meio, ele continua do último chunk confirmado. Uma queda do serviço é recuperada automaticamente na subida; uma falha comum pode ser reiniciada por `POST /execucoes/{id}/reiniciar`.
- **Arquivos inválidos não se perdem:** nome ou cabeçalho fora do layout movem o arquivo para `rejeitados/`, com o motivo em metadado.
- **Cold start da Lambda em Java:** custo conhecido da JVM + Spring, mitigado na AWS real com **SnapStart**.

---

## 📁 Estrutura do repositório

```
.
├── docker-compose.yml             # LocalStack, Kafka e PostgreSQL
├── .env.example                   # variáveis de ambiente necessárias
├── pom.xml                        # POM pai (multi-módulo)
├── mvnw, mvnw.cmd, .mvn/          # Maven Wrapper (não precisa instalar o Maven)
├── conciliacao-eventos/           # DTOs e contratos dos eventos
├── conciliacao-lambda/            # Lambda: valida o arquivo e publica no Kafka
├── conciliacao-batch/             # Consumer Kafka + job Spring Batch (com Dockerfile multi-stage)
├── gerador-dados/                 # Gera o arquivo da adquirente, as autorizações e o gabarito para testes de volume
├── infra/                         # Scripts de init (bucket, Lambda, tópicos), migrations e exemplos
├── scripts/                       # Deploy da Lambda, carga de massa, envio, conferência e medição
└── docs/
    └── comandos.md                # comandos do dia a dia (subir infra, deploy, inspecionar Kafka/S3/banco)
```

> A estrutura evolui conforme as fases do [roadmap](#-roadmap).

---

## 🚀 Como executar

### Pré-requisitos

- Docker e Docker Compose
- Java 21 (o Maven vem pelo wrapper `mvnw`, não precisa instalar)
- Conta gratuita no LocalStack (plano Hobby) para gerar o auth token
- Recomendado: 8 GB de RAM livres para os containers

### 1. Configurar o ambiente

```bash
cp .env.example .env
# edite o .env e informe seu LOCALSTACK_AUTH_TOKEN

# opcional: onde a Lambda guarda a idempotência (postgres é o padrão)
# IDEMPOTENCIA_PROVEDOR=dynamodb
```

### 2. Build

```bash
./mvnw clean package          # Windows: .\mvnw.cmd clean package
```

Gera, entre outros, o JAR da Lambda (`conciliacao-lambda/target/conciliacao-lambda-*-aws.jar`).

### 3. Subir o ambiente

```bash
docker compose up -d --wait   # retorna quando todos os serviços estão saudáveis
```

Ao subir, o Flyway aplica as migrations no PostgreSQL, o `kafka-init` cria os tópicos, o
LocalStack cria o bucket e a tabela do DynamoDB, publica a Lambda e liga a notificação do S3 a ela,
e o serviço de conciliação (imagem construída pelo `conciliacao-batch/Dockerfile`) passa a consumir
os eventos. Na primeira vez o build da imagem leva cerca de 2 minutos.

| Serviço | Endereço |
| --- | --- |
| Kafka UI (tópicos, mensagens, consumer groups) | http://localhost:8080 |
| Serviço de conciliação (Actuator) | http://localhost:8081/actuator/health |
| LocalStack (S3, Lambda, DynamoDB) | `localhost:4566` |
| Kafka (containers na rede Docker) | `kafka:9092` |
| Kafka (aplicações no host) | `localhost:9094` |
| PostgreSQL | `localhost:5432` |

Depois de alterar o código:

```powershell
.\scripts\deploy-lambda.ps1                          # Lambda: recompila e republica no LocalStack
docker compose up -d --build conciliacao-batch       # serviço de conciliação: reconstrói a imagem
```

### 4. Enviar um arquivo

Carregue as transações autorizadas de exemplo (uma para cada resultado possível) e envie o arquivo
da adquirente:

```bash
docker exec -i postgres psql -U conciliacao -d conciliacao < infra/exemplos/transacoes_autorizadas_20261001.sql
docker exec localstack awslocal s3 cp /exemplos/conciliacao_20261001.csv s3://conciliacao/entrada/
```

`/exemplos` é a pasta `infra/exemplos` montada no LocalStack; `awslocal` é a AWS CLI já
apontada para o LocalStack. A geração de massa de dados chega na fase 4.

### 5. Acompanhar o resultado

```bash
docker logs -f conciliacao-batch
```

```
Job finalizado: execução 1 do arquivo conciliacao_20261001.csv com status COMPLETED (5 lidas, 5 gravadas, 752 ms)
Resumo do arquivo conciliacao_20261001.csv: {AUSENTE_NO_ARQUIVO=1, CONCILIADA=1, DIVERGENTE=2, NAO_ENCONTRADA=1}
```

No Kafka UI (http://localhost:8080), os resultados ficam em `conciliacao.resultado` e as linhas
inválidas em `conciliacao.erro`. No banco:

```bash
docker exec postgres psql -U conciliacao -d conciliacao -c "select nome_arquivo, status, linhas_processadas, linhas_invalidas from arquivo_recebido order by recebido_em desc limit 5"
docker exec postgres psql -U conciliacao -d conciliacao -c "select numero_linha, nsu, status, campos_divergentes from resultado_conciliacao order by id desc limit 10"
```

Todos os cenários de validação (duplicidade, linhas inválidas, Kafka fora do ar, queda do serviço
e restart) estão em [`docs/comandos.md`](docs/comandos.md).

---

## 📨 Eventos Kafka

| Tópico | Produtor | Conteúdo |
| --- | --- | --- |
| `conciliacao.arquivo-recebido` | Lambda | Bucket, chave, ETag, tamanho e data de recebimento do arquivo |
| `conciliacao.resultado` | Job Spring Batch | Resultado por transação: `CONCILIADA`, `DIVERGENTE`, `NAO_ENCONTRADA` ou `AUSENTE_NO_ARQUIVO` (autorização sem linha no arquivo), sem PAN |
| `conciliacao.erro` | Job Spring Batch | Linhas inválidas, com número da linha e motivo |

Os eventos de resultado usam o **NSU** como chave: o milhão de resultados de um arquivo grande se espalha pelas partições, e os consumidores escalam com elas (com o id do arquivo como chave, tudo caía numa partição só). A entrega é *pelo menos uma vez*; consumidores usam `idArquivo + nsu + codigoAutorizacao` para descartar repetições.

---

## 📈 Desempenho

Teste com **1 milhão de linhas** gerado pelo `gerador-dados`, passando pelo fluxo completo
(S3 → Lambda → Kafka → Spring Batch → PostgreSQL e Kafka), tudo local em Docker. O serviço de
conciliação roda em container limitado a 768 MB. Para cada linha, o job consulta a autorização
(uma consulta por chunk), grava o resultado e publica uma mensagem no Kafka.

| Chunk | Duração do job | Linhas/s | Memória máx. |
| --- | --- | --- | --- |
| 1.000 (padrão) | 103 s | ~9.700 | 260 MB |
| 5.000 | 91 s | ~11.000 | 315 MB |

Em todas as rodadas o resultado conferiu com o gabarito do gerador: 924.889 conciliadas,
39.857 divergentes, 30.294 não encontradas, 25.000 ausentes no arquivo e 4.960 linhas inválidas.
O chunk é ajustável por `JOB_TAMANHO_CHUNK`.

**O que o teste de volume revelou e mudou no código:**

- **Conexões longas com o S3 caem:** a leitura de um arquivo grande leva mais de um minuto, e a
  conexão era derrubada no meio. Agora a leitura retoma do byte em que parou (`Range` + `If-Match`
  do ETag), sem perder nem repetir linhas.
- **Chave das mensagens:** com o id do arquivo como chave, o milhão de resultados caía numa única
  partição. Com o NSU, as mensagens se espalham e o job ficou ~8% mais rápido.
- **Contagem exata depois de um restart:** linhas inválidas de um chunk desfeito eram contadas duas
  vezes; agora ficam numa tabela com chave por arquivo e linha.
- **Limite de linhas inválidas percentual** (1% das linhas lidas), e não um número fixo que não escala.

> Números de um ambiente local (Windows 11, Docker Desktop com ~7,4 GB para os containers): servem
> para comparar configurações, não como capacidade de produção.

Para reproduzir: [`docs/comandos.md`](docs/comandos.md), etapa 4.

---

## 📄 Formato do arquivo de conciliação

Arquivo CSV com cabeçalho, nomeado no padrão `conciliacao_AAAAMMDD.csv`:

```csv
nsu;codigo_autorizacao;data_transacao;valor;pan_mascarado;mcc;parcelas
000123456;A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;5411;1
```

---

## 🔒 Segurança e PCI-DSS

Mesmo sendo um ambiente de estudo, o projeto segue práticas exigidas em sistemas de cartões:

- O número do cartão (**PAN**) nunca é armazenado nem logado completo; os dados de exemplo usam PAN mascarado.
- Segredos ficam fora do código e do repositório (`.env` no `.gitignore`).
- Valores monetários são tratados com `BigDecimal`, nunca com ponto flutuante.

---

## 🗺 Roadmap

- [x] **Fase 1:** estrutura Maven multi-módulo e infraestrutura com Docker Compose
- [x] **Fase 2:** Lambda Java publicando no Kafka a partir do upload no S3
- [x] **Fase 3:** consumer Kafka e job Spring Batch de conciliação
- [x] **Fase 4:** gerador de massa de dados e teste com 1 milhão de linhas
- [ ] **Fase 5:** documentação
- [ ] **Fase 6:** testes automatizados com JUnit 5 e Testcontainers
- [ ] **Fase 7:** observabilidade com OpenTelemetry, Prometheus e Grafana
- [ ] **Fase 8:** pipeline CI/CD com GitHub Actions e scan de segurança (Trivy)
- [ ] **Fase 9:** deploy na AWS com Terraform

---

## 👤 Autor

**Douglas Croti**
Engenheiro de software com mais de 15 anos de experiência em sistemas web críticos, seguros e escaláveis, com atuação em fintech, meios de pagamento e provedores de internet.

[![GitHub](https://img.shields.io/badge/GitHub-douglascroti-181717?logo=github)](https://github.com/douglascroti)
