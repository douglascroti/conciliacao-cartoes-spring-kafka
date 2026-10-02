package br.estudo.conciliacao.gerador;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parâmetros da geração, lidos de {@code --nome valor} na linha de comando.
 *
 * <p>Os percentuais são do total de linhas do arquivo, exceto {@code pctAusente}, que é a
 * quantidade de autorizações extras (fora do arquivo) em relação ao total de linhas.
 */
public record Parametros(
        long linhas,
        LocalDate data,
        long semente,
        Path saida,
        double pctDivergente,
        double pctNaoEncontrada,
        double pctInvalida,
        double pctAusente) {

    private static final Set<String> NOMES = Set.of("linhas", "data", "semente", "saida",
            "pct-divergente", "pct-nao-encontrada", "pct-invalida", "pct-ausente");

    public Parametros {
        if (linhas < 1 || linhas > 99_999_999) {
            throw new IllegalArgumentException("--linhas deve estar entre 1 e 99.999.999");
        }
        if (pctDivergente + pctNaoEncontrada + pctInvalida > 100) {
            throw new IllegalArgumentException("a soma de divergente, não encontrada e inválida passa de 100%");
        }
    }

    public static Parametros ler(String[] args) {
        Map<String, String> valores = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String nome = args[i].replaceFirst("^--", "");
            if (!NOMES.contains(nome) || i + 1 >= args.length) {
                throw new IllegalArgumentException("argumento inválido: " + args[i] + "\n" + USO);
            }
            valores.put(nome, args[i + 1]);
        }
        return new Parametros(
                Long.parseLong(valores.getOrDefault("linhas", "1000000")),
                LocalDate.parse(valores.getOrDefault("data", "2026-10-15")),
                Long.parseLong(valores.getOrDefault("semente", "42")),
                Path.of(valores.getOrDefault("saida", "massa")),
                Double.parseDouble(valores.getOrDefault("pct-divergente", "4")),
                Double.parseDouble(valores.getOrDefault("pct-nao-encontrada", "3")),
                Double.parseDouble(valores.getOrDefault("pct-invalida", "0.5")),
                Double.parseDouble(valores.getOrDefault("pct-ausente", "2.5")));
    }

    static final String USO = """
            uso: java -jar gerador-dados.jar [--linhas 1000000] [--data 2026-10-15] [--semente 42] [--saida massa]
                     [--pct-divergente 4] [--pct-nao-encontrada 3] [--pct-invalida 0.5] [--pct-ausente 2.5]""";
}
