package br.estudo.conciliacao.batch.job;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Decide se uma linha inválida pode ser pulada: sim, enquanto as inválidas não passarem de um
 * percentual das linhas lidas até ali, com um mínimo de tolerância.
 *
 * <p>Um limite fixo não escala: 1.000 linhas ruins é muito num arquivo de 5 mil e pouco num de
 * 1 milhão. O percentual é calculado sobre as linhas lidas até o erro (o total só se conhece no
 * fim da leitura em streaming); o número da linha vem da própria {@link FlatFileParseException}.
 * O mínimo evita que as primeiras linhas ruins de um arquivo pequeno derrubem o job.
 */
public class LimitePercentualLinhasInvalidas implements SkipPolicy {

    private final double percentualMaximo;
    private final long minimo;

    public LimitePercentualLinhasInvalidas(double percentualMaximo, long minimo) {
        this.percentualMaximo = percentualMaximo;
        this.minimo = minimo;
    }

    @Override
    public boolean shouldSkip(Throwable erro, long skipsAteAgora) {
        if (!(erro instanceof FlatFileParseException falha)) {
            return false;   // só erros de leitura/layout; banco ou Kafka continuam derrubando o chunk
        }
        long permitido = limite(falha.getLineNumber() - 1L);   // linha 1 é o cabeçalho
        if (skipsAteAgora < permitido) {
            return true;
        }
        throw new SkipLimitExceededException(permitido, erro);
    }

    long limite(long linhasLidas) {
        return Math.max(minimo, (long) Math.floor(linhasLidas * percentualMaximo / 100.0));
    }
}
