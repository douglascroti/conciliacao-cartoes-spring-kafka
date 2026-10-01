package br.estudo.conciliacao.lambda.dominio;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;

import org.springframework.stereotype.Component;

import br.estudo.conciliacao.eventos.LayoutArquivo;

/** Validações rápidas do arquivo, sem I/O (fáceis de testar unitariamente). */
@Component
public class ValidadorArquivo {

    private static final char BOM = '﻿';

    /**
     * Extrai a data de referência do nome {@code conciliacao_AAAAMMDD.csv}.
     * Vazio se o nome está fora do padrão ou a data não existe no calendário (ex.: 20261340).
     */
    public Optional<LocalDate> extrairDataReferencia(String nomeArquivo) {
        Matcher matcher = LayoutArquivo.PADRAO_NOME.matcher(nomeArquivo);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1), LayoutArquivo.FORMATO_DATA_NOME));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Confere o cabeçalho, tolerando BOM do Excel e final de linha Windows (\r\n). */
    public boolean cabecalhoValido(String primeiraLinha) {
        String linha = primeiraLinha;
        if (!linha.isEmpty() && linha.charAt(0) == BOM) {
            linha = linha.substring(1);
        }
        return LayoutArquivo.CABECALHO.equals(linha.strip());
    }
}
