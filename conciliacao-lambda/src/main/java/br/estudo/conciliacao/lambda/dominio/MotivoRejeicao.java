package br.estudo.conciliacao.lambda.dominio;

/** Por que um arquivo foi movido para {@code rejeitados/}. Gravado como metadado do objeto no S3. */
public enum MotivoRejeicao {
    NOME_INVALIDO,
    ARQUIVO_VAZIO,
    CABECALHO_INVALIDO
}
