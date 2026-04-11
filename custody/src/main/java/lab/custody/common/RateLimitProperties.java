package lab.custody.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "custody.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10")   int withdrawalsPerSecond,
        @DefaultValue("20")   int whitelistPerSecond
) {}
