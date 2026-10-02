package br.estudo.conciliacao.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import tools.jackson.core.JacksonException;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /**
     * O que fazer quando o listener lança exceção. A autoconfiguração do Spring Kafka usa este
     * bean em todos os listeners.
     *
     * <p>Falha temporária (banco fora do ar): 5 novas tentativas a cada 2 s; esgotadas, o erro é
     * logado e o consumer segue para a próxima mensagem. JSON inválido não é retentado: a mensagem
     * nunca vai funcionar ("poison pill") e travaria a partição. Na 3c, as esgotadas passam a ir
     * para um tópico de erro em vez de só para o log.
     */
    @Bean
    DefaultErrorHandler tratadorErrosKafka() {
        DefaultErrorHandler tratador = new DefaultErrorHandler(new FixedBackOff(2_000, 5));
        tratador.addNotRetryableExceptions(JacksonException.class);
        return tratador;
    }
}
