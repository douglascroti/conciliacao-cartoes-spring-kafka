package br.estudo.conciliacao.lambda.infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.stereotype.Component;

import br.estudo.conciliacao.lambda.config.RecebimentoProperties;
import br.estudo.conciliacao.lambda.dominio.MotivoRejeicao;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.MetadataDirective;

/** Operações no S3 usadas pela Lambda. */
@Component
public class ArmazenamentoArquivos {

    /** Basta para o cabeçalho; nunca baixamos o arquivo inteiro na Lambda. */
    private static final int BYTES_CABECALHO = 1024;

    private final S3Client s3;
    private final String prefixoRejeitados;

    public ArmazenamentoArquivos(S3Client s3, RecebimentoProperties props) {
        this.s3 = s3;
        this.prefixoRejeitados = props.s3().prefixoRejeitados();
    }

    /** Lê só o início do objeto (GET com Range) e devolve a primeira linha. */
    public String lerPrimeiraLinha(String bucket, String chave) {
        try (InputStream in = s3.getObject(req -> req
                .bucket(bucket)
                .key(chave)
                .range("bytes=0-" + (BYTES_CABECALHO - 1)))) {
            String inicio = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int fimLinha = inicio.indexOf('\n');
            return fimLinha >= 0 ? inicio.substring(0, fimLinha) : inicio;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler cabeçalho de s3://" + bucket + "/" + chave, e);
        }
    }

    /**
     * Move o arquivo para o prefixo de rejeitados (o S3 não tem "move": é copiar e apagar).
     * O motivo vai como metadado do objeto, para quem for investigar.
     *
     * @return a chave de destino
     */
    public String moverParaRejeitados(String bucket, String chave, String nomeArquivo, MotivoRejeicao motivo) {
        String destino = prefixoRejeitados + nomeArquivo;
        s3.copyObject(req -> req
                .sourceBucket(bucket)
                .sourceKey(chave)
                .destinationBucket(bucket)
                .destinationKey(destino)
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(Map.of("motivo-rejeicao", motivo.name())));
        s3.deleteObject(req -> req.bucket(bucket).key(chave));
        return destino;
    }
}
