package lab.custody.domain.pds;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 16-1-5: PDS feature flag configuration properties.
 *
 * <p>All features default to false (disabled) until Phase 2 activation.
 * Activate per-feature via environment variable or deployment config.
 *
 * <pre>
 * pds:
 *   enabled: false
 *   endpoint: http://pds-core:3100
 *   features:
 *     signer-key-pds: false
 *     policy-audit-chain: false
 *     emergency-access: false
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "pds")
public class PdsProperties {

    private boolean enabled = false;
    private String endpoint = "http://pds-core:3100";
    private Features features = new Features();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public Features getFeatures() { return features; }
    public void setFeatures(Features features) { this.features = features; }

    public static class Features {
        // Phase 2: signer key stored in PDS (B-2 patent)
        private boolean signerKeyPds = false;
        // Phase 3: policy audit hash chain in PDS (B-1 patent)
        private boolean policyAuditChain = false;
        // Phase 2+: emergency multi-factor access recovery
        private boolean emergencyAccess = false;

        public boolean isSignerKeyPds() { return signerKeyPds; }
        public void setSignerKeyPds(boolean signerKeyPds) { this.signerKeyPds = signerKeyPds; }

        public boolean isPolicyAuditChain() { return policyAuditChain; }
        public void setPolicyAuditChain(boolean policyAuditChain) { this.policyAuditChain = policyAuditChain; }

        public boolean isEmergencyAccess() { return emergencyAccess; }
        public void setEmergencyAccess(boolean emergencyAccess) { this.emergencyAccess = emergencyAccess; }
    }
}
