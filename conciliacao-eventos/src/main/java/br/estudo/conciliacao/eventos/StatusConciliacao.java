package br.estudo.conciliacao.eventos;

/** Resultado da conciliação de uma transação. */
public enum StatusConciliacao {

    /** Linha do arquivo com autorização correspondente e valor, parcelas e dia iguais. */
    CONCILIADA,

    /** Autorização encontrada, mas com algum campo diferente (ver {@link CampoDivergente}). */
    DIVERGENTE,

    /** Linha do arquivo sem autorização correspondente (venda informada que o emissor não aprovou). */
    NAO_ENCONTRADA,

    /** Autorização do dia que não veio no arquivo (venda aprovada que a adquirente não informou). */
    AUSENTE_NO_ARQUIVO
}
