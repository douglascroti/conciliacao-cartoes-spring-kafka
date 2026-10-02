package br.estudo.conciliacao.gerador;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import br.estudo.conciliacao.eventos.LayoutArquivo;

class GeradorMassaTest {

    private static final LocalDate DATA = LocalDate.of(2026, 10, 15);

    @TempDir
    Path pasta;

    @Test
    void gabaritoFechaComOsArquivos() throws IOException {
        Gabarito g = gerar(20_000, 42, "a");

        assertThat(g.conciliadas() + g.divergentes() + g.naoEncontradas() + g.invalidas()).isEqualTo(20_000);
        assertThat(g.autorizacoes()).isEqualTo(g.conciliadas() + g.divergentes() + g.ausentes());
        assertThat(g.ausentes()).isEqualTo(500);   // 2,5% de 20.000

        List<String> adquirente = Files.readAllLines(pasta.resolve("a.csv"));
        List<String> autorizacoes = Files.readAllLines(pasta.resolve("a-aut.csv"));
        assertThat(adquirente).hasSize(20_001).first().isEqualTo(LayoutArquivo.CABECALHO);
        assertThat(autorizacoes).hasSize((int) g.autorizacoes() + 1);
    }

    @Test
    void distribuicaoFicaPertoDosPercentuais() {
        Gabarito g = gerar(100_000, 7, "b");

        // Conciliada é o restante: 100 - 4 (divergente) - 3 (não encontrada) - 0,5 (inválida) = 92,5%.
        assertThat(g.conciliadas() / 1000.0).isBetween(92.0, 93.0);
        assertThat(g.divergentes() / 1000.0).isBetween(3.5, 4.5);
        assertThat(g.naoEncontradas() / 1000.0).isBetween(2.5, 3.5);
        assertThat(g.invalidas() / 1000.0).isBetween(0.3, 0.7);
        assertThat(g.divergentesValor()).isPositive();
        assertThat(g.divergentesParcelas()).isPositive();
        assertThat(g.divergentesData()).isPositive();
    }

    @Test
    void mesmaSementeGeraArquivosIdenticos() throws IOException {
        gerar(5_000, 99, "x");
        gerar(5_000, 99, "y");

        assertThat(Files.mismatch(pasta.resolve("x.csv"), pasta.resolve("y.csv"))).isEqualTo(-1L);
        assertThat(Files.mismatch(pasta.resolve("x-aut.csv"), pasta.resolve("y-aut.csv"))).isEqualTo(-1L);
    }

    @Test
    void chaveDasAutorizacoesEhUnica() throws IOException {
        gerar(20_000, 3, "c");

        Set<String> chaves = new HashSet<>();
        List<String> linhas = Files.readAllLines(pasta.resolve("c-aut.csv"));
        for (String linha : linhas.subList(1, linhas.size())) {
            String[] campos = linha.split(";");
            assertThat(chaves.add(campos[0] + "|" + campos[1])).as("chave repetida: %s", linha).isTrue();
            assertThat(campos[0]).hasSize(12).startsWith("1015");
        }
    }

    @Test
    void linhasInvalidasTemOsQuatroTiposDeErro() throws IOException {
        gerar(20_000, 11, "d");

        List<String> linhas = Files.readAllLines(pasta.resolve("d.csv"));
        assertThat(linhas).anyMatch(l -> l.contains("-13-"))
                .anyMatch(l -> l.contains(";-"))
                .anyMatch(l -> l.split(";").length == 4)
                .anyMatch(l -> l.endsWith(";dez"));
    }

    private Gabarito gerar(long linhas, long semente, String nome) {
        var p = new Parametros(linhas, DATA, semente, pasta, 4, 3, 0.5, 2.5);
        return new GeradorMassa(p).gerar(pasta.resolve(nome + ".csv"), pasta.resolve(nome + "-aut.csv"));
    }
}
