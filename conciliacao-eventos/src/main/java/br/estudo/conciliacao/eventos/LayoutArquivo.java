package br.estudo.conciliacao.eventos;

import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Layout do arquivo de conciliação enviado pela adquirente. Compartilhado entre a Lambda
 * (validação rápida) e o job Batch (leitura das linhas).
 */
public final class LayoutArquivo {

    public static final String SEPARADOR = ";";

    public static final String CABECALHO =
            "nsu;codigo_autorizacao;data_transacao;valor;pan_mascarado;mcc;parcelas";

    /** Nome esperado: {@code conciliacao_AAAAMMDD.csv}; o grupo 1 é a data de referência. */
    public static final Pattern PADRAO_NOME = Pattern.compile("^conciliacao_(\\d{8})\\.csv$");

    /** Formato da data no nome do arquivo (AAAAMMDD), com validação estrita do calendário. */
    public static final DateTimeFormatter FORMATO_DATA_NOME = DateTimeFormatter.BASIC_ISO_DATE;

    private LayoutArquivo() {
    }
}
