package br.estudo.conciliacao.gerador;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * Linha de comando do gerador. Exemplo:
 * <pre>
 * java -jar gerador-dados/target/gerador-dados-0.1.0-SNAPSHOT.jar --linhas 1000000 --data 2026-10-15
 * </pre>
 * Saída em {@code massa/}: {@code conciliacao_20261015.csv} (adquirente),
 * {@code transacoes_autorizadas_20261015.csv} (emissor) e {@code gabarito_20261015.json}.
 */
public final class GeradorDados {

    private GeradorDados() {
    }

    public static void main(String[] args) throws IOException {
        Parametros p;
        try {
            p = Parametros.ler(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        Files.createDirectories(p.saida());
        String dia = p.data().format(DateTimeFormatter.BASIC_ISO_DATE);
        Path adquirente = p.saida().resolve("conciliacao_" + dia + ".csv");
        Path autorizacoes = p.saida().resolve("transacoes_autorizadas_" + dia + ".csv");
        Path gabarito = p.saida().resolve("gabarito_" + dia + ".json");

        long inicio = System.nanoTime();
        Gabarito resultado = new GeradorMassa(p).gerar(adquirente, autorizacoes);
        Files.writeString(gabarito, resultado.json(), StandardCharsets.UTF_8);
        Duration duracao = Duration.ofNanos(System.nanoTime() - inicio);

        System.out.printf("Gerado em %d ms (semente %d):%n", duracao.toMillis(), p.semente());
        System.out.printf("  %s  %,d linhas (%,d inválidas)  %s%n", adquirente, resultado.linhasArquivo(),
                resultado.invalidas(), tamanho(adquirente));
        System.out.printf("  %s  %,d autorizações (%,d ausentes no arquivo)  %s%n", autorizacoes,
                resultado.autorizacoes(), resultado.ausentes(), tamanho(autorizacoes));
        System.out.printf("  %s%n%s", gabarito, resultado.json());
    }

    private static String tamanho(Path arquivo) throws IOException {
        return String.format("%.1f MB", Files.size(arquivo) / 1_048_576.0);
    }
}
