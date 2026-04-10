package lab.custody.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "custody.security")
public class ApiKeyProperties {

    private List<ApiKeyEntry> apiKeys = new ArrayList<>();

    public List<ApiKeyEntry> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeyEntry> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public record ApiKeyEntry(String key, List<String> roles) {}
}
