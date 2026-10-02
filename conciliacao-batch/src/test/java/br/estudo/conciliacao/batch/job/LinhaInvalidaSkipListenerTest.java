package br.estudo.conciliacao.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

import br.estudo.conciliacao.batch.leitura.LinhaInvalidaException;

class LinhaInvalidaSkipListenerTest {

    private static final String LINHA = "000123456;A1B2C3;2026-10-01T14:32:10;-5.00;411111******1111;5411;1";

    @Test
    void motivoDeRegraDeNegocioEhAMensagemDaRegra() {
        var falha = new FlatFileParseException("Parsing error", new LinhaInvalidaException("valor deve ser maior que zero"), LINHA, 3);

        assertThat(LinhaInvalidaSkipListener.motivo(falha)).isEqualTo("valor deve ser maior que zero");
    }

    @Test
    void motivoNuncaIncluiOConteudoDaLinha() {
        // A mensagem da FlatFileParseException do Spring Batch traz a linha inteira (input=[...]).
        var falha = new FlatFileParseException("Parsing error at line: 3, input=[" + LINHA + "]",
                new DateTimeParseException("Text '2026-13-01T14:32:10' could not be parsed", "2026-13-01T14:32:10", 5), LINHA, 3);

        assertThat(LinhaInvalidaSkipListener.motivo(falha))
                .startsWith("DateTimeParseException")
                .doesNotContain("411111")
                .doesNotContain(LINHA);
    }
}
