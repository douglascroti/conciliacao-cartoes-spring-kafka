package br.estudo.conciliacao.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Serviço de conciliação: fica rodando (diferente da Lambda), consome
 * {@code conciliacao.arquivo-recebido} e executa um job Spring Batch por arquivo.
 *
 * <p>No Node seria um worker de fila (ex.: BullMQ) sempre ligado; aqui o Spring Boot sobe o
 * consumer Kafka, o pool de conexões e um servidor HTTP só para health/métricas (Actuator).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchApplication.class, args);
    }
}
