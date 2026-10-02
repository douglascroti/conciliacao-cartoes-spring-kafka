package br.estudo.conciliacao.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado em {@link Topicos#ERRO} para cada linha inválida do arquivo, que é pulada
 * sem interromper o processamento. Leva só o número da linha e o motivo, nunca o conteúdo
 * (a linha tem o PAN mascarado).
 *
 * @param idArquivo arquivo de origem; chave da mensagem Kafka
 */
public record ErroLinhaEvento(
        UUID idArquivo,
        String nomeArquivo,
        int numeroLinha,
        String motivo,
        Instant ocorridoEm) {
}
