package br.estudo.conciliacao.batch.leitura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LeituraRetomavelTest {

    private static final byte[] CONTEUDO = "linha 1\nlinha 2\nlinha 3\nlinha 4\n".repeat(1000).getBytes(StandardCharsets.UTF_8);

    @Test
    void retomaDoPontoOndeAConexaoCaiu() throws IOException {
        List<Long> aberturas = new ArrayList<>();
        // As duas primeiras conexões caem depois de 10.000 bytes; a terceira vai até o fim.
        var leitura = new LeituraRetomavel(posicao -> {
            aberturas.add(posicao);
            return aberturas.size() <= 2 ? cairDepoisDe(posicao, 10_000) : desde(posicao);
        }, "teste", 5, 0);

        byte[] lido = leitura.readAllBytes();

        assertThat(lido).isEqualTo(CONTEUDO);   // nenhum byte perdido nem repetido
        assertThat(aberturas).containsExactly(0L, 10_000L, 20_000L);
    }

    @Test
    void desisteDepoisDoMaximoDeFalhasSeguidas() {
        var leitura = new LeituraRetomavel(posicao -> cairDepoisDe(posicao, 0), "teste", 3, 0);

        assertThatThrownBy(leitura::readAllBytes)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("falhou 3 vezes seguidas no byte 0")
                .hasCauseInstanceOf(SocketException.class);
    }

    private static InputStream desde(long posicao) {
        return new ByteArrayInputStream(CONTEUDO, (int) posicao, CONTEUDO.length - (int) posicao);
    }

    /** Stream que entrega {@code bytes} bytes e depois falha como uma conexão derrubada. */
    private static InputStream cairDepoisDe(long posicao, int bytes) {
        return new FilterInputStream(desde(posicao)) {
            private int entregues;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (entregues >= bytes) {
                    throw new SocketException("Connection reset");
                }
                int n = super.read(b, off, Math.min(len, bytes - entregues));
                entregues += n;
                return n;
            }
        };
    }
}
