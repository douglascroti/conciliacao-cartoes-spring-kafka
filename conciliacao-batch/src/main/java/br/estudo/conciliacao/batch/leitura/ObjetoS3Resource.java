package br.estudo.conciliacao.batch.leitura;

import java.io.InputStream;

import org.springframework.core.io.AbstractResource;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Um objeto do S3 visto como {@link org.springframework.core.io.Resource}, que é o que o
 * {@code FlatFileItemReader} sabe ler.
 *
 * <p>O {@code getObject} devolve o corpo como stream: o reader puxa as linhas conforme precisa,
 * e o arquivo nunca é baixado inteiro nem carregado na memória (essencial para 1 milhão de
 * linhas). No Node seria o {@code Body} do {@code GetObjectCommand} ligado num {@code readline}.
 * Se a conexão cair no meio, a {@link LeituraRetomavel} reabre o objeto do ponto em que parou.
 */
public class ObjetoS3Resource extends AbstractResource {

    private static final int MAXIMO_TENTATIVAS = 5;
    private static final long ESPERA_BASE_MILLIS = 500;

    private final S3Client s3;
    private final String bucket;
    private final String chave;

    public ObjetoS3Resource(S3Client s3, String bucket, String chave) {
        this.s3 = s3;
        this.bucket = bucket;
        this.chave = chave;
    }

    @Override
    public boolean exists() {
        try {
            s3.headObject(r -> r.bucket(bucket).key(chave));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public InputStream getInputStream() {
        // ETag da primeira abertura: as retomadas usam If-Match, para nunca emendar bytes de duas
        // versões diferentes do objeto (se ele for substituído no meio, o S3 responde 412 e a
        // leitura falha em vez de produzir um arquivo misturado).
        String[] etag = new String[1];
        return new LeituraRetomavel(posicao -> {
            ResponseInputStream<GetObjectResponse> corpo = s3.getObject(r -> {
                r.bucket(bucket).key(chave);
                if (posicao > 0) {
                    r.range("bytes=" + posicao + "-").ifMatch(etag[0]);
                }
            });
            if (etag[0] == null) {
                etag[0] = corpo.response().eTag();
            }
            return corpo;
        }, getDescription(), MAXIMO_TENTATIVAS, ESPERA_BASE_MILLIS);
    }

    @Override
    public String getDescription() {
        return "s3://" + bucket + "/" + chave;
    }
}
