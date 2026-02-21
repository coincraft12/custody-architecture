package lab.custody.orchestration;

import lab.custody.domain.withdrawal.Withdrawal;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {

    /**
     * Placeholder approval step — currently auto-approves.
     */
    public boolean requestApproval(Withdrawal withdrawal) {
        // In future, this may create approval tasks / integrate manual flow.
        return true;
    }
}
