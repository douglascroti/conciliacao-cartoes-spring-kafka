package br.estudo.conciliacao.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.dao.DataAccessResourceFailureException;

class LimitePercentualLinhasInvalidasTest {

    // 1% das linhas lidas, com tolerância mínima de 100.
    private final LimitePercentualLinhasInvalidas politica = new LimitePercentualLinhasInvalidas(1.0, 100);

    @Test
    void arquivoPequenoUsaOMinimo() {
        assertThat(politica.shouldSkip(erroNaLinha(51), 99)).isTrue();
        assertThatThrownBy(() -> politica.shouldSkip(erroNaLinha(52), 100))
                .isInstanceOf(SkipLimitExceededException.class);
    }

    @Test
    void arquivoGrandeEscalaComAsLinhasLidas() {
        // Na linha 500.001 já foram lidas 500.000 linhas: 1% = 5.000 permitidas.
        assertThat(politica.shouldSkip(erroNaLinha(500_001), 4_999)).isTrue();
        assertThatThrownBy(() -> politica.shouldSkip(erroNaLinha(500_001), 5_000))
                .isInstanceOf(SkipLimitExceededException.class);
    }

    @Test
    void blocoCorrompidoNoInicioDerrubaCedo() {
        // 150 linhas ruins seguidas logo no começo: acima do mínimo e de 1% do que foi lido.
        assertThatThrownBy(() -> politica.shouldSkip(erroNaLinha(152), 150))
                .isInstanceOf(SkipLimitExceededException.class);
    }

    @Test
    void erroQueNaoEhDeLeituraNaoEhPulado() {
        assertThat(politica.shouldSkip(new DataAccessResourceFailureException("banco fora"), 0)).isFalse();
    }

    private static FlatFileParseException erroNaLinha(int numeroLinha) {
        return new FlatFileParseException("Parsing error", "linha", numeroLinha);
    }
}
