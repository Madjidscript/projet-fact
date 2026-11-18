package com.eburtis.fact.service;

import java.net.URI;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Un bucket Cloudflare R2 privé, par son interface compatible S3. Les fichiers n'y sont jamais
 * publics : l'API les lit et les sert elle-même, avec ses propres contrôles d'accès (une photo
 * d'état des lieux ou un mandat signé ne doivent pas être lisibles par n'importe qui).
 */
public final class DepotR2 implements AutoCloseable {

    private final S3Client client;
    private final String bucket;

    public DepotR2(String endpoint, String bucket, String cleAcces, String cleSecrete) {
        if (blanc(endpoint) || blanc(bucket) || blanc(cleAcces) || blanc(cleSecrete)) {
            throw new IllegalStateException("Cloudflare R2 : R2_ENDPOINT, R2_BUCKET, R2_CLE_ACCES et R2_CLE_SECRETE "
                    + "doivent être renseignés ensemble.");
        }
        this.bucket = bucket.trim();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint.trim()))
                // R2 attend la région « auto » ; adresses « endpoint/bucket/clé », acceptées partout.
                .region(Region.of("auto"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cleAcces.trim(), cleSecrete.trim())))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public String bucket() {
        return bucket;
    }

    public void ecrire(String cle, byte[] contenu, String type) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(cle).contentType(type).build(), RequestBody.fromBytes(contenu));
    }

    /** Le contenu d'un fichier, ou {@code null} s'il n'existe pas. */
    public byte[] lire(String cle) {
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(cle).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    public boolean existe(String cle) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(cle).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public void effacer(String cle) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(cle).build());
    }

    @Override
    public void close() {
        client.close();
    }

    static boolean blanc(String s) {
        return s == null || s.isBlank();
    }
}
