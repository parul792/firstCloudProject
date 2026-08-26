package com.myproject.core.services;

/**
 * Service that reads Product Content Fragment data directly from the JCR
 * repository and uploads it as JSON to an AWS S3 bucket.
 *
 * <p>No IMS token or HTTP call is required — data is read in-process using
 * a Sling service resource resolver.
 */
public interface ContentFragmentS3ExportService {

    /**
     * Reads the Content Fragment at {@code cfPath} from the JCR repository,
     * serialises its field values to JSON, and uploads the result to S3.
     *
     * @param cfPath absolute JCR path of the published Content Fragment,
     *               e.g. {@code /content/dam/Cloud-Learning/my-product}
     * @return the S3 object key that was written
     * @throws Exception if the JCR read or S3 upload fails
     */
    String exportToS3(String cfPath) throws Exception;
}
