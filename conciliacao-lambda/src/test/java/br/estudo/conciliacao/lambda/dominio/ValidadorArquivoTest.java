package br.estudo.conciliacao.lambda.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.estudo.conciliacao.eventos.LayoutArquivo;

class ValidadorArquivoTest {

    private final ValidadorArquivo validador = new ValidadorArquivo();

    @Test
    void extraiDataDoNomeValido() {
        assertThat(validador.extrairDataReferencia("conciliacao_20261001.csv"))
                .contains(LocalDate.of(2026, 10, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "conciliacao_2026101.csv",     // data com 7 dígitos
            "conciliacao_20261340.csv",    // mês 13
            "conciliacao_20260231.csv",    // 31 de fevereiro
            "conciliacao_20261001.txt",    // extensão errada
            "Conciliacao_20261001.csv",    // maiúscula
            "vendas_20261001.csv",
            ""
    })
    void rejeitaNomeInvalido(String nome) {
        assertThat(validador.extrairDataReferencia(nome)).isEmpty();
    }

    @Test
    void aceitaCabecalhoExato() {
        assertThat(validador.cabecalhoValido(LayoutArquivo.CABECALHO)).isTrue();
    }

    @Test
    void aceitaCabecalhoComBomECrlf() {
        assertThat(validador.cabecalhoValido("﻿" + LayoutArquivo.CABECALHO + "\r")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nsu,codigo_autorizacao,data_transacao,valor,pan_mascarado,mcc,parcelas", // separador errado
            "nsu;codigo_autorizacao;data_transacao;valor;pan;mcc;parcelas",           // coluna renomeada
            "000123456;A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;5411;1",    // sem cabeçalho
            ""
    })
    void rejeitaCabecalhoInvalido(String linha) {
        assertThat(validador.cabecalhoValido(linha)).isFalse();
    }
}
