package lab.custody.orchestration.whitelist;

import lab.custody.domain.whitelist.WhitelistAddress;
import lab.custody.domain.whitelist.WhitelistStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/whitelist")
@Slf4j
public class WhitelistController {

    private final WhitelistService whitelistService;

    /**
     * 주소 등록 요청 → REGISTERED 상태
     * 이후 approve API 로 승인해야 HOLDING → ACTIVE 워크플로우 시작.
     */
    @PostMapping
    public ResponseEntity<WhitelistAddress> register(@Valid @RequestBody RegisterAddressRequest req) {
        log.info("event=whitelist.controller.register address={} chainType={}", req.address(), req.chainType());
        return ResponseEntity.ok(whitelistService.register(req));
    }

    /**
     * 관리자 승인 → HOLDING 상태 (48h 보류 타이머 시작).
     * Body: { "approvedBy": "admin-user-id" }
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<WhitelistAddress> approve(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String approvedBy = body.getOrDefault("approvedBy", "unknown");
        log.info("event=whitelist.controller.approve id={} approvedBy={}", id, approvedBy);
        return ResponseEntity.ok(whitelistService.approve(id, approvedBy));
    }

    /**
     * 취소 → REVOKED 상태.
     * Body: { "revokedBy": "admin-user-id" }
     */
    @PostMapping("/{id}/revoke")
    public ResponseEntity<WhitelistAddress> revoke(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String revokedBy = body.getOrDefault("revokedBy", "unknown");
        log.info("event=whitelist.controller.revoke id={} revokedBy={}", id, revokedBy);
        return ResponseEntity.ok(whitelistService.revoke(id, revokedBy));
    }

    /**
     * 목록 조회. ?status=HOLDING 등으로 필터링 가능.
     */
    @GetMapping
    public ResponseEntity<List<WhitelistAddress>> list(
            @RequestParam(required = false) WhitelistStatus status) {
        return ResponseEntity.ok(whitelistService.list(status));
    }

    /**
     * 단건 조회.
     */
    @GetMapping("/{id}")
    public ResponseEntity<WhitelistAddress> get(@PathVariable UUID id) {
        return ResponseEntity.ok(whitelistService.get(id));
    }
}
