package br.estudo.conciliacao.lambda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Ponto de entrada da aplicação Spring dentro da Lambda.
 *
 * <p>Na AWS o handler configurado é o {@code FunctionInvoker} do Spring Cloud Function: na
 * primeira invocação (cold start) ele sobe este contexto Spring, encontra o bean
 * {@code receberArquivo} e passa a delegar cada evento para ele. Nas invocações seguintes
 * o contexto (e as conexões com Kafka e Postgres) é reaproveitado.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LambdaApplication {

    public static void main(String[] args) {
        SpringApplication.run(LambdaApplication.class, args);
    }
}
