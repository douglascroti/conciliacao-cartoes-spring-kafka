package br.estudo.conciliacao.eventos;

/** Nomes dos tópicos Kafka da conciliação (criados por infra/kafka/criar-topicos.sh). */
public final class Topicos {

    public static final String ARQUIVO_RECEBIDO = "conciliacao.arquivo-recebido";
    public static final String RESULTADO = "conciliacao.resultado";
    public static final String ERRO = "conciliacao.erro";

    private Topicos() {
    }
}
