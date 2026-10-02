package br.estudo.conciliacao.batch.conciliacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.eventos.CampoDivergente;
import br.estudo.conciliacao.eventos.StatusConciliacao;

class ConciliadorTest {

    private static final LocalDateTime DATA = LocalDateTime.of(2026, 10, 1, 14, 32, 10);

    private final Conciliador conciliador = new Conciliador();

    @Test
    void camposIguaisConcilia() {
        var resultado = conciliador.conciliar(linha("150.90", 1, DATA), autorizacao("150.90", 1, DATA));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.CONCILIADA);
        assertThat(resultado.camposDivergentes()).isEmpty();
        assertThat(resultado.idTransacaoAutorizada()).isEqualTo(7L);
    }

    @Test
    void valorComOutraEscalaConcilia() {
        // 150.9 e 150.90 são o mesmo valor; BigDecimal.equals diria que não (escala diferente).
        var resultado = conciliador.conciliar(linha("150.9", 1, DATA), autorizacao("150.90", 1, DATA));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.CONCILIADA);
    }

    @Test
    void horarioDiferenteNoMesmoDiaConcilia() {
        var resultado = conciliador.conciliar(linha("150.90", 1, DATA), autorizacao("150.90", 1, DATA.plusSeconds(40)));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.CONCILIADA);
    }

    @Test
    void valorDiferenteDiverge() {
        var resultado = conciliador.conciliar(linha("150.90", 1, DATA), autorizacao("150.00", 1, DATA));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.DIVERGENTE);
        assertThat(resultado.camposDivergentes()).containsExactly(CampoDivergente.VALOR);
        assertThat(resultado.valorArquivo()).isEqualByComparingTo("150.90");
        assertThat(resultado.valorAutorizado()).isEqualByComparingTo("150.00");
    }

    @Test
    void variosCamposDiferentesSaoTodosListados() {
        var resultado = conciliador.conciliar(linha("150.90", 10, DATA), autorizacao("99.00", 1, DATA.plusDays(1)));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.DIVERGENTE);
        assertThat(resultado.camposDivergentes())
                .containsExactly(CampoDivergente.VALOR, CampoDivergente.PARCELAS, CampoDivergente.DATA);
    }

    @Test
    void semAutorizacaoNaoEncontrada() {
        var resultado = conciliador.conciliar(linha("150.90", 1, DATA), null);

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.NAO_ENCONTRADA);
        assertThat(resultado.valorAutorizado()).isNull();
        assertThat(resultado.idTransacaoAutorizada()).isNull();
    }

    @Test
    void loteUsaAChaveCompletaNsuECodigo() {
        // Mesmo NSU com outro código de autorização não é a mesma transação.
        var outra = new TransacaoAutorizada(8, "000123456", "ZZZZZZ", DATA, new BigDecimal("150.90"), 1);

        var resultados = conciliador.conciliar(List.of(linha("150.90", 1, DATA)), Map.of(outra.chave(), outra));

        assertThat(resultados).singleElement()
                .extracting(ResultadoConciliacao::status).isEqualTo(StatusConciliacao.NAO_ENCONTRADA);
    }

    @Test
    void ausenteNaoTemLinhaNemValorDoArquivo() {
        var resultado = conciliador.ausente(autorizacao("89.00", 1, DATA));

        assertThat(resultado.status()).isEqualTo(StatusConciliacao.AUSENTE_NO_ARQUIVO);
        assertThat(resultado.numeroLinha()).isNull();
        assertThat(resultado.valorArquivo()).isNull();
        assertThat(resultado.valorAutorizado()).isEqualByComparingTo("89.00");
    }

    private static LinhaArquivo linha(String valor, int parcelas, LocalDateTime data) {
        return new LinhaArquivo(2, "000123456", "A1B2C3", data, new BigDecimal(valor), "411111******1111", "5411", parcelas);
    }

    private static TransacaoAutorizada autorizacao(String valor, int parcelas, LocalDateTime data) {
        return new TransacaoAutorizada(7, "000123456", "A1B2C3", data, new BigDecimal(valor), parcelas);
    }
}
