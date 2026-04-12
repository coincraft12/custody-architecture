package lab.custody.orchestration.whitelist;

import jakarta.annotation.PostConstruct;
import lab.custody.domain.whitelist.WhitelistAddress;
import lab.custody.domain.whitelist.WhitelistAddressRepository;
import lab.custody.domain.whitelist.WhitelistAuditLog;
import lab.custody.domain.whitelist.WhitelistAuditLogRepository;
import lab.custody.domain.whitelist.WhitelistStatus;
import lab.custody.domain.withdrawal.ChainType;
import lab.custody.orchestration.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhitelistService {

    private final WhitelistAddressRepository whitelistRepository;
    private final WhitelistAuditLogRepository auditLogRepository;

    @Value("${custody.whitelist.default-hold-hours:48}")
    private long defaultHoldHours;

    @Value("${policy.whitelist-to-addresses:}")
    private String staticWhitelistConfig;

    // ─────────────────────────── API operations ───────────────────────────

    @Transactional
    public WhitelistAddress register(RegisterAddressRequest req) {
        ChainType chainType = parseChainType(req.chainType());
        String normalizedAddress = normalizeAddress(req.address());

        whitelistRepository.findByAddressIgnoreCaseAndChainType(normalizedAddress, chainType)
                .ifPresent(existing -> {
                    throw new InvalidRequestException(
                            "이미 등록된 주소입니다: " + normalizedAddress + " (" + chainType + ") status=" + existing.getStatus());
                });

        WhitelistAddress entry = WhitelistAddress.register(
                normalizedAddress,
                chainType,
                req.registeredBy() != null ? req.registeredBy() : "unknown",
                req.note(),
                defaultHoldHours
        );
        try {
            WhitelistAddress saved = whitelistRepository.save(entry);
            log.info("event=whitelist.register address={} chainType={} id={} registeredBy={}",
                    saved.getAddress(), saved.getChainType(), saved.getId(), saved.getRegisteredBy());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate register: UNIQUE(address, chainType) violated.
            throw new InvalidRequestException("이미 등록된 주소입니다: " + normalizedAddress + " (" + chainType + ")");
        }
    }

    @Transactional
    public WhitelistAddress approve(UUID id, String approvedBy) {
        WhitelistAddress entry = load(id);
        WhitelistStatus previousStatus = entry.getStatus();
        try {
            entry.approve(approvedBy != null ? approvedBy : "unknown");
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        WhitelistAddress saved = whitelistRepository.save(entry);
        // 8-3-3: 상태 전이 감사 로그 기록
        auditLogRepository.save(WhitelistAuditLog.record(
                saved.getId(), "APPROVED",
                approvedBy != null ? approvedBy : "unknown",
                previousStatus, saved.getStatus()));
        log.info("event=whitelist.approve id={} address={} approvedBy={} activeAfter={}",
                saved.getId(), saved.getAddress(), saved.getApprovedBy(), saved.getActiveAfter());
        return saved;
    }

    @Transactional
    public WhitelistAddress revoke(UUID id, String revokedBy) {
        WhitelistAddress entry = load(id);
        WhitelistStatus previousStatus = entry.getStatus();
        try {
            entry.revoke(revokedBy != null ? revokedBy : "unknown");
        } catch (IllegalStateException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        WhitelistAddress saved = whitelistRepository.save(entry);
        // 8-3-3: 상태 전이 감사 로그 기록
        auditLogRepository.save(WhitelistAuditLog.record(
                saved.getId(), "REVOKED",
                revokedBy != null ? revokedBy : "unknown",
                previousStatus, saved.getStatus()));
        log.info("event=whitelist.revoke id={} address={} revokedBy={}",
                saved.getId(), saved.getAddress(), saved.getRevokedBy());
        return saved;
    }

    @Transactional(readOnly = true)
    public WhitelistAddress get(UUID id) {
        return load(id);
    }

    @Transactional(readOnly = true)
    public List<WhitelistAddress> list(WhitelistStatus status) {
        if (status == null) {
            return whitelistRepository.findAllByOrderByRegisteredAtDesc();
        }
        return whitelistRepository.findByStatus(status);
    }

    // 8-3-4: 화이트리스트 항목의 감사 이력 조회 (최신 순)
    @Transactional(readOnly = true)
    public List<WhitelistAuditLog> getAuditLog(UUID id) {
        // 항목 존재 확인 (없으면 InvalidRequestException)
        load(id);
        return auditLogRepository.findByWhitelistAddressIdOrderByCreatedAtDesc(id);
    }

    // ─────────────────────────── scheduler ───────────────────────────

    /**
     * HOLDING 상태 중 activeAfter 가 경과한 항목을 ACTIVE 로 자동 전환.
     * fixedDelay: 이전 실행 완료 후 N ms 뒤에 재실행.
     */
    @Scheduled(fixedDelayString = "${custody.whitelist.scheduler-delay-ms:60000}")
    @Transactional
    public void promoteHoldingToActive() {
        // 8-1-3: 스케줄러 실행마다 고유 correlationId 생성
        String correlationId = "sched-wl-" + UUID.randomUUID().toString().substring(0, 8);
        MDC.put("correlationId", correlationId);
        try {
            List<WhitelistAddress> ready =
                    whitelistRepository.findByStatusAndActiveAfterLessThanEqual(WhitelistStatus.HOLDING, Instant.now());

            if (ready.isEmpty()) return;

            // 8-1-4: scheduler=WhitelistScheduler 형식 구조화 로그
            log.info("event=whitelist.scheduler.promote scheduler=WhitelistScheduler promoted={}", ready.size());
            for (WhitelistAddress entry : ready) {
                try {
                    WhitelistStatus previousStatus = entry.getStatus();
                    entry.activate();
                    whitelistRepository.save(entry);
                    // 8-3-3: 스케줄러에 의한 ACTIVATED 감사 로그
                    auditLogRepository.save(WhitelistAuditLog.record(
                            entry.getId(), "ACTIVATED", "system:scheduler",
                            previousStatus, entry.getStatus()));
                    log.info("event=whitelist.activated scheduler=WhitelistScheduler id={} address={} chainType={}",
                            entry.getId(), entry.getAddress(), entry.getChainType());
                } catch (IllegalStateException e) {
                    // Ignore race with concurrent revoke/activate and continue.
                    log.warn("event=whitelist.scheduler.promote.skip scheduler=WhitelistScheduler id={} reason={}",
                            entry.getId(), e.getMessage());
                }
            }
        } finally {
            MDC.remove("correlationId");
        }
    }

    // ─────────────────────────── startup seed ───────────────────────────

    /**
     * 기존 application.yaml 의 policy.whitelist-to-addresses 주소들을
     * DB가 완전히 비어있을 때 ACTIVE 엔트리로 시드한다.
     *
     * 이유: 정적 설정에서 DB 기반 화이트리스트로 전환 시 기존 테스트 호환성 유지.
     * 운영 환경에서는 이 설정을 비워두고 API 를 통해 등록/승인한다.
     */
    @PostConstruct
    @Transactional
    public void seedFromStaticConfig() {
        if (staticWhitelistConfig == null || staticWhitelistConfig.isBlank()) return;
        if (whitelistRepository.count() > 0) return;

        List<String> addresses = Arrays.stream(staticWhitelistConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        if (addresses.isEmpty()) return;

        log.info("event=whitelist.seed.start count={} source=static-config", addresses.size());
        // 정적 설정 주소는 모든 ChainType 에 대해 ACTIVE 로 시드 (하위 호환성 유지)
        for (String addr : addresses) {
            for (ChainType chainType : ChainType.values()) {
                WhitelistAddress entry = WhitelistAddress.register(
                        addr, chainType, "system:seed", "정적 설정에서 마이그레이션", defaultHoldHours);
                entry.approve("system:seed");
                entry.activate();
                whitelistRepository.save(entry);
                log.info("event=whitelist.seed.entry address={} chainType={} status={}", addr, chainType, entry.getStatus());
            }
        }
        log.info("event=whitelist.seed.done addresses={} chains={}", addresses.size(), ChainType.values().length);
    }

    // ─────────────────────────── internal ───────────────────────────

    private WhitelistAddress load(UUID id) {
        return whitelistRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("화이트리스트 항목을 찾을 수 없습니다: " + id));
    }

    private ChainType parseChainType(String chainType) {
        if (chainType == null || chainType.isBlank()) return ChainType.EVM;
        try {
            return ChainType.valueOf(chainType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("잘못된 chainType: " + chainType);
        }
    }

    private String normalizeAddress(String address) {
        if (address == null) {
            throw new InvalidRequestException("address is required");
        }
        String normalized = address.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new InvalidRequestException("address is required");
        }
        return normalized;
    }
}
