package br.estudo.conciliacao.batch.leitura;

/** Linha que pôde ser lida, mas viola uma regra do layout (ex.: valor negativo). */
public class LinhaInvalidaException extends RuntimeException {

    public LinhaInvalidaException(String motivo) {
        super(motivo);
    }
}
