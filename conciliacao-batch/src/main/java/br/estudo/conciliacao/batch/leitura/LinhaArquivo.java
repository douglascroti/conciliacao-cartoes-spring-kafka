package br.estudo.conciliacao.batch.leitura;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Uma linha do arquivo da adquirente, já convertida.
 *
 * <p>{@code valor} é {@link BigDecimal}: dinheiro nunca em {@code double} (0.1 + 0.2 != 0.3,
 * o mesmo problema do {@code Number} no JS). {@code mcc} é texto porque tem zeros à esquerda.
 *
 * @param numeroLinha número da linha no arquivo (o cabeçalho é a linha 1), usado nos erros
 */
public record LinhaArquivo(
        int numeroLinha,
        String nsu,
        String codigoAutorizacao,
        LocalDateTime dataTransacao,
        BigDecimal valor,
        String panMascarado,
        String mcc,
        int parcelas) {

    /** Sem o PAN: o toString padrão do record incluiria todos os campos em qualquer log. */
    @Override
    public String toString() {
        return "LinhaArquivo[linha=" + numeroLinha + ", nsu=" + nsu + ", valor=" + valor + "]";
    }
}
