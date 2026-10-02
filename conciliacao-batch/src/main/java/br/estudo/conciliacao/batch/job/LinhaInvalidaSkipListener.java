package br.estudo.conciliacao.batch.job;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

import br.estudo.conciliacao.batch.leitura.LinhaArquivo;
import br.estudo.conciliacao.batch.leitura.LinhaInvalidaException;
import br.estudo.conciliacao.batch.publicacao.PublicadorConciliacao;
import br.estudo.conciliacao.eventos.ErroLinhaEvento;

/**
 * Chamado pelo Spring Batch para cada linha pulada na leitura: registra no log e publica em
 * {@code conciliacao.erro}.
 *
 * <p>O motivo vem da causa da exceção, não da {@code FlatFileParseException} em si: a mensagem
 * dela inclui a linha inteira ({@code input=[...]}), com o PAN mascarado.
 */
public class LinhaInvalidaSkipListener implements SkipListener<LinhaArquivo, LinhaArquivo> {

    private static final Logger log = LoggerFactory.getLogger(LinhaInvalidaSkipListener.class);

    private static final int TAMANHO_MAXIMO_MOTIVO = 300;

    private final PublicadorConciliacao publicador;
    private final UUID idArquivo;
    private final String nomeArquivo;

    public LinhaInvalidaSkipListener(PublicadorConciliacao publicador, UUID idArquivo, String nomeArquivo) {
        this.publicador = publicador;
        this.idArquivo = idArquivo;
        this.nomeArquivo = nomeArquivo;
    }

    @Override
    public void onSkipInRead(Throwable erro) {
        if (!(erro instanceof FlatFileParseException falha)) {
            return;
        }
        String motivo = motivo(falha);
        log.warn("Linha {} do arquivo {} inválida, pulada: {}", falha.getLineNumber(), nomeArquivo, motivo);
        publicador.publicarErro(new ErroLinhaEvento(idArquivo, nomeArquivo, falha.getLineNumber(), motivo, Instant.now()));
    }

    static String motivo(FlatFileParseException falha) {
        Throwable causa = falha.getCause();
        String motivo = causa == null
                ? "linha fora do layout"
                : causa instanceof LinhaInvalidaException
                        ? causa.getMessage()
                        : causa.getClass().getSimpleName() + ": " + causa.getMessage();
        return motivo.length() > TAMANHO_MAXIMO_MOTIVO ? motivo.substring(0, TAMANHO_MAXIMO_MOTIVO) : motivo;
    }
}
