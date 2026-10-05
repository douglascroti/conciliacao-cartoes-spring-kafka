package br.estudo.conciliacao.lambda.infra;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identificador de rastreamento no padrão W3C Trace Context, enviado no header {@code traceparent}
 * do evento Kafka. O serviço batch continua o mesmo trace a partir dele, então tudo o que acontece
 * com um arquivo (do upload ao fim do job) fica sob um único {@code traceId}.
 *
 * <p>A Lambda não usa o SDK do OpenTelemetry (JAR e cold start menores, ADR 0012): ela só gera os
 * IDs e não exporta o próprio span. No Grafana Tempo o trace aparece com a raiz "faltando"
 * (o span da Lambda), e o {@code traceId} no log da Lambda liga as duas pontas.
 *
 * <p>Formato: {@code 00-<traceId 32 hex>-<spanId 16 hex>-01} (versão 00, flag 01 = amostrado).
 */
public record Traceparent(String traceId, String spanId) {

    public static final String HEADER = "traceparent";

    private static final HexFormat HEX = HexFormat.of();

    public static Traceparent novo() {
        return new Traceparent(hexNaoZero(16), hexNaoZero(8));
    }

    public String valor() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    // O padrão proíbe IDs só com zeros (seriam tratados como inválidos e o trace recomeçaria).
    private static String hexNaoZero(int bytes) {
        byte[] id = new byte[bytes];
        do {
            ThreadLocalRandom.current().nextBytes(id);
        } while (soZeros(id));
        return HEX.formatHex(id);
    }

    private static boolean soZeros(byte[] id) {
        for (byte b : id) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
