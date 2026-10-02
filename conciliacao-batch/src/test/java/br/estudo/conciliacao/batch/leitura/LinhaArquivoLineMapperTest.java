package br.estudo.conciliacao.batch.leitura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LinhaArquivoLineMapperTest {

    private final LinhaArquivoLineMapper mapper = new LinhaArquivoLineMapper();

    @Test
    void converteLinhaValida() {
        LinhaArquivo item = mapper.mapLine("000123456;A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;0742;3", 2);

        assertThat(item.numeroLinha()).isEqualTo(2);
        assertThat(item.nsu()).isEqualTo("000123456");
        assertThat(item.dataTransacao()).isEqualTo(LocalDateTime.of(2026, 10, 1, 14, 32, 10));
        assertThat(item.valor()).isEqualByComparingTo(new BigDecimal("150.90"));
        assertThat(item.mcc()).isEqualTo("0742");   // texto: o zero à esquerda é preservado
        assertThat(item.parcelas()).isEqualTo(3);
    }

    @Test
    void toStringNaoExpoeOPan() {
        LinhaArquivo item = mapper.mapLine("000123456;A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;5411;1", 2);

        assertThat(item.toString()).doesNotContain("411111").doesNotContain("1111");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "000123456;A1B2C3;2026-10-01T14:32:10;0.00;411111******1111;5411;1    | valor deve ser maior que zero",
            "000123456;A1B2C3;2026-10-01T14:32:10;-5.00;411111******1111;5411;1   | valor deve ser maior que zero",
            "000123456;A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;5411;0  | parcelas deve ser no mínimo 1",
            ";A1B2C3;2026-10-01T14:32:10;150.90;411111******1111;5411;1           | nsu vazio",
            "000123456;;2026-10-01T14:32:10;150.90;411111******1111;5411;1        | codigo_autorizacao vazio",
    })
    void rejeitaRegraDeNegocio(String linha, String motivo) {
        assertThatThrownBy(() -> mapper.mapLine(linha.strip(), 3))
                .isInstanceOf(LinhaInvalidaException.class)
                .hasMessage(motivo);
    }

    @Test
    void rejeitaDataInvalida() {
        assertThatThrownBy(() -> mapper.mapLine("000123456;A1B2C3;2026-13-01T14:32:10;150.90;411111******1111;5411;1", 3))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void rejeitaQuantidadeErradaDeCampos() {
        assertThatThrownBy(() -> mapper.mapLine("000123456;A1B2C3;2026-10-01T14:32:10;150.90", 3))
                .hasMessageContaining("expected 7 actual 4");
    }
}
