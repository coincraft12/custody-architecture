package lab.custody.orchestration.policy;

public record PolicyDecision(
        boolean allowed,
        String reason
) {
    public static PolicyDecision allow() {
        return new PolicyDecision(true, "ALLOWED");
    }

    public static PolicyDecision reject(String reason) {
        return new PolicyDecision(false, reason);
    }
}
