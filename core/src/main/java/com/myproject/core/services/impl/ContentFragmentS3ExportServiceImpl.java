package com.myproject.core.services.impl;

import com.myproject.core.services.ContentFragmentS3ExportService;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads Product Content Fragment data directly from the JCR repository and
 * uploads it as JSON to an AWS S3 bucket.
 *
 * <h3>No token required</h3>
 * This implementation reads CF data in-process using a Sling service resource
 * resolver — no HTTP call, no IMS Bearer token, no Developer Console access
 * needed.  The service user {@code myproject-cf-exporter} (mapped via
 * {@code org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended})
 * must have read access to {@code /content/dam}.
 *
 * <h3>What gets exported</h3>
 * When any Content Fragment under {@code /content/dam} is published, this
 * service reads the CF's {@code jcr:content/data/master} node, serialises all
 * its properties to JSON, and uploads the result to S3.
 *
 * <p>All AWS parameters are managed via OSGi configuration
 * ({@code ui.config/.../osgiconfig/config/com.myproject.core.services.impl
 * .ContentFragmentS3ExportServiceImpl.cfg.json}).
 */
@Designate(ocd = ContentFragmentS3ExportServiceImpl.Config.class)
@Component(service = ContentFragmentS3ExportService.class, immediate = false)
public class ContentFragmentS3ExportServiceImpl implements ContentFragmentS3ExportService {

    /** Sling service-user sub-service name — must match the OSGi mapping config. */
    static final String SERVICE_USER = "myproject-cf-exporter";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // -----------------------------------------------------------------------
    // OSGi configuration
    // -----------------------------------------------------------------------

    @ObjectClassDefinition(
            name = "Content Fragment → S3 Export Service",
            description = "Reads Product CF data from JCR and pushes JSON to S3. "
                    + "No AEM token required — uses a Sling service user."
    )
    public @interface Config {

        @AttributeDefinition(name = "AWS region",
                description = "AWS region where the target S3 bucket resides, e.g. eu-north-1")
        String aws_region() default "eu-north-1";

        @AttributeDefinition(name = "AWS access key ID")
        String aws_accessKeyId() default "";

        @AttributeDefinition(name = "AWS secret access key")
        String aws_secretAccessKey() default "";

        @AttributeDefinition(name = "S3 bucket name")
        String s3_bucket() default "my-aem-cf-headless-poc--eun1-az1--x-s";

        @AttributeDefinition(name = "S3 key prefix",
                description = "Path prefix for uploaded objects, e.g. 'cf-exports/'")
        String s3_keyPrefix() default "cf-exports/";
    }

    // -----------------------------------------------------------------------
    // OSGi references
    // -----------------------------------------------------------------------

    @Reference
    private ResourceResolverFactory resolverFactory;

    // -----------------------------------------------------------------------
    // Fields populated from config
    // -----------------------------------------------------------------------

    private Region awsRegion;
    private String accessKeyId;
    private String secretAccessKey;
    private String s3Bucket;
    private String s3KeyPrefix;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Activate
    @Modified
    protected void activate(final Config config) {
        this.awsRegion       = Region.of(config.aws_region());
        this.accessKeyId     = config.aws_accessKeyId();
        this.secretAccessKey = config.aws_secretAccessKey();
        this.s3Bucket        = config.s3_bucket();
        this.s3KeyPrefix     = config.s3_keyPrefix();
        logger.info("ContentFragmentS3ExportService activated: bucket={}", s3Bucket);
    }

    // -----------------------------------------------------------------------
    // Service implementation
    // -----------------------------------------------------------------------

    /**
     * Reads the published Product Content Fragment at {@code cfPath} directly
     * from the JCR repository, serialises its properties to JSON, and uploads
     * the result to S3.
     *
     * <p>Content Fragment data is stored under:
     * {@code <cf-path>/jcr:content/data/master}
     * Each property on that node is one CF field (e.g. {@code productTitle}).
     */
    @Override
    public String exportToS3(final String cfPath) throws Exception {
        logger.info("Reading CF from JCR at path={}", cfPath);

        final String json = readCfAsJson(cfPath);
        final String cfName = cfPath.substring(cfPath.lastIndexOf('/') + 1);
        final String s3Key  = buildS3Key(cfName);
        uploadToS3(json, s3Key);

        logger.info("CF data for path={} uploaded to s3://{}/{}", cfPath, s3Bucket, s3Key);
        return s3Key;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Opens a service-user resource resolver, navigates to the CF's data node,
     * and returns a JSON string of all its properties.
     *
     * <p>CF structure in JCR:
     * <pre>
     * /content/dam/Cloud-Learning/my-product      (dam:Asset)
     *   └── jcr:content                           (dam:AssetContent)
     *         └── data
     *               └── master                    ← CF field values live here
     *                     ├── productTitle = "..."
     *                     └── ...other fields...
     * </pre>
     */
    private String readCfAsJson(final String cfPath) throws LoginException, RepositoryException {
        final Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SERVICE_USER);

        try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(authInfo)) {

            // Navigate to the CF data node: <path>/jcr:content/data/master
            final String dataPath = cfPath + "/jcr:content/data/master";
            final Resource dataResource = resolver.getResource(dataPath);

            if (dataResource == null) {
                throw new IllegalStateException(
                        "CF data node not found at " + dataPath
                        + " — ensure the path is a Content Fragment and the service user has read access.");
            }

            return buildJson(cfPath, dataResource);
        }
    }

    /**
     * Builds a JSON string from the CF data node's properties.
     *
     * <p>Skips internal JCR properties (those starting with {@code jcr:} or
     * {@code cq:}) to keep the output clean.
     *
     * <p>Example output:
     * <pre>
     * {
     *   "path": "/content/dam/Cloud-Learning/my-product",
     *   "properties": {
     *     "productTitle": "My Product"
     *   }
     * }
     * </pre>
     */
    private String buildJson(final String cfPath, final Resource dataResource)
            throws RepositoryException {

        final StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"path\": \"").append(escape(cfPath)).append("\",\n");
        sb.append("  \"exportedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"properties\": {\n");

        final ValueMap props = dataResource.getValueMap();
        boolean first = true;
        for (final Map.Entry<String, Object> entry : props.entrySet()) {
            final String key = entry.getKey();
            // Skip internal JCR/CQ system properties
            if (key.startsWith("jcr:") || key.startsWith("cq:") || key.startsWith("sling:")) {
                continue;
            }
            if (!first) {
                sb.append(",\n");
            }
            sb.append("    \"").append(escape(key)).append("\": ")
              .append(toJsonValue(entry.getValue()));
            first = false;
        }

        // Also read child nodes (e.g. multi-field or nested CF references)
        final Node dataNode = dataResource.adaptTo(Node.class);
        if (dataNode != null) {
            final NodeIterator children = dataNode.getNodes();
            while (children.hasNext()) {
                final javax.jcr.Node child = children.nextNode();
                final String childName = child.getName();
                if (childName.startsWith("jcr:") || childName.startsWith("cq:")) {
                    continue;
                }
                if (!first) {
                    sb.append(",\n");
                }
                sb.append("    \"").append(escape(childName)).append("\": \"[nested node]\"");
                first = false;
            }
        }

        sb.append("\n  }\n}");
        return sb.toString();
    }

    /**
     * Converts a JCR property value to a JSON-safe representation.
     * Arrays are written as JSON arrays; all other types as quoted strings.
     */
    private String toJsonValue(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[]) {
            final StringBuilder arr = new StringBuilder("[");
            final Object[] items = (Object[]) value;
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    arr.append(", ");
                }
                arr.append("\"").append(escape(String.valueOf(items[i]))).append("\"");
            }
            arr.append("]");
            return arr.toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    /** Escapes characters that are unsafe inside a JSON string value. */
    private String escape(final String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Uploads the JSON string to S3 using the configured credentials and bucket.
     */
    private void uploadToS3(final String json, final String s3Key) {
        final S3Client s3 = S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();

        try {
            final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            final PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Bucket)
                    .key(s3Key)
                    .contentType("application/json")
                    .contentLength((long) bytes.length)
                    .build();

            s3.putObject(putRequest, RequestBody.fromBytes(bytes));
            logger.debug("S3 PutObject completed: key={}", s3Key);
        } finally {
            s3.close();
        }
    }

    /**
     * Builds a timestamped S3 object key,
     * e.g. {@code cf-exports/my-product-1724567890.json}.
     */
    private String buildS3Key(final String label) {
        return s3KeyPrefix + label + "-" + Instant.now().getEpochSecond() + ".json";
    }
}
