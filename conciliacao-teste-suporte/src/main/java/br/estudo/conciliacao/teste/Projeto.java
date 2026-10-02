package br.estudo.conciliacao.teste;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Localiza arquivos do repositório (migrations, exemplos, .env) a partir do módulo em teste. */
public final class Projeto {

    private Projeto() {
    }

    /** Raiz do repositório: o primeiro diretório acima do atual que tem {@code infra/postgres/migrations}. */
    public static Path raiz() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infra/postgres/migrations"))) {
                return dir;
            }
        }
        throw new IllegalStateException("Raiz do repositório não encontrada a partir de " + Path.of("").toAbsolutePath());
    }

    /**
     * Token do LocalStack: variável de ambiente {@code LOCALSTACK_AUTH_TOKEN} (CI) ou, se ela não
     * existir, a linha {@code LOCALSTACK_AUTH_TOKEN=} do {@code .env} da raiz (máquina de desenvolvimento,
     * onde o Maven não lê o .env sozinho).
     */
    public static Optional<String> tokenLocalStack() {
        String doAmbiente = System.getenv("LOCALSTACK_AUTH_TOKEN");
        if (doAmbiente != null && !doAmbiente.isBlank()) {
            return Optional.of(doAmbiente.strip());
        }
        Path env = raiz().resolve(".env");
        if (!Files.exists(env)) {
            return Optional.empty();
        }
        try {
            return Files.readAllLines(env).stream()
                    .map(String::strip)
                    .filter(l -> l.startsWith("LOCALSTACK_AUTH_TOKEN="))
                    .map(l -> l.substring("LOCALSTACK_AUTH_TOKEN=".length()).strip())
                    .filter(t -> !t.isEmpty() && !t.equals("coloque-seu-token-aqui"))
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
