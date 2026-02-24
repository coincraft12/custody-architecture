package lab.custody.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

import java.util.Map;

/**
 * Allows only logs that were emitted with an MDC correlationId.
 * Used for the pretty file appender so it captures request-flow logs only.
 */
public class RequireCorrelationIdFilter extends Filter<ILoggingEvent> {

    private static final String CORRELATION_ID_KEY = "correlationId";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (event == null) {
            return FilterReply.DENY;
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && mdc.containsKey(CORRELATION_ID_KEY)) {
            return FilterReply.NEUTRAL;
        }
        return FilterReply.DENY;
    }
}
