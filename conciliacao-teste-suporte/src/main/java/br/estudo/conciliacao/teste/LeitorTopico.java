package br.estudo.conciliacao.teste;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Lê as mensagens de um tópico nos testes, do início, até achar as esperadas ou estourar o tempo. */
public final class LeitorTopico {

    private LeitorTopico() {
    }

    /**
     * Espera até haver {@code quantidade} mensagens que satisfaçam o filtro e devolve todas as que
     * satisfazem (pode haver mais, para o teste conferir que não houve duplicata). Grupo novo a cada
     * chamada, lendo do começo do tópico.
     */
    public static List<ConsumerRecord<String, String>> esperar(String topico, Predicate<ConsumerRecord<String, String>> filtro,
                                                               int quantidade, Duration limite) {
        try (var consumidor = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafka().getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()))) {
            consumidor.subscribe(List.of(topico));
            List<ConsumerRecord<String, String>> encontradas = new ArrayList<>();
            Instant fim = Instant.now().plus(limite);
            while (Instant.now().isBefore(fim) && encontradas.size() < quantidade) {
                consumidor.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (filtro.test(r)) {
                        encontradas.add(r);
                    }
                });
            }
            // Uma leitura a mais para pegar eventuais duplicatas que chegaram logo depois.
            consumidor.poll(Duration.ofSeconds(1)).forEach(r -> {
                if (filtro.test(r)) {
                    encontradas.add(r);
                }
            });
            return encontradas;
        }
    }
}
