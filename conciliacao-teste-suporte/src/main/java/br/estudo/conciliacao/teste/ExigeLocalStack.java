package br.estudo.conciliacao.teste;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Marca uma classe de teste que precisa do LocalStack. Sem o token (variável de ambiente ou .env),
 * a classe inteira é pulada com o motivo, antes de qualquer preparação.
 *
 * <p>Por que não basta o {@code Assumptions} de {@link Containers#localstack()}: ele é chamado de
 * dentro do {@code @BeforeAll} ou do {@code @DynamicPropertySource}. No primeiro caso o
 * {@code @AfterAll} ainda roda (e falha com recursos nulos); no segundo, a exceção acontece durante
 * a criação do contexto Spring e vira erro em vez de "pulado". Uma {@link ExecutionCondition} é
 * avaliada pelo JUnit antes de tudo isso. (Isso acontece no CI de PRs do Dependabot, que não
 * recebem os secrets do Actions.)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ExigeLocalStack.Condicao.class)
public @interface ExigeLocalStack {

    class Condicao implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext contexto) {
            return Projeto.tokenLocalStack().isPresent()
                    ? ConditionEvaluationResult.enabled("LOCALSTACK_AUTH_TOKEN definido")
                    : ConditionEvaluationResult.disabled(
                            "LOCALSTACK_AUTH_TOKEN não definido (nem no ambiente nem no .env): testes com LocalStack pulados");
        }
    }
}
