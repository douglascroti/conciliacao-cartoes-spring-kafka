package br.estudo.conciliacao.batch.leitura;

import java.io.InputStream;

import org.springframework.core.io.AbstractResource;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Um objeto do S3 visto como {@link org.springframework.core.io.Resource}, que é o que o
 * {@code FlatFileItemReader} sabe ler.
 *
 * <p>O {@code getObject} devolve o corpo como stream: o reader puxa as linhas conforme precisa,
 * e o arquivo nunca é baixado inteiro nem carregado na memória (essencial para 1 milhão de
 * linhas). No Node seria o {@code Body} do {@code GetObjectCommand} ligado num {@code readline}.
 */
public class ObjetoS3Resource extends AbstractResource {

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
        return s3.getObject(r -> r.bucket(bucket).key(chave));
    }

    @Override
    public String getDescription() {
        return "s3://" + bucket + "/" + chave;
    }
}
