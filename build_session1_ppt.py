"""
수탁형 지갑 설계 — Session 1
서명 · Nonce · 수수료 모델 핵심 배경 지식
CoinCraft 공식 템플릿(coincraft_ppt_base) 기반
"""
import sys
sys.path.insert(0, r"f:\Workplace\CoinCraft-Content-Studio\03_ASSETS\Scripts")
from coincraft_ppt_base import *

OUT = r"f:\Workplace\custody\custody track\Session1_Execution_Fundamentals.pptx"

prs = new_presentation()

# ═══════════════════════════════════════════
# SL 1 — 표지
# ═══════════════════════════════════════════
slide_cover(
    prs,
    title="수탁형 지갑 설계\nSession 1",
    subtitle="서명 · Nonce · 수수료 모델 핵심 배경 지식",
    bullets=["트랜잭션 서명 vs 메시지 서명", "Nonce 관리 · EIP‑1559 · EIP‑712", "피싱 방어 · Replay 공격 대응"],
    date_line="2026.03  |  CoinCraft Custody Track"
)

# ═══════════════════════════════════════════
# SL 2 — 학습 목표
# ═══════════════════════════════════════════
sl = slide_body(prs, "", "학습 목표 — Learning Objectives")

objectives = [
    "ECDSA 서명 원리: (v, r, s) 세 값의 의미를 설명하고 ecrecover로 서명자 주소 복구 원리를 말할 수 있다",
    "서명 유형 구분: 트랜잭션 서명 vs 메시지 서명의 구조·Replay 보호 방식 차이를 설명할 수 있다",
    "트랜잭션 타입: Type 0 / 1 / 2 직렬화 형식 차이를 알고, 브로드캐스터가 타입을 유지해야 하는 이유를 말할 수 있다",
    "수수료 모델: EIP-1559 baseFee/tip/maxFee 구조와 Replacement 시 두 필드 함께 올려야 하는 이유를 설명할 수 있다",
    "Nonce 관리: nonce gap 원인과 (from, nonce) 단위 상태 추적의 필요성을 설명할 수 있다",
    "Finality: latest / safe / finalized 블록 태그 차이를 알고, 내부 원장 반영 시점을 결정할 수 있다",
    "피싱 방어: EIP-712 도메인 분리가 블라인드 서명 피싱을 줄이는 원리를 설명할 수 있다",
]

accent_colors = [GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD]
for i, (obj, ac) in enumerate(zip(objectives, accent_colors)):
    y = 1.60 + i * 0.64
    rect(sl, 0.6, y, 0.44, 0.48, NAVY)
    tb(sl, str(i+1), 0.62, y+0.06, 0.40, 0.36,
       size=15, bold=True, color=GOLD, align=PP_ALIGN.CENTER)
    rect(sl, 1.14, y, 11.56, 0.48, CARD_NAVY)
    rect(sl, 1.14, y, 0.06, 0.48, ac)
    tb(sl, obj, 1.28, y+0.06, 11.3, 0.38, size=13.5, color=WHITE)

highlight_bar(sl, "다음 세션 연결: Session 2 HSM/MPC Signer 설계  ·  Session 3 Withdrawal·TxAttempt 상태 머신의 직접 전제 지식")

# ═══════════════════════════════════════════
# SL — Hot / Warm / Cold (NEW — §0)
# ═══════════════════════════════════════════
sl = slide_body(prs, "00", "수탁 지갑 기초 — Hot / Warm / Cold 분류")

card(sl, 0.6, 1.6, 12.1, 0.80,
     title="핵심 질문: \"서명 키가 어디에 있는가?\"",
     body="Hot/Warm/Cold 분류는 키 위치와 리스크의 트레이드오프. 이 세션의 모든 개념(서명·nonce·수수료)은 이 구조 안에서 작동함",
     accent_bar=True)

card(sl, 0.6,  2.55, 3.7, 3.40,
     title="🔥  Hot 지갑",
     body="키 위치: 항상 온라인 서버\n서명: 소프트웨어 직접\n속도: 즉시\n\n리스크: 서버 침해 시\n키 즉시 노출\n\n용도: 소액 빈번 출금\n잔고 최소화 운영",
     accent_bar=True, bg_color=CARD_DARK)

card(sl, 4.55, 2.55, 3.7, 3.40,
     title="🌡  Warm 지갑",
     body="키 위치: HSM / MPC\n서명: 정책 통과 후 활성화\n속도: 수 초\n\n리스크: 정책 우회 시\n대규모 탈취 위험\n\n용도: 중간 금액\n4-eyes 승인 필요",
     accent_bar=True)

card(sl, 8.5,  2.55, 3.7, 3.40,
     title="❄  Cold 지갑",
     body="키 위치: 오프라인 에어갭\n서명: 수동·스케줄\n속도: 수 분~수 시간\n\n리스크: 운영 속도 저하\n접근 절차 복잡\n\n용도: 대형 자산 장기 보관\n3-of-5 이상 다중 서명",
     accent_bar=True)

highlight_bar(sl, "운영 원칙: Hot 잔고 = 1~3일치 수요만 유지 → 임계치 초과 시 Cold로 자동 스윕(sweep)  |  Session 2에서 HSM/MPC Signer 상세 설계")

# ═══════════════════════════════════════════
# SL 3 — 목차
# ═══════════════════════════════════════════
slide_agenda(prs, [
    ("00", "Hot / Warm / Cold 지갑 분류"),
    ("01", "트랜잭션 서명 vs 메시지 서명"),
    ("02", "피싱과 메시지 서명 오남용"),
    ("03", "Replay 공격 방어 — EIP‑155 / EIP‑712"),
    ("04", "Nonce 관리 · 트랜잭션 교체 · Gas 추정"),
    ("05", "EIP‑1559 수수료 모델 · gasLimit 설정"),
    ("06", "EIP‑712 Typed Signing 실전"),
    ("07", "확장 EIP — 2612 / 3009 / 4337"),
])

# ═══════════════════════════════════════════
# SECTION 01
# ═══════════════════════════════════════════
slide_section(prs, "01", "트랜잭션 서명 vs 메시지 서명")

# SL — ECDSA 기초 원리 (NEW)
sl = slide_body(prs, "01", "ECDSA 서명 기초 원리 — (v, r, s) 를 이해해야 하는 이유")

card(sl, 0.6, 1.6, 12.1, 0.92,
     title="왜 여기서 ECDSA를 배우는가",
     body="EIP-155의 v 값 변경, EIP-712의 ecrecover 검증이 \"외워야 할 규칙\"이 아니라 필연적인 결과임을 이해하기 위해",
     accent_bar=True)

tb(sl, "서명 생성 5단계", 0.6, 2.68, 4.0, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.10, 5.8)

steps = [
    "keccak256(data) → 32바이트 digest 계산",
    "서명마다 고유 랜덤값 k 생성",
    "r = (k × G).x  (G: secp256k1 생성점)",
    "s = k⁻¹ × (hash + privateKey × r)  mod n",
    "v = 0 or 1  (공개키 복구를 위한 recovery identifier)",
]
for i, step in enumerate(steps):
    y = 3.16 + i * 0.52
    rect(sl, 0.6, y, 0.38, 0.42, NAVY)
    tb(sl, str(i+1), 0.61, y+0.05, 0.36, 0.32,
       size=13, bold=True, color=GOLD, align=PP_ALIGN.CENTER)
    tb(sl, step, 1.08, y+0.05, 5.2, 0.40, size=13.5, color=WHITE)

tb(sl, "핵심 인사이트 3가지", 6.9, 2.68, 5.5, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 6.9, 3.10, 5.8)

card(sl, 6.9, 3.16, 5.8, 0.88,
     body="같은 메시지도 서명할 때마다 (r, s)가 달라짐  →  k가 랜덤이기 때문\n⚠ k 재사용 = 개인키 노출 (PS3 해킹 사례)",
     accent_bar=True, body_size=13)
card(sl, 6.9, 4.10, 5.8, 0.88,
     body="ecrecover(hash, v, r, s) → 서명자 주소 복구 가능\n→ 컨트랙트가 개인키 없이 서명 검증 (Permit 원리)",
     accent_bar=True, body_size=13)
card(sl, 6.9, 5.04, 5.8, 0.88,
     body="EIP-155: v = chainId×2 + 35/36\n→ 다른 체인에서 ecrecover → 다른 주소 → replay 불가",
     accent_bar=True, body_size=13, bg_color=CARD_DARK)

highlight_bar(sl, "\"지갑 = 키 보관\"이 아니라 \"지갑 = 서명 권한 관리\" — ecrecover 원리가 이 명제의 수학적 근거")

# SL — 트랜잭션 서명
sl = slide_body(prs, "01", "트랜잭션 서명 — 온체인 상태 변경 명령")

card(sl, 0.6, 1.6, 12.1, 1.1,
     title="한 줄 정의",
     body="nonce / gasPrice / gasLimit / to / value / data 를 RLP 직렬화 후 서명 → 네트워크 전파 즉시 체인 상태 변경",
     accent_bar=True)

tb(sl, "3가지 보호 기제", 0.6, 2.85, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.30, 11.5)

card(sl, 0.6,  3.38, 3.7, 2.45,
     title="① RLP 직렬화",
     body="데이터 일부 변경 시\n서명값 완전히 달라짐\n→ 부분 변조 불가",
     accent_bar=True)
card(sl, 4.55, 3.38, 3.7, 2.45,
     title="② EIP‑155 Chain ID",
     body="v = (0 or 1) + 2×chainId\n+ 35/36\n→ 크로스체인 재사용 방지",
     accent_bar=True)
card(sl, 8.5,  3.38, 3.7, 2.45,
     title="③ Nonce",
     body="계정별 순번 카운터\n→ 순서 보장 +\n중복 제출(Replay) 방지",
     accent_bar=True)

highlight_bar(sl, "트랜잭션 서명 = \"이 체인에서, 이 nonce로, 이 상태 변화를 실행하라\" 는 온체인 명령")

# SL 5 — 메시지 서명
sl = slide_body(prs, "01", "메시지 서명 — 오프체인 의사 표시")

card(sl, 0.6, 1.6, 12.1, 1.0,
     title="한 줄 정의",
     body="로그인 · 권한 위임 · 주문 체결 등 비즈니스 로직 동의 증거 | 서명 자체는 체인에 기록되지 않음 — 컨트랙트/서버에 제출 시 온체인 효력",
     accent_bar=True)

tb(sl, "EIP‑191", 0.6, 2.75, 2.0, 0.42, size=15, bold=True, color=GOLD)
tb(sl, "EIP‑712", 6.8, 2.75, 2.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.20, 5.9)
gold_line(sl, 6.8, 3.20, 5.9)

card(sl, 0.6, 3.28, 5.9, 2.6,
     title="Signed Data Standard",
     body="형식: 0x19 <version> <version-specific data> <data>\n\n"
          "0x19 prefix → RLP 인코딩 거래와 혼동 방지\n\n"
          "personal_sign (v=0x45): "
          "\"\\x19Ethereum Signed Message:\\n\"\n+ len(msg) 프리픽스",
     accent_bar=False)

card(sl, 6.8, 3.28, 5.9, 2.6,
     title="Typed Structured Data Hashing",
     body="digest = \\x19\\x01 || domainSeparator\n         || hashStruct(message)\n\n"
          "domainSeparator: name / version / chainId\n"
          "               / verifyingContract / salt\n\n"
          "chainId 불일치 → 지갑은 서명 거부 필수",
     accent_bar=False)

warn_bar(sl, "메시지 서명도 자산을 잃을 수 있는 온체인 행동의 전제 조건 — 절대 방심 금지")

# SL 6 — 비교
sl = slide_body(prs, "01", "트랜잭션 서명 vs 메시지 서명 비교")

headers = ["항목", "트랜잭션 서명", "메시지 서명"]
rows = [
    ("목적",      "이더 송금·컨트랙트 실행 등 온체인 상태 변경",    "로그인·Permit·주문 체결 등 오프체인 증명"),
    ("데이터",    "RLP(nonce, gasPrice, gasLimit, to, value, data, chainId…)",  "0x19 prefix (EIP‑191) 또는 \\x19\\x01||domain||struct (EIP‑712)"),
    ("Replay 보호","nonce + chainId 포함 → 프로토콜 레벨 자동 방어",    "앱 레벨에서 nonce/expiry/uid 직접 관리 필요"),
    ("위험 요소", "가스비 미지정·chainId 잘못 설정 → 운영 사고",  "이해 못한 서명 → 피싱·권한 오남용"),
    ("주요 표준", "EIP‑155, EIP‑1559",                              "EIP‑191, EIP‑712, EIP‑2612"),
]

col_w = [2.2, 4.8, 4.8]
col_x = [0.6, 2.9, 7.85]

# 헤더
for i, (h, x, w) in enumerate(zip(headers, col_x, col_w)):
    rect(sl, x, 1.58, w - 0.08, 0.46, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x + 0.08, 1.62, w - 0.2, 0.38,
       size=15, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

# 행
row_colors = [CARD_NAVY, CARD_DARK, CARD_NAVY, CARD_DARK, CARD_NAVY]
for ri, (label, tx_val, msg_val) in enumerate(rows):
    y = 2.1 + ri * 0.82
    h = 0.76
    rect(sl, col_x[0], y, col_w[0] - 0.08, h, row_colors[ri])
    tb(sl, label, col_x[0] + 0.1, y + 0.08, col_w[0] - 0.22, h - 0.12,
       size=14, bold=True, color=GOLD)
    rect(sl, col_x[1], y, col_w[1] - 0.08, h, row_colors[ri])
    tb(sl, tx_val, col_x[1] + 0.1, y + 0.06, col_w[1] - 0.22, h - 0.1,
       size=13, color=WHITE)
    rect(sl, col_x[2], y, col_w[2] - 0.08, h, row_colors[ri])
    tb(sl, msg_val, col_x[2] + 0.1, y + 0.06, col_w[2] - 0.22, h - 0.1,
       size=13, color=WHITE)

# ═══════════════════════════════════════════
# SECTION 02
# ═══════════════════════════════════════════
slide_section(prs, "02", "피싱과 메시지 서명 오남용")

# SL 8 — 공격 패턴
sl = slide_body(prs, "02", "왜 사고가 나는가? — 공격 패턴 3가지")

card(sl, 0.6, 1.58, 3.8, 2.5,
     title="① 블라인드 서명(eth_sign)",
     body="지갑이 메시지 해석 없이\n\"Sign\" 버튼만 제공\n\n"
          "공격자가 난독화 해시 전송\n→ 사용자가 로그인으로 착각\n\n"
          "서명값 악용 →\n자산 완전 제어권 탈취",
     accent_bar=True, bg_color=CARD_DARK)

card(sl, 4.6, 1.58, 3.8, 2.5,
     title="② 허위 로그인 / Permit 악용",
     body="\"지갑 연결\" 버튼 클릭\n→ 실제로는 permit() 서명 요청\n\n"
          "EIP‑2612 / Uniswap Permit2:\n오프체인 서명으로\n무제한 토큰 승인 가능",
     accent_bar=True, bg_color=CARD_DARK)

card(sl, 8.6, 1.58, 3.8, 2.5,
     title="③ 서명 재사용(Replay) 피싱",
     body="동일 서명을 여러 번 제출\n→ 동일 명령 반복 실행\n\n"
          "nonce/expiry 미포함\n메시지는 재사용 위험\n\n"
          "permit2 서명 탈취 후\n다수 자산 전송",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "실제 사례", 0.6, 4.22, 3.0, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 4.65, 11.5)

numbered_list(sl, [
    "가짜 NFT 마켓플레이스 UI → 메시지 서명 요청 → permit2 승인으로 제출 (MetaMask 경고)",
    "Binance 보안 블로그: eth_sign 악용 → 공격자가 계정 완전 제어권 획득",
], left=0.6, top=4.72, row_h=0.60, text_size=15)

warn_bar(sl, "메시지 서명 = 가스비 없이 자산 잃는 최단 경로  |  절대 이해 없이 서명 금지")

# SL 9 — 방어 전략
sl = slide_body(prs, "02", "방어 전략 — 지갑 UI · 애플리케이션 레벨")

tb(sl, "지갑 UI 설계 원칙", 0.6, 1.6, 5.5, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.06, 5.7)

card(sl, 0.6, 2.12, 5.7, 1.15,
     body="eth_sign 원시 해시 → 경고 또는 차단\n"
          "서명 요청 시 도메인명 + verifyingContract 주소 강조\n"
          "현재 연결 dApp 도메인과 불일치 시 서명 거부",
     accent_bar=True)

card(sl, 0.6, 3.38, 5.7, 1.15,
     body="EIP‑712 구조화 서명 UI 필수 — 서명 데이터 내용 명시적 표시\n"
          "chainId 검증 — 현재 체인과 다르면 즉시 거부\n"
          "permit/permit2 요청 시 \"승인 금액 + 기간\" 별도 경고창",
     accent_bar=True)

tb(sl, "애플리케이션 레벨 방어", 6.65, 1.6, 5.5, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 6.65, 2.06, 5.7)

card(sl, 6.65, 2.12, 5.7, 1.15,
     body="nonce + deadline(expiry) + uid 메시지에 포함 필수\n"
          "EIP‑2612 Permit 구조체: nonce + deadline 내장\n"
          "컨트랙트: usedNonces mapping 유지 → 재사용 즉시 거절",
     accent_bar=True)

card(sl, 6.65, 3.38, 5.7, 1.15,
     body="domainSeparator에 chainId + verifyingContract 포함\n"
          "→ 다른 앱·체인에서 서명 재사용 불가\n"
          "Idempotent 처리: 동일 서명 재제출 → 상태 변화 중복 방지",
     accent_bar=True)

highlight_bar(sl, "\"트랜잭션 Replay는 프로토콜이 막고, 메시지 Replay는 앱이 막는다\"")

# ═══════════════════════════════════════════
# SECTION 03
# ═══════════════════════════════════════════
slide_section(prs, "03", "Replay 공격 방어")

# SL 11 — EIP-155
sl = slide_body(prs, "03", "트랜잭션 Replay — EIP‑155")

card(sl, 0.6, 1.6, 5.7, 1.3,
     title="문제 (EIP‑155 이전)",
     body="동일한 서명값이 포크 체인에서\n그대로 재사용 가능\n→ 다른 체인에서 동일 트랜잭션 실행",
     accent_bar=True, bg_color=CARD_DARK)

card(sl, 6.65, 1.6, 5.7, 1.3,
     title="EIP‑155 해결책",
     body="서명 해시 입력에 chainId 추가\n"
          "(nonce, gasprice, gas, to, value, data, chainId, 0, 0)\n"
          "v = chainId × 2 + 35 또는 36",
     accent_bar=True)

tb(sl, "운영 주의사항", 0.6, 3.08, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.52, 11.5)

numbered_list(sl, [
    "메인넷 / 테스트넷 / L2 각각 다른 chainId → 하드코딩 또는 명확한 UI 표시 필수",
    "잘못된 chainId 지정 → 자산이 잘못된 체인으로 전송 (되돌릴 수 없음)",
    "체인 전환 시 기존 서명 요청 취소 → 새 chainId로 재요청",
], left=0.6, top=3.60, row_h=0.64, text_size=15)

highlight_bar(sl, "\"tx 서명은 chainId로 체인 경계를 박는다\"  —  EIP‑155 확립 원칙")

# SL 12 — 메시지 Replay
sl = slide_body(prs, "03", "메시지 Replay — EIP‑712 + 애플리케이션 레벨")

card(sl, 0.6, 1.6, 12.1, 0.88,
     title="문제",
     body="메시지 서명에는 자동 nonce 없음 → 동일 서명 여러 번 제출 가능 → 동일 명령 반복 실행",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "실전 구현 패턴 3가지", 0.6, 2.62, 5.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.06, 11.5)

card(sl, 0.6,  3.14, 3.7, 2.75,
     title="① nonce + deadline 포함",
     body="메시지에 nonce + deadline\n(expiry) 또는 uid 포함 필수\n\n"
          "EIP‑2612 Permit 구조체:\nnonce + deadline 필드 내장",
     accent_bar=True)

card(sl, 4.55, 3.14, 3.7, 2.75,
     title="② usedNonces 테이블",
     body="컨트랙트/서버에서\nusedNonces mapping 또는\nusedDigests 해시 테이블 유지\n\n"
          "이미 사용된 서명 →\n즉시 거절",
     accent_bar=True)

card(sl, 8.5,  3.14, 3.7, 2.75,
     title="③ Idempotent 처리",
     body="동일 서명 재제출 시\n상태 변화 중복 발생 방지\n\n"
          "애플리케이션 레벨에서\ndigest 저장·관리 필수\n(EIP‑712 Security Considerations)",
     accent_bar=True)

# ═══════════════════════════════════════════
# SECTION 04
# ═══════════════════════════════════════════
slide_section(prs, "04", "Nonce 관리 · 트랜잭션 교체")

# SL 14 — Nonce 역할
sl = slide_body(prs, "04", "Nonce의 역할과 순서 보장")

card(sl, 0.6, 1.6, 5.7, 2.1,
     title="Nonce란?",
     body="계정이 보낸 트랜잭션의 총 수\n= 순번 카운터 (0부터 시작)\n\n"
          "트랜잭션 전송마다 1씩 증가\n\n"
          "같은 nonce 트랜잭션 2개 →\n네트워크에 공존 불가",
     accent_bar=True)

card(sl, 6.65, 1.6, 5.7, 2.1,
     title="보장하는 것",
     body="모든 노드가 동일 순서로 처리\n→ 이중 지불 방지\n\n"
          "높은 nonce 트랜잭션은\n낮은 nonce 처리될 때까지 pending\n\n"
          "gas price 높은 쪽 채굴,\n나머지는 dropped",
     accent_bar=True)

tb(sl, "Replacement 시나리오", 0.6, 3.85, 5.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 4.30, 11.5)

numbered_list(sl, [
    "같은 nonce로 새 tx 전송 → speed up(수수료↑) 또는 cancel(zero-value tx)",
    "fee bump 부족 → \"replacement transaction underpriced\" 오류",
], left=0.6, top=4.38, row_h=0.64, text_size=15)

highlight_bar(sl, "상태 관리 단위: tx hash ❌  →  (from, nonce) ✅")

# SL 15 — Replacement 정책
sl = slide_body(prs, "04", "Replacement 트랜잭션 — Geth / Besu 정책")

card(sl, 0.6, 1.6, 5.7, 4.35,
     title="Geth (txpool.pricebump = 10%)",
     body="새 트랜잭션의 가스 가격이\n기존보다 10% 이상 높아야 교체 허용\n\n"
          "EIP‑1559 트랜잭션:\nmaxFeePerGas AND\nmaxPriorityFeePerGas\n모두 10% 이상 인상 필요\n\n"
          "미충족 시 오류:\n\"replacement transaction underpriced\"",
     accent_bar=True)

card(sl, 6.65, 1.6, 5.7, 4.35,
     title="Besu 정책 (두 조건 중 하나)",
     body="조건 A:\n효과적 가스 가격 >\n(1+bump)×기존 AND\n효과적 priority fee ≥ 기존\n\n"
          "조건 B:\n가스 가격 동일 +\n효과적 priority fee >\n(1+bump)×기존\n\n"
          "effective priority fee\n= min(maxFee - baseFee, maxPriorityFee)",
     accent_bar=True)

highlight_bar(sl, "혼잡 시 baseFee 급등 대비 → maxFee + priorityFee 함께 인상 필수")

# SL 16 — Dropped / 운영
sl = slide_body(prs, "04", "Dropped / Replaced 상태 및 운영 모범 사례")

card(sl, 0.6, 1.6, 5.7, 1.55,
     title="Dropped",
     body="노드들이 더 이상 브로드캐스트하지 않아\n체인에 미기록 → 자산·가스비 반환, nonce 재사용",
     accent_bar=True, bg_color=CARD_DARK)
card(sl, 6.65, 1.6, 5.7, 1.55,
     title="Replaced",
     body="동일 nonce의 다른 트랜잭션이 해당 nonce를 차지\n→ 기존 tx는 dropped 처리",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "운영 모범 사례", 0.6, 3.32, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.75, 11.5)

numbered_list(sl, [
    "(from, nonce) 기준 상태 추적 — 출금 1건 = TxAttempt 엔티티 여러 개",
    "재전송 시 priority fee + max fee 각각 10~15% 이상 인상 자동화",
    "txpool.lifetime(Geth 기본 3시간) 초과 → 새 nonce로 재발송 로직 필수",
    "고빈도 서비스: 노드 자동 nonce 의존 금지 → 서버 측 주소별 nonce 직접 관리",
], left=0.6, top=3.82, row_h=0.56, text_size=14)

warn_bar(sl, "nonce gap 발생 시 이후 모든 tx가 pending 대기 → 반드시 gap 먼저 해소")

# SL — eth_getTransactionCount 함정 (NEW §4.3.1)
sl = slide_body(prs, "04", "eth_getTransactionCount 함정 — \"latest\" vs \"pending\"")

card(sl, 0.6, 1.6, 12.1, 0.78,
     title="왜 이것이 중요한가",
     body="\"pending\" 파라미터에만 의존하면 dropped tx·노드 불일치·병렬 요청 시 nonce 충돌 발생 → 이후 모든 tx pending 대기(nonce gap)",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "시나리오별 함정", 0.6, 2.52, 4.0, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.94, 11.5)

trap_rows = [
    ("tx 3건 mempool 대기",   "5",   "8",   "노드 재시작 시 pending 누락 → 잘못된 nonce"),
    ("dropped tx 발생 후",    "5",   "5",   "dropped tx 포함해 계산 → nonce gap 오해"),
    ("노드마다 mempool 불일치","5",   "5 or 6", "노드에 따라 결과 다름 → 예측 불가"),
    ("서버 동시 요청 2건",     "5",   "5 (동시)", "두 tx가 같은 nonce 6 → 충돌"),
]
trap_headers = ["시나리오", "latest", "pending", "문제"]
trap_col_x = [0.6, 4.5, 6.15, 7.85]
trap_col_w = [3.82, 1.58, 1.63, 4.82]

for ci, (h, x, w) in enumerate(zip(trap_headers, trap_col_x, trap_col_w)):
    rect(sl, x, 3.00, w - 0.06, 0.38, GOLD if ci > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 3.03, w-0.18, 0.30,
       size=12, bold=True, color=DARK if ci > 0 else GOLD, align=PP_ALIGN.CENTER)

for ri, (scenario, lat, pend, problem) in enumerate(trap_rows):
    y = 3.44 + ri * 0.58
    for ci, (val, x, w) in enumerate(zip([scenario, lat, pend, problem], trap_col_x, trap_col_w)):
        rect(sl, x, y, w - 0.06, 0.52, CARD_DARK if ri % 2 else CARD_NAVY)
        col_c = WARN if ci == 2 else WHITE
        tb(sl, val, x+0.10, y+0.06, w-0.22, 0.42, size=12, color=col_c, bold=(ci==0))

tb(sl, "정답: DB 기반 nonce 관리 흐름", 0.6, 5.78, 5.0, 0.38, size=13, bold=True, color=GOLD)
card(sl, 0.6, 6.16, 12.1, 0.60,
     body="① 시작 시 latest로 초기화  →  ② 이후 내부 DB NonceReservation 테이블에서 next_nonce 계산  →  ③ from 주소별 single-writer 락  →  ④ 노드값 주기 비교·알림",
     accent_bar=False, body_size=13)

# SL — 트랜잭션 타입 Type 0/1/2 (NEW)
sl = slide_body(prs, "04", "트랜잭션 타입 — Type 0 / 1 / 2")

card(sl, 0.6, 1.6, 12.1, 0.80,
     title="왜 타입을 구분해야 하는가",
     body="브로드캐스터가 타입을 모르면 직렬화 자체가 실패  |  같은 nonce에서 타입 변경 불가 → 처음 선택한 타입을 완료 때까지 유지",
     accent_bar=True)

headers_t = ["항목", "Type 0  Legacy", "Type 1  Access List", "Type 2  EIP‑1559"]
rows_t = [
    ("도입",       "초기",          "EIP‑2930",              "EIP‑1559"),
    ("앞 바이트",  "없음 (순수 RLP)", "0x01",                  "0x02"),
    ("수수료 필드", "gasPrice",      "gasPrice + accessList", "maxFeePerGas\n+ maxPriorityFeePerGas"),
    ("v 인코딩",   "chainId×2+35/36\n(EIP-155)", "yParity (0 or 1)", "yParity (0 or 1)"),
    ("현재 사용",  "L2 일부·레거시", "특수 목적 (gas 최적화)", "EVM 표준 기본값"),
]

col_w_t = [2.0, 2.9, 3.2, 3.6]
col_x_t = [0.6, 2.7, 5.7, 9.0]
row_h_t = 0.72

for i, (h, x, w) in enumerate(zip(headers_t, col_x_t, col_w_t)):
    bg_c = GOLD if i > 0 else CARD_DARK
    rect(sl, x, 2.55, w - 0.06, 0.44, bg_c)
    tb(sl, h, x+0.08, 2.59, w-0.18, 0.36,
       size=13, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

row_bg = [CARD_NAVY, CARD_DARK, CARD_NAVY, CARD_DARK, CARD_NAVY]
for ri, (label, v0, v1, v2) in enumerate(rows_t):
    y = 3.05 + ri * row_h_t
    for ci, (val, x, w) in enumerate(zip([label, v0, v1, v2], col_x_t, col_w_t)):
        rect(sl, x, y, w - 0.06, row_h_t - 0.06, row_bg[ri])
        tb(sl, val, x+0.1, y+0.05, w-0.22, row_h_t-0.14,
           size=12, bold=(ci==0), color=GOLD if ci==0 else WHITE)

highlight_bar(sl, "Type 1/2는 EIP‑2718(Typed Envelope): 타입 바이트 || RLP  |  Type 1/2에서 v → yParity (chainId는 별도 필드)")

# SL — Finality 개념 (NEW)
sl = slide_body(prs, "04", "Finality — 트랜잭션이 언제 \"완료\"인가")

card(sl, 0.6, 1.6, 12.1, 0.80,
     title="왜 included 됐다고 바로 원장에 반영하면 안 되는가",
     body="블록에 포함(latest)된 트랜잭션도 블록 재조직(reorg)으로 취소 가능  →  수탁 지갑은 반드시 finality 기준을 지켜야 함",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "PoS Ethereum 신뢰 수준 3단계", 0.6, 2.56, 5.0, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.98, 11.5)

SAFE_COLOR  = RGBColor(0x52, 0xB7, 0x88)   # 초록 (safe/finalized 표시용)
finality_rows = [
    ("`latest`",    "가장 최근 블록에 포함됨",             "reorg 가능",     "원장 반영 금지",        WARN),
    ("`safe`",      "검증자 2/3 이상 attestation 완료",    "매우 낮음",      "사용자 \"완료\" 알림 기준", GOLD_LIGHT),
    ("`finalized`", "2 epoch 완료 (~12분), 수학적 확정",   "사실상 불가",    "내부 원장 반영 기준",    SAFE_COLOR),
]
f_col_x = [0.6, 2.35, 6.3, 8.4, 10.85]
f_col_w = [1.68, 3.88, 2.03, 2.38, 1.92]
f_headers = ["블록 태그", "의미", "재조직", "수탁 운영 원칙", ""]

for ci, (h, x, w) in enumerate(zip(f_headers[:4], f_col_x[:4], f_col_w[:4])):
    rect(sl, x, 3.04, w - 0.06, 0.38, GOLD if ci > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 3.07, w-0.18, 0.30,
       size=12, bold=True, color=DARK if ci > 0 else GOLD, align=PP_ALIGN.CENTER)

for ri, (tag, meaning, reorg, principle, ac) in enumerate(finality_rows):
    y = 3.48 + ri * 0.68
    vals = [tag, meaning, reorg, principle]
    for ci, (val, x, w) in enumerate(zip(vals, f_col_x, f_col_w)):
        rect(sl, x, y, w - 0.06, 0.62, CARD_DARK if ri % 2 else CARD_NAVY)
        tb(sl, val, x+0.1, y+0.08, w-0.22, 0.50,
           size=12, bold=(ci==0), color=ac if ci==0 else WHITE)
    # 색상 마커
    rect(sl, f_col_x[3] + f_col_w[3], y, 0.25, 0.62, ac)

tb(sl, "L2 / 사이드체인 Finality 차이", 0.6, 5.60, 5.0, 0.38, size=13, bold=True, color=GOLD)
card(sl, 0.6, 5.98, 12.1, 0.70,
     body="Optimistic Rollup: L2 포함 빠름 / L1 finality까지 ~7일 챌린지  |  ZK Rollup: proof L1 제출 시점 (~수십 분)  |  Polygon PoS: L1 체크포인트 (~30분)",
     accent_bar=False, body_size=13)

# ═══════════════════════════════════════════
# SECTION 05
# ═══════════════════════════════════════════
slide_section(prs, "05", "EIP‑1559 수수료 모델")

# SL 18 — 기본 구조
sl = slide_body(prs, "05", "EIP‑1559 핵심 구조 — baseFee · tip · maxFee")

card(sl, 0.6,  1.6, 3.7, 2.6,
     title="Base Fee (baseFee)",
     body="프로토콜이 블록마다 자동 조정\n\n"
          "전 블록 가스 사용량 > 목표치\n→ baseFee 증가\n미만 → 감소\n\n"
          "baseFee는 소각(burn)\n→ 검증인 미수령",
     accent_bar=True)

card(sl, 4.55, 1.6, 3.7, 2.6,
     title="Priority Fee (tip)",
     body="사용자 → 블록 생산자에게\n지급하는 인센티브\n\n"
          "혼잡 시 priority fee 높이면\n더 빠른 처리\n\n"
          "채굴자/검증인이 수령하는\n유일한 수수료",
     accent_bar=True)

card(sl, 8.5,  1.6, 3.7, 2.6,
     title="Max Fee (maxFeePerGas)",
     body="사용자가 지불할\n최대 수수료 상한선\n\n"
          "실제 지불 =\nbaseFee + priorityFee\n\n"
          "baseFee + tip > maxFee\n→ 트랜잭션 거부",
     accent_bar=True)

tb(sl, "Legacy vs EIP‑1559", 0.6, 4.38, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 4.82, 11.5)

card(sl, 0.6,  4.88, 5.7, 0.96,
     title="Legacy (gasPrice 단일값)",
     body="경매 방식 → 수수료 예측 불가, 과납 빈번",
     accent_bar=False, bg_color=CARD_DARK)
card(sl, 6.65, 4.88, 5.7, 0.96,
     title="EIP‑1559",
     body="baseFee 예측 가능 범위 내 증감 → 지갑 자동 수수료 계산",
     accent_bar=False)

# SL 19 — Replacement와 결합
sl = slide_body(prs, "05", "EIP‑1559 교체(Replacement)와의 결합")

card(sl, 0.6, 1.6, 5.7, 2.5,
     title="교체 규칙 (EIP‑1559 트랜잭션)",
     body="maxFeePerGas AND maxPriorityFeePerGas\n모두 10% 이상 인상 필요\n\n"
          "미충족 → Geth\n\"replacement transaction underpriced\"\n\n"
          "Besu: 효과적 가스 가격 + priority fee\n두 조건 동시 검증",
     accent_bar=True)

card(sl, 6.65, 1.6, 5.7, 2.5,
     title="팁만 올리면 안 되는 이유",
     body="혼잡 시 baseFee 급격히 상승 가능\n\n"
          "priority fee만 올릴 경우:\nbaseFee + tip > 기존 maxFee\n→ 트랜잭션 무효화\n\n"
          "→ 교체 시 priority fee와\n  max fee 함께 인상 필수",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "수탁 운영 자동화 포인트", 0.6, 4.28, 5.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 4.72, 11.5)

numbered_list(sl, [
    "pending 시간 기준 fee bump 자동 실행 (예: 10분 후 +15%)",
    "baseFee 급등 모니터링 → maxFee 추가 여유(buffer) 확보",
    "mempool 상태 폴링 + 재전송 큐 자동화",
], left=0.6, top=4.80, row_h=0.56, text_size=15)

# SL — eth_estimateGas + gasLimit (NEW §5.3)
sl = slide_body(prs, "05", "Gas 추정과 gasLimit 설정 — eth_estimateGas")

card(sl, 0.6, 1.6, 12.1, 0.78,
     title="eth_estimateGas 원리",
     body="실제 전송 없이 실행 시뮬레이션 → 예상 gas 소비량 반환  |  revert 예상 시 오류 반환 → 수탁 지갑은 전송 전 차단 가능",
     accent_bar=True)

tb(sl, "상황별 대응", 0.6, 2.52, 4.0, 0.40, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.94, 11.5)

gas_rows = [
    ("정상 실행",          "예상값 반환",              "예상과 유사",           "추정값 × 1.2 gasLimit 설정"),
    ("revert 예상",        "오류(execution reverted)", "Out of Gas or revert",  "전송 전 즉시 출금 거절"),
    ("상태 변화 경쟁(race)","시뮬레이션 기준값",         "실행 시 gas 부족 가능", "버퍼를 1.5배로 확보"),
    ("EOA 단순 ETH 전송",  "21,000",                   "항상 21,000",           "고정값 사용 가능"),
]
gas_headers = ["상황", "estimateGas 반환", "실제 결과", "대응"]
gas_col_x = [0.6, 3.32, 6.42, 9.25]
gas_col_w = [2.65, 3.03, 2.76, 3.42]

for ci, (h, x, w) in enumerate(zip(gas_headers, gas_col_x, gas_col_w)):
    rect(sl, x, 3.00, w - 0.06, 0.38, GOLD if ci > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 3.03, w-0.18, 0.30,
       size=12, bold=True, color=DARK if ci > 0 else GOLD, align=PP_ALIGN.CENTER)

for ri, (s, est, act, resp) in enumerate(gas_rows):
    y = 3.44 + ri * 0.58
    row_bg = CARD_DARK if ri % 2 else CARD_NAVY
    for ci, (val, x, w) in enumerate(zip([s, est, act, resp], gas_col_x, gas_col_w)):
        rect(sl, x, y, w - 0.06, 0.52, row_bg)
        col_c = WARN if (ri == 1 and ci in [1,2]) else WHITE
        tb(sl, val, x+0.10, y+0.06, w-0.22, 0.42, size=12, color=col_c, bold=(ci==0))

tb(sl, "gasLimit 설정 원칙", 0.6, 5.80, 4.0, 0.38, size=13, bold=True, color=GOLD)
card(sl, 0.6, 6.18, 12.1, 0.60,
     body="ETH 전송: 21,000 고정  |  ERC-20: 추정×1.2~1.5  |  너무 낮으면 → Out of Gas revert(가스비 소진)  |  너무 높으면 → 초과분 반환(단, maxFee에 포함됨)",
     accent_bar=False, body_size=13)

# ═══════════════════════════════════════════
# SECTION 06
# ═══════════════════════════════════════════
slide_section(prs, "06", "EIP‑712 Typed Signing")

# SL 21 — EIP-712
sl = slide_body(prs, "06", "EIP‑712 구조와 도메인 분리 — 피싱 방어 실전 도구")

card(sl, 0.6, 1.6, 12.1, 1.05,
     title="동기",
     body="사용자가 의미를 이해하지 못한 채 서명(블라인드 서명) 방지  |  구조화된 데이터를 지갑 UI에 명시적으로 표시",
     accent_bar=True)

tb(sl, "서명 digest 계산", 0.6, 2.80, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.24, 11.5)

card(sl, 0.6,  3.32, 7.5, 1.15,
     body="digest = \\x19\\x01  ||  domainSeparator  ||  hashStruct(message)\n\n"
          "domainSeparator = hash(EIP712Domain { name, version, chainId, verifyingContract, salt })",
     accent_bar=False, body_size=15)

tb(sl, "도메인 분리 효과", 8.3, 3.32, 4.2, 0.42, size=14, bold=True, color=GOLD)
card(sl, 8.3,  3.75, 4.4, 0.95,
     body="chainId → 다른 체인 재사용 불가\nverifyingContract → 특정 컨트랙트에만 유효",
     accent_bar=False, body_size=14)

tb(sl, "지갑 구현 의무사항", 0.6, 4.62, 5.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 5.06, 11.5)

numbered_list(sl, [
    "chainId 불일치 시 서명 즉시 거부 (EIP‑712 Security Considerations)",
    "domainSeparator의 도메인명 + verifyingContract 주소를 서명 창에 강조 표시",
    "nonce/expiry 재사용 방지는 EIP‑712 표준 범위 밖 — 애플리케이션 구현자 책임",
], left=0.6, top=5.12, row_h=0.54, text_size=14)

# ═══════════════════════════════════════════
# SECTION 07
# ═══════════════════════════════════════════
slide_section(prs, "07", "확장 EIP 심화")

# SL 23 — EIP-2612
sl = slide_body(prs, "07", "EIP‑2612 Permit — 서명으로 Approve 대체")

card(sl, 0.6, 1.6, 5.7, 1.5,
     title="목적",
     body="ERC‑20 approve()를 오프체인 서명으로 대체\n"
          "제3자가 permit()을 제출해 allowance 설정 → 가스리스 UX",
     accent_bar=True)

card(sl, 6.65, 1.6, 5.7, 1.5,
     title="핵심 서명 필드",
     body="owner / spender / value / nonce / deadline\n\n"
          "nonce + deadline → replay 위험 감소",
     accent_bar=True)

tb(sl, "운영 관점", 0.6, 3.28, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.72, 11.5)

numbered_list(sl, [
    "\"전송\"이 아닌 \"승인 권한 부여\" — 두 개념 명확히 구분",
    "무제한 allowance(value = MAX_UINT256) 요청 → 정책 엔진 추가 검증 대상 분류",
    "피싱: 가짜 dApp이 무제한 승인 서명 요청 후 spender를 공격자 주소로 제출",
], left=0.6, top=3.80, row_h=0.64, text_size=15)

warn_bar(sl, "Permit = 가스비 절감이지만 사용자 교육 없이는 피싱 고위험 기능  |  과도한 allowance 정책 통제 필수")

# SL 24 — EIP-3009
sl = slide_body(prs, "07", "EIP‑3009 Transfer With Authorization — 서명으로 전송")

card(sl, 0.6, 1.6, 5.7, 1.5,
     title="목적",
     body="ERC‑20 토큰 전송을 오프체인 서명으로 승인\n"
          "제3자(relayer)가 온체인 제출 → 가스리스 전송",
     accent_bar=True)

card(sl, 6.65, 1.6, 5.7, 1.5,
     title="핵심 서명 필드",
     body="from / to / value / validAfter\n/ validBefore / nonce\n\n"
          "만료시간(validBefore) + EIP‑712 도메인 분리 함께 사용",
     accent_bar=True)

tb(sl, "수탁 운영 통제", 0.6, 3.28, 4.0, 0.42, size=15, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.72, 11.5)

numbered_list(sl, [
    "일반 출금과 동일하게 정책 엔진 / 승인 / 감사 체인으로 통제",
    "relayer가 제출하더라도 내부 원장 반영은 finality 확인 이후",
    "컨트랙트에서 nonce 재사용 반드시 차단 (usedNonces mapping)",
], left=0.6, top=3.80, row_h=0.64, text_size=15)

warn_bar(sl, "리스크: 잘못된 수신자/금액으로 직접 전송 위험  |  Permit(승인)과 달리 즉시 자산 이동 발생")

# SL 25 — 비교
sl = slide_body(prs, "07", "EIP‑2612 vs EIP‑3009 비교")

headers2 = ["구분", "EIP‑2612 Permit", "EIP‑3009 Transfer With Auth"]
rows2 = [
    ("1차 목적",    "승인 권한 부여 (permit)",               "전송 권한 실행 (transferWithAuthorization)"),
    ("온체인 결과",  "allowance 상태 변경 (approve 대체)",    "토큰 이동(transfer) 발생"),
    ("핵심 서명 필드","owner/spender/value/nonce/deadline",  "from/to/value/validAfter/validBefore/nonce"),
    ("리스크 포인트","과도한 allowance 남용",                "잘못된 수신자·금액 직접 전송"),
    ("운영 통제",   "승인 정책 + spender 검증",              "전송 정책 + finality 반영"),
    ("실무 해석",   "\"서명으로 승인\"",                     "\"서명으로 전송\""),
]

col_w2 = [2.2, 4.8, 4.8]
col_x2 = [0.6, 2.9, 7.85]

for i, (h, x, w) in enumerate(zip(headers2, col_x2, col_w2)):
    rect(sl, x, 1.62, w - 0.08, 0.44, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x + 0.08, 1.66, w - 0.2, 0.36,
       size=14, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

row_colors2 = [CARD_NAVY, CARD_DARK, CARD_NAVY, CARD_DARK, CARD_NAVY, CARD_DARK]
for ri, (label, v1, v2) in enumerate(rows2):
    y = 2.12 + ri * 0.67
    h = 0.62
    rect(sl, col_x2[0], y, col_w2[0] - 0.08, h, row_colors2[ri])
    tb(sl, label, col_x2[0] + 0.1, y + 0.06, col_w2[0] - 0.22, h - 0.1,
       size=13, bold=True, color=GOLD)
    rect(sl, col_x2[1], y, col_w2[1] - 0.08, h, row_colors2[ri])
    tb(sl, v1, col_x2[1] + 0.1, y + 0.04, col_w2[1] - 0.22, h - 0.08,
       size=12, color=WHITE)
    rect(sl, col_x2[2], y, col_w2[2] - 0.08, h, row_colors2[ri])
    tb(sl, v2, col_x2[2] + 0.1, y + 0.04, col_w2[2] - 0.22, h - 0.08,
       size=12, color=WHITE)

highlight_bar(sl, "두 EIP 모두 EIP‑712 서명 기반 가스리스 UX  |  목적: Permit = 승인,  3009 = 전송  — 혼동 금지")

# SL 26 — EIP-4337 + x402
sl = slide_body(prs, "07", "EIP‑4337 Account Abstraction · EIP‑2930 · x402")

card(sl, 0.6, 1.6, 3.7, 4.3,
     title="EIP‑4337\nAccount Abstraction",
     body="지갑을 스마트 컨트랙트로 구현\n— EOA 한계 극복\n\n"
          "수수료를 다른 토큰으로 지불\n번들러(Bundler) 통해 처리\n\n"
          "수탁 UX 개선 활용:\n소셜 복구, 세션 키,\n다중 서명 정책 등\n\n"
          "향후 수탁형 지갑 아키텍처\n핵심 참조 표준",
     accent_bar=True)

card(sl, 4.55, 1.6, 3.7, 4.3,
     title="EIP‑2930\nAccess List (Type 0x01)",
     body="트랜잭션에 접근할\nstorage 키 목록 미리 제공\n\n"
          "Gas 비용 예측 가능\n→ 수수료 모델과\n  nonce 교체 전략에 영향\n\n"
          "EIP‑1559와 함께 도입된\nType 0x01 트랜잭션",
     accent_bar=True)

card(sl, 8.5, 1.6, 3.7, 4.3,
     title="Coinbase x402\n결제 오케스트레이션",
     body="HTTP 402 기반\nAPI 결제 흐름 연결\n체인 합의 레이어가 아닌\nAPI 결제 레이어\n\n"
          "수탁 관점 핵심:\n엔드포인트별 결제 한도\n자동 결제 키 분리 관리\n\n"
          "idempotency key +\nnonce + 만료시간 강제\n결제 이벤트 → 내부 원장",
     accent_bar=True)

# ═══════════════════════════════════════════
# SECTION 08
# ═══════════════════════════════════════════
slide_section(prs, "08", "결론 및 핵심 메시지")

# SL 28 — 결론
sl = slide_body(prs, "08", "Session 1 핵심 메시지 5가지")

messages = [
    ("①", "지갑 = 서명 권한 관리 시스템",
          "키 보관이 아니라 서명 권한을 안전하게 관리하는 시스템"),
    ("②", "트랜잭션 서명",
          "RLP + nonce + chainId(EIP‑155) + EIP‑1559 fee 구조 — 프로토콜 레벨 보호"),
    ("③", "메시지 서명",
          "EIP‑191 프리픽스 / EIP‑712 도메인 분리 → 피싱 방어 — 앱 레벨 nonce 필수"),
    ("④", "수탁 운영 핵심",
          "(from, nonce) 상태 머신 + 자동 fee bump + dropped 대응 자동화"),
    ("⑤", "사용자 교육 & UI",
          "피싱은 기술보다 사람의 허점을 노림 — 서명 내용 명확히 표시 + 교육"),
]

accent_colors = [GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD]
for i, (num, head, body_t) in enumerate(messages):
    y = 1.62 + i * 0.92
    rect(sl, 0.6, y, 0.52, 0.76, NAVY)
    tb(sl, num, 0.62, y + 0.12, 0.48, 0.52,
       size=16, bold=True, color=GOLD, align=PP_ALIGN.CENTER)
    rect(sl, 1.22, y, 11.1, 0.76, CARD_NAVY)
    rect(sl, 1.22, y, 0.06, 0.76, accent_colors[i])
    tb(sl, head, 1.38, y + 0.04, 3.5, 0.36,
       size=15, bold=True, color=accent_colors[i])
    tb(sl, body_t, 1.38, y + 0.38, 10.8, 0.34,
       size=14, color=WHITE)

highlight_bar(sl, "서명 구조를 이해하는 것이 수탁형 지갑 설계의 출발점  |  다음 Session: 키 관리 아키텍처")

# ═══════════════════════════════════════════
# SL 29 — 마무리
# ═══════════════════════════════════════════
slide_thanks(prs, website="coincraft.io")

save(prs, OUT)
