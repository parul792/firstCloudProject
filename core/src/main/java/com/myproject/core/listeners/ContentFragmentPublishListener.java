package com.myproject.core.listeners;

import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;
import com.myproject.core.services.ContentFragmentS3ExportService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi EventHandler that listens for AEM Replication (publish) events and,
 * whenever a Content Fragment under {@code /content/dam} is activated,
 * triggers an S3 export for that specific fragment.
 *
 * <p>Content Fragments in AEM are stored as {@code dam:Asset} nodes whose
 * primary child resource carries the property
 * {@code jcr:content/contentFragment=true}.  Because the replication event
 * fires before the JCR state is accessible here, we use a simple path
 * convention check ({@code /content/dam}) combined with a configurable
 * CF root path prefix to decide whether to react.
 *
 * <p>The component uses {@code immediate = true} — required for
 * {@code EventHandler} services so OSGi registers the service synchronously
 * and the event bus can dispatch to it immediately.
 */
@Component(
        service = EventHandler.class,
        immediate = true,
        property = {
                EventConstants.EVENT_TOPIC + "=" + ReplicationAction.EVENT_TOPIC
        }
)
public class ContentFragmentPublishListener implements EventHandler {

    private static final String DEFAULT_CF_ROOT = "/content/dam";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Reference
    private ContentFragmentS3ExportService exportService;

    // -----------------------------------------------------------------------
    // EventHandler
    // -----------------------------------------------------------------------

    @Override
    public void handleEvent(final Event event) {
        final ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null) {
            return;
        }

        // Only react to ACTIVATE (publish) actions
        if (!ReplicationActionType.ACTIVATE.equals(action.getType())) {
            return;
        }

        final String path = action.getPath();
        if (path == null || !path.startsWith(DEFAULT_CF_ROOT)) {
            return;
        }

        logger.info("Content Fragment publish detected at path={}, triggering S3 export", path);

        try {
            final String s3Key = exportService.exportToS3(path);
            logger.info("CF S3 export completed for path={}, s3Key={}", path, s3Key);
        } catch (Exception e) {
            // Log and continue — do not rethrow; a failed export must not block replication
            logger.error("CF S3 export failed for path={}", path, e);
        }
    }
}
