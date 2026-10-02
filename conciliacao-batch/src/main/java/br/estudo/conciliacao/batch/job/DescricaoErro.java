package br.estudo.conciliacao.batch.job;

import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Resume uma exceção com a cadeia de causas, para o log e para {@code arquivo_recebido.mensagem_erro}.
 *
 * <p>O {@code toString} da exceção do topo costuma ser genérico ("Unable to process chunk"); a
 * causa útil (ex.: timeout do Kafka) fica algumas camadas abaixo. A {@link FlatFileParseException}
 * é resumida ao número da linha, porque a mensagem dela traz a linha inteira, com o PAN mascarado.
 */
final class DescricaoErro {

    private static final int MAXIMO_CAUSAS = 5;

    private DescricaoErro() {
    }

    static String descrever(Throwable erro) {
        List<String> partes = new ArrayList<>();
        for (Throwable atual = erro; atual != null && partes.size() < MAXIMO_CAUSAS; atual = atual.getCause()) {
            partes.add(atual instanceof FlatFileParseException falha
                    ? "FlatFileParseException na linha " + falha.getLineNumber()
                    : atual.getClass().getSimpleName() + (atual.getMessage() == null ? "" : ": " + atual.getMessage()));
            if (atual.getCause() == atual) {
                break;
            }
        }
        return String.join(" <- ", partes);
    }
}
