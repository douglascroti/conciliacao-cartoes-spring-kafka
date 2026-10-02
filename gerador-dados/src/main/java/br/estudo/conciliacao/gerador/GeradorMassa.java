package br.estudo.conciliacao.gerador;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.SplittableRandom;

import br.estudo.conciliacao.eventos.LayoutArquivo;

/**
 * Gera, em streaming, o arquivo da adquirente e as autorizações do emissor para uma data.
 *
 * <p>Cada linha é sorteada numa categoria (conciliada, divergente, não encontrada ou inválida) e a
 * autorização correspondente é escrita ao mesmo tempo no outro arquivo. Nada fica na memória:
 * 1 milhão de linhas usa o mesmo tanto de RAM que 10. Com a mesma semente, os arquivos saem
 * idênticos (útil para repetir uma medição).
 */
public class GeradorMassa {

    public static final String CABECALHO_AUTORIZACOES = "nsu;codigo_autorizacao;data_transacao;valor;pan_mascarado;mcc;parcelas";

    private static final String[] BINS = {"411111", "550000", "401200", "520000", "376411", "636368"};
    private static final String[] MCCS = {"5411", "5812", "5732", "5814", "5912", "4121", "5311", "0742"};
    private static final char[] ALFANUMERICO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter MES_DIA = DateTimeFormatter.ofPattern("MMdd");
    private static final int SEGUNDOS_NO_DIA = 86_400;

    private final Parametros p;
    private final SplittableRandom aleatorio;
    private final String prefixoNsu;

    // Contadores do gabarito.
    private long conciliadas;
    private long divergentesValor;
    private long divergentesParcelas;
    private long divergentesData;
    private long naoEncontradas;
    private long invalidas;
    private long autorizacoes;

    public GeradorMassa(Parametros parametros) {
        this.p = parametros;
        this.aleatorio = new SplittableRandom(parametros.semente());
        // NSU = MMdd + sequência de 8 dígitos (12 caracteres): massas de datas diferentes não colidem.
        this.prefixoNsu = parametros.data().format(MES_DIA);
    }

    public Gabarito gerar(Path arquivoAdquirente, Path arquivoAutorizacoes) {
        try (BufferedWriter adquirente = Files.newBufferedWriter(arquivoAdquirente, StandardCharsets.UTF_8);
             BufferedWriter emissor = Files.newBufferedWriter(arquivoAutorizacoes, StandardCharsets.UTF_8)) {
            escrever(adquirente, LayoutArquivo.CABECALHO);
            escrever(emissor, CABECALHO_AUTORIZACOES);

            for (long i = 1; i <= p.linhas(); i++) {
                gerarLinha(i, adquirente, emissor);
            }
            long ausentes = Math.round(p.linhas() * p.pctAusente() / 100.0);
            for (long i = 1; i <= ausentes; i++) {
                Transacao t = novaTransacao(p.linhas() + i, horario(p.linhas() + i, p.linhas() + ausentes));
                escrever(emissor, t.linha(LayoutArquivo.SEPARADOR));
                autorizacoes++;
            }
            return new Gabarito(p.data(), p.semente(), p.linhas(), conciliadas, divergentesValor, divergentesParcelas,
                    divergentesData, naoEncontradas, invalidas, ausentes, autorizacoes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void gerarLinha(long i, BufferedWriter adquirente, BufferedWriter emissor) throws IOException {
        Transacao venda = novaTransacao(i, horario(i, p.linhas()));
        double sorteio = aleatorio.nextDouble() * 100;
        double limite = p.pctInvalida();

        if (sorteio < limite) {
            // Linha que o job vai pular; sem autorização, para não virar AUSENTE_NO_ARQUIVO.
            escrever(adquirente, linhaInvalida(venda, i));
            invalidas++;
            return;
        }
        escrever(adquirente, venda.linha(LayoutArquivo.SEPARADOR));

        if (sorteio < (limite += p.pctNaoEncontrada())) {
            naoEncontradas++;
            return;
        }
        Transacao autorizacao;
        if (sorteio < limite + p.pctDivergente()) {
            autorizacao = divergente(venda);
        } else {
            // Relógios diferentes: o emissor registra até 5 s antes (o job compara só o dia).
            autorizacao = venda.comHorario(venda.dataHora().minusSeconds(aleatorio.nextInt(6)));
            conciliadas++;
        }
        escrever(emissor, autorizacao.linha(LayoutArquivo.SEPARADOR));
        autorizacoes++;
    }

    private Transacao divergente(Transacao venda) {
        return switch (aleatorio.nextInt(3)) {
            case 0 -> {
                divergentesValor++;
                yield venda.comValor(venda.valor().add(BigDecimal.valueOf(1 + aleatorio.nextInt(5000), 2)));
            }
            case 1 -> {
                divergentesParcelas++;
                yield venda.comParcelas(venda.parcelas() == 12 ? 1 : venda.parcelas() + 1);
            }
            default -> {
                // Autorizada no dia seguinte: fora da data do arquivo, então não aparece como ausente.
                divergentesData++;
                yield venda.comHorario(venda.dataHora().plusDays(1));
            }
        };
    }

    /** Quatro tipos de erro, um de cada vez: data impossível, valor negativo, colunas faltando, parcelas em texto. */
    private static String linhaInvalida(Transacao t, long i) {
        String s = LayoutArquivo.SEPARADOR;
        return switch ((int) (i % 4)) {
            case 0 -> t.linha(s).replace(t.dataHora().format(DATA_HORA), t.dataHora().format(DATA_HORA).replaceFirst("-\\d\\d-", "-13-"));
            case 1 -> String.join(s, t.nsu(), t.codigo(), t.dataHora().format(DATA_HORA), "-" + t.valor().toPlainString(),
                    t.pan(), t.mcc(), String.valueOf(t.parcelas()));
            case 2 -> String.join(s, t.nsu(), t.codigo(), t.dataHora().format(DATA_HORA), t.valor().toPlainString());
            default -> String.join(s, t.nsu(), t.codigo(), t.dataHora().format(DATA_HORA), t.valor().toPlainString(),
                    t.pan(), t.mcc(), "dez");
        };
    }

    private Transacao novaTransacao(long sequencia, LocalDateTime dataHora) {
        String nsu = prefixoNsu + String.format("%08d", sequencia);
        BigDecimal valor = BigDecimal.valueOf(500 + aleatorio.nextInt(199_501), 2);   // R$ 5,00 a R$ 2.000,00
        int parcelas = aleatorio.nextInt(10) < 7 ? 1 : 2 + aleatorio.nextInt(11);       // 70% à vista
        String pan = BINS[aleatorio.nextInt(BINS.length)] + "******" + String.format("%04d", aleatorio.nextInt(10_000));
        return new Transacao(nsu, codigoAutorizacao(), dataHora, valor, pan, MCCS[aleatorio.nextInt(MCCS.length)], parcelas);
    }

    /**
     * Horários crescentes ao longo do dia, entre 00:01 e 23:59 (com folga para os 5 s de diferença
     * entre os relógios não mudarem o dia).
     */
    private LocalDateTime horario(long i, long total) {
        long segundo = 60 + (i - 1) * (SEGUNDOS_NO_DIA - 120L) / Math.max(1, total);
        return p.data().atStartOfDay().plusSeconds(segundo);
    }

    private String codigoAutorizacao() {
        char[] codigo = new char[6];
        for (int i = 0; i < codigo.length; i++) {
            codigo[i] = ALFANUMERICO[aleatorio.nextInt(ALFANUMERICO.length)];
        }
        return new String(codigo);
    }

    private static void escrever(BufferedWriter saida, String linha) throws IOException {
        saida.write(linha);
        saida.write('\n');
    }

    record Transacao(String nsu, String codigo, LocalDateTime dataHora, BigDecimal valor, String pan, String mcc,
                     int parcelas) {

        String linha(String s) {
            return String.join(s, nsu, codigo, dataHora.format(DATA_HORA), valor.toPlainString(), pan, mcc,
                    String.valueOf(parcelas));
        }

        Transacao comHorario(LocalDateTime novo) {
            return new Transacao(nsu, codigo, novo, valor, pan, mcc, parcelas);
        }

        Transacao comValor(BigDecimal novo) {
            return new Transacao(nsu, codigo, dataHora, novo, pan, mcc, parcelas);
        }

        Transacao comParcelas(int novo) {
            return new Transacao(nsu, codigo, dataHora, valor, pan, mcc, novo);
        }
    }
}
