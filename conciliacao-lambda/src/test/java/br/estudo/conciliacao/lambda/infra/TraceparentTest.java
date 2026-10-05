package br.estudo.conciliacao.lambda.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceparentTest {

    @Test
    void valorSegueOFormatoW3C() {
        var rastreio = Traceparent.novo();

        assertThat(rastreio.valor()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(rastreio.valor()).contains(rastreio.traceId()).contains(rastreio.spanId());
    }

    @Test
    void cadaPublicacaoGeraUmTraceNovo() {
        assertThat(Traceparent.novo().traceId()).isNotEqualTo(Traceparent.novo().traceId());
    }
}
