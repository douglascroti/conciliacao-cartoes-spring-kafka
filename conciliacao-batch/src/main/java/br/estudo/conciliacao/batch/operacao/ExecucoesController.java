package br.estudo.conciliacao.batch.operacao;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operação manual: reiniciar uma execução que falhou (ex.: Kafka fora do ar), depois que a causa
 * foi resolvida. O restart continua do último chunk confirmado.
 *
 * <p>Equivale a uma rota {@code app.post('/execucoes/:id/reiniciar', ...)} no Express. Sem
 * autenticação: é um endpoint operacional, que num ambiente real ficaria atrás de auth/rede interna.
 */
@RestController
@RequestMapping("/execucoes")
public class ExecucoesController {

    private static final Logger log = LoggerFactory.getLogger(ExecucoesController.class);

    private final JobOperator jobOperator;

    public ExecucoesController(JobOperator jobOperator) {
        this.jobOperator = jobOperator;
    }

    @PostMapping("/{id}/reiniciar")
    public ResponseEntity<Map<String, Object>> reiniciar(@PathVariable long id) {
        try {
            Long nova = jobOperator.restart(id);
            log.info("Execução {} reiniciada manualmente como execução {}", id, nova);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("execucaoOriginal", id, "novaExecucao", nova));
        } catch (NoSuchJobExecutionException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", "execução " + id + " não existe"));
        } catch (JobInstanceAlreadyCompleteException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", "o arquivo já foi processado com sucesso"));
        } catch (Exception e) {
            // Ex.: JobRestartException (execução que não falhou, como uma ainda em andamento).
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", e.getMessage()));
        }
    }
}
