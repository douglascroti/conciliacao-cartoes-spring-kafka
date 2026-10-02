package br.estudo.conciliacao.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

class DescricaoErroTest {

    @Test
    void mostraACadeiaAteACausaRaiz() {
        var erro = new RuntimeException("Unable to process chunk",
                new IllegalStateException("Falha ao publicar em conciliacao.resultado", new TimeoutException()));

        assertThat(DescricaoErro.descrever(erro)).isEqualTo(
                "RuntimeException: Unable to process chunk <- IllegalStateException: Falha ao publicar em conciliacao.resultado <- TimeoutException");
    }

    @Test
    void naoExpoeALinhaDoArquivo() {
        String linha = "000123456;A1B2C3;2026-10-01T14:32:10;-5.00;411111******1111;5411;1";
        var erro = new RuntimeException("Skip limit exceeded",
                new FlatFileParseException("Parsing error at line: 7, input=[" + linha + "]", linha, 7));

        assertThat(DescricaoErro.descrever(erro))
                .endsWith("FlatFileParseException na linha 7")
                .doesNotContain("411111");
    }
}
