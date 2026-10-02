package br.estudo.conciliacao.batch.leitura;

import java.time.LocalDateTime;

import org.springframework.batch.infrastructure.item.file.LineMapper;
import org.springframework.batch.infrastructure.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.infrastructure.item.file.transform.FieldSet;

import br.estudo.conciliacao.eventos.LayoutArquivo;

/**
 * Converte uma linha de texto em {@link LinhaArquivo}. O {@code FlatFileItemReader} informa o
 * número da linha, que fica no item para os erros apontarem onde está o problema.
 *
 * <p>Qualquer falha de conversão (campo faltando, data ou valor inválido) vira exceção; o
 * reader a embrulha em {@code FlatFileParseException} com a linha e o número dela.
 */
public class LinhaArquivoLineMapper implements LineMapper<LinhaArquivo> {

    private final DelimitedLineTokenizer tokenizer;

    public LinhaArquivoLineMapper() {
        tokenizer = new DelimitedLineTokenizer(LayoutArquivo.SEPARADOR);
        // Os nomes vêm do mesmo cabeçalho que a Lambda valida: layout definido num lugar só.
        tokenizer.setNames(LayoutArquivo.CABECALHO.split(LayoutArquivo.SEPARADOR));
    }

    @Override
    public LinhaArquivo mapLine(String linha, int numeroLinha) {
        FieldSet campos = tokenizer.tokenize(linha);
        return new LinhaArquivo(
                numeroLinha,
                campos.readString("nsu"),
                campos.readString("codigo_autorizacao"),
                LocalDateTime.parse(campos.readString("data_transacao")),
                campos.readBigDecimal("valor"),
                campos.readString("pan_mascarado"),
                campos.readString("mcc"),
                campos.readInt("parcelas"));
    }
}
