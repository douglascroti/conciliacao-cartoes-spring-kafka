package br.estudo.conciliacao.batch.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration(proxyBeanMethods = false)
public class S3Config {

    @Bean(destroyMethod = "close")
    S3Client s3Client(ConciliacaoProperties props) {
        ConciliacaoProperties.S3 s3 = props.s3();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.regiao()))
                .forcePathStyle(s3.forcePathStyle());
        if (s3.endpoint() != null && !s3.endpoint().isBlank()) {
            // LocalStack: aceita qualquer credencial. Na AWS real (endpoint vazio), vale a cadeia
            // padrão do SDK (variáveis de ambiente, perfil, role do container).
            builder.endpointOverride(URI.create(s3.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
}
