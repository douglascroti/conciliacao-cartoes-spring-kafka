package br.estudo.conciliacao.batch.leitura;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stream que sobrevive a quedas de conexão: conta os bytes entregues e, se a leitura falhar, pede
 * a {@link Abertura} um stream novo a partir daquela posição e continua dali.
 *
 * <p>Um arquivo grande fica minutos com a mesma conexão HTTP aberta (o reader puxa as linhas
 * conforme o job avança), e conexões longas caem: o LocalStack derrubou a nossa depois de ~75 s,
 * e na AWS real downloads longos do S3 também podem ser interrompidos. Quem lê (o
 * {@code FlatFileItemReader}) não percebe a retomada: os bytes chegam na mesma sequência.
 */
public class LeituraRetomavel extends InputStream {

    /** Abre o conteúdo a partir de uma posição em bytes (no S3, um GET com {@code Range: bytes=N-}). */
    @FunctionalInterface
    public interface Abertura {
        InputStream abrir(long posicao) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(LeituraRetomavel.class);

    private final Abertura abertura;
    private final String descricao;
    private final int maximoTentativas;
    private final long esperaBaseMillis;

    private InputStream atual;
    private long posicao;
    private int falhasSeguidas;

    public LeituraRetomavel(Abertura abertura, String descricao, int maximoTentativas, long esperaBaseMillis) {
        this.abertura = abertura;
        this.descricao = descricao;
        this.maximoTentativas = maximoTentativas;
        this.esperaBaseMillis = esperaBaseMillis;
    }

    @Override
    public int read() throws IOException {
        byte[] um = new byte[1];
        int lidos = read(um, 0, 1);
        return lidos == -1 ? -1 : um[0] & 0xFF;
    }

    @Override
    public int read(byte[] destino, int inicio, int tamanho) throws IOException {
        while (true) {
            try {
                if (atual == null) {
                    atual = abertura.abrir(posicao);
                }
                int lidos = atual.read(destino, inicio, tamanho);
                if (lidos > 0) {
                    posicao += lidos;
                    falhasSeguidas = 0;
                }
                return lidos;
            } catch (IOException | RuntimeException e) {
                retomarDepoisDe(e);
            }
        }
    }

    private void retomarDepoisDe(Exception erro) throws IOException {
        fecharAtual();
        if (++falhasSeguidas > maximoTentativas) {
            throw new IOException("Leitura de " + descricao + " falhou " + maximoTentativas
                    + " vezes seguidas no byte " + posicao, erro);
        }
        log.warn("Leitura de {} interrompida no byte {} ({}); retomando, tentativa {} de {}",
                descricao, posicao, erro.toString(), falhasSeguidas, maximoTentativas);
        try {
            Thread.sleep(esperaBaseMillis * falhasSeguidas);   // espera crescente: 1x, 2x, 3x...
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Retomada da leitura interrompida", e);
        }
    }

    @Override
    public void close() {
        fecharAtual();
    }

    private void fecharAtual() {
        if (atual != null) {
            try {
                atual.close();
            } catch (IOException e) {
                log.debug("Falha ao fechar a conexão anterior", e);
            }
            atual = null;
        }
    }
}
