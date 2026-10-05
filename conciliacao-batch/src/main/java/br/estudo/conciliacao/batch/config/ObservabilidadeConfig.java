package br.estudo.conciliacao.batch.config;

import org.springframework.aop.framework.Advised;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

@Configuration(proxyBeanMethods = false)
public class ObservabilidadeConfig {

    /**
     * Ignora as requisições ao {@code /actuator}: a coleta do Prometheus (a cada 15 s) e o health
     * check do Docker (a cada 10 s) virariam milhares de traces sem valor no Tempo, escondendo os
     * do processamento. Vale para métrica e trace dessas requisições, que não interessam a nenhum dos dois.
     */
    @Bean
    ObservationPredicate ignorarActuator() {
        return (nome, contexto) -> !(contexto instanceof ServerRequestObservationContext requisicao
                && requisicao.getCarrier().getRequestURI().startsWith("/actuator"));
    }

    /**
     * Entrega o {@link ObservationRegistry} da aplicação ao {@code JobOperator}, para o trace do
     * evento Kafka continuar no job.
     *
     * <p>No Spring Batch 6.0, o {@code DefaultBatchConfiguration} cria o JobOperator com o registry
     * NOOP, e o pós-processador que o substituiria ({@code BatchObservabilityBeanPostProcessor})
     * não o reconhece porque ele vem embrulhado num proxy transacional. Com o NOOP, a observation de
     * lançamento abre um escopo vazio que esconde o trace do consumer, e a thread do job (que herda
     * o contexto pelo {@code ContextPropagatingTaskDecorator}) começava um trace novo. Aqui o proxy é
     * desembrulhado e o registry real é aplicado no objeto de verdade.
     *
     * <p>{@code static}: um BeanPostProcessor precisa existir antes dos beans que ele processa; o
     * registry é buscado só na hora (ObjectProvider) para não antecipar a criação dele.
     */
    @Bean
    static BeanPostProcessor registroDeObservationNoJobOperator(ObjectProvider<ObservationRegistry> registry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String nome) throws BeansException {
                if (bean instanceof JobOperator && bean instanceof Advised proxy) {
                    try {
                        if (proxy.getTargetSource().getTarget() instanceof TaskExecutorJobOperator operador) {
                            operador.setObservationRegistry(registry.getObject());
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Não foi possível configurar o tracing do JobOperator", e);
                    }
                }
                return bean;
            }
        };
    }
}
