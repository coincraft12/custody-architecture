"""
수탁형 지갑 백엔드 설계 — Session 2
표준 7모듈 아키텍처 · HSM/MPC · 내부 원장 · 상태 머신
CoinCraft 공식 템플릿(coincraft_ppt_base) 기반
"""
import sys
sys.path.insert(0, r"f:\Workplace\CoinCraft-Content-Studio\03_ASSETS\Scripts")
from coincraft_ppt_base import *

OUT = r"f:\Workplace\custody\custody track\Session2_Custody_Reference_Architecture.pptx"

SAFE_COLOR = RGBColor(0x52, 0xB7, 0x88)   # 녹색 (ACTIVE/APPLIED/finalized 표시용)
RED_COLOR  = RGBColor(0xE0, 0x50, 0x50)   # 적색 (REJECTED/CANCELLED 표시용)

prs = new_presentation()

# ═══════════════════════════════════════════
# SL 1 — 표지
# ═══════════════════════════════════════════
slide_cover(
    prs,
    title="수탁형 지갑 설계\nSession 2",
    subtitle="표준 7모듈 아키텍처 · HSM/MPC · 내부 원장 · 상태 머신",
    bullets=["7모듈 아키텍처 — 책임·경계·불변 규칙", "HSM vs MPC 선택 기준", "화이트리스트·정책 변경 상태 머신"],
    date_line="2026.03  |  CoinCraft Custody Track"
)

# ═══════════════════════════════════════════
# SL 2 — 학습 목표
# ═══════════════════════════════════════════
sl = slide_body(prs, "", "학습 목표 — Learning Objectives")

objectives = [
    "7모듈 아키텍처: 각 모듈의 책임·입출력·불변 규칙을 설명하고, 모듈 경계가 왜 보안과 같은 의미인지 말할 수 있다",
    "Policy/Approval/Signer 분리: 분리하지 않을 때 발생하는 사고 시나리오 3가지를 설명하고, 분리가 각 위험을 어떻게 차단하는지 말할 수 있다",
    "HSM vs MPC: 두 방식의 키 보관 원리·장단점·선택 기준을 비교하여 설명할 수 있다",
    "Signer 입력 계약: Signer가 반드시 검증해야 할 필드와 거부해야 하는 케이스를 열거할 수 있다",
    "내부 원장 4단계: Available / Reserved / Pending / Settled 잔고 상태의 의미와 전환 시점을 설명할 수 있다",
    "화이트리스트 상태 머신: 주소 등록부터 활성화까지 단계와 48시간 보류의 이유를 말할 수 있다",
    "정책 변경 상태 머신: 정책 변경이 즉시 적용되지 않고 지연·쿼럼 승인을 거쳐야 하는 이유를 설명할 수 있다",
    "입금 플로우: 입금 감지부터 원장 크레딧까지 흐름을 출금 플로우와 비교하여 설명할 수 있다",
]

accent_colors = [GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT]
for i, (obj, ac) in enumerate(zip(objectives, accent_colors)):
    y = 1.60 + i * 0.59
    rect(sl, 0.6, y, 0.44, 0.48, NAVY)
    tb(sl, str(i+1), 0.62, y+0.06, 0.40, 0.36,
       size=14, bold=True, color=GOLD, align=PP_ALIGN.CENTER)
    rect(sl, 1.14, y, 11.56, 0.48, CARD_NAVY)
    rect(sl, 1.14, y, 0.06, 0.48, ac)
    tb(sl, obj, 1.28, y+0.06, 11.3, 0.38, size=12.5, color=WHITE)

highlight_bar(sl, "Session 1 전제 지식: ECDSA 서명 · Nonce 관리 · Finality  →  이 세션 Signer·Broadcaster·Confirmation Tracker 설계의 직접 근거")

# ═══════════════════════════════════════════
# SL 3 — 목차
# ═══════════════════════════════════════════
slide_agenda(prs, [
    ("01", "표준 7모듈 아키텍처 — 책임·경계·데이터"),
    ("02", "Approval과 Signer 분리 — 사고 시나리오"),
    ("03", "HSM vs MPC — 선택 기준과 차이"),
    ("04", "상태 머신 — 화이트리스트 · 정책 변경"),
    ("05", "내부 원장 설계 — 잔고 · 정산 · 대사 · 감사"),
    ("06", "입금(Deposit) 플로우"),
])

# ═══════════════════════════════════════════
# SECTION 01 — 7모듈 아키텍처
# ═══════════════════════════════════════════
slide_section(prs, "01", "표준 7모듈 아키텍처")

# SL — 전체 아키텍처 다이어그램
sl = slide_body(prs, "01", "전체 아키텍처 — 입력·출력 경계")

modules_flow = [
    ("외부 시스템 / 사용자",   "출금 요청 (idempotency key 포함)",                                    CARD_DARK,  GRAY_LIGHT, False),
    ("API / Orchestrator",     "요청 수신 · 워크플로우 오케스트레이션 · 중복 실행 방지",              CARD_NAVY,  GOLD,       True),
    ("Policy Engine",          "금액 · 주소 · 속도 · 화이트리스트 판정  →  허용 / 거절 / 추가검증",  CARD_NAVY,  GOLD,       True),
    ("Approval",               "4-eyes / 쿼럼 승인 · 다중 독립 디바이스  →  승인 / 반려 / 보류",     CARD_DARK,  WARN,       True),
    ("Signer  (HSM / MPC)",    "[최후 방어선]  서명만 수행 · 정책 판단 금지  →  signed tx",          CARD_DARK,  WARN,       True),
    ("Broadcaster",            "온체인 전파 · replacement / speed-up 처리  →  tx hash",              CARD_NAVY,  GOLD,       True),
    ("Confirmation Tracker",   "(from, nonce) 기반 추적 · latest / safe / finalized 구분",           CARD_NAVY,  GOLD_LIGHT, True),
    ("Ledger & Audit",         "단일 진실(SoT) · Available/Reserved/Pending/Settled · 감사 로그",    CARD_DARK,  GOLD_LIGHT, True),
]

box_h = 0.44
gap_h = 0.14
start_y = 1.58

for i, (name, desc, bg, ac, is_module) in enumerate(modules_flow):
    y = start_y + i * (box_h + gap_h)
    if i > 0:
        tb(sl, "▼", 6.05, y - gap_h, 0.9, gap_h,
           size=9, color=GRAY_LIGHT, align=PP_ALIGN.CENTER)
    rect(sl, 0.6, y, 12.1, box_h, bg)
    rect(sl, 0.6, y, 0.06, box_h, ac)
    tb(sl, name, 0.76, y+0.04, 3.7, box_h-0.06,
       size=12.5 if is_module else 12, bold=is_module,
       color=ac if is_module else GRAY_LIGHT)
    tb(sl, desc, 4.6, y+0.04, 8.0, box_h-0.06, size=11, color=WHITE)

highlight_bar(sl, "핵심 원칙: Policy / Approval = \"허용 여부\"  |  Signer = \"서명만\"  |  권한과 키를 같은 곳에 두지 않는다")

# SL — 모듈 역할 상세 Part 1 (API ~ Signer)
sl = slide_body(prs, "01", "모듈 역할 상세 ① — API / Policy / Approval / Signer")

m_col_x = [0.6, 4.3, 9.45]
m_col_w = [3.62, 5.07, 3.22]

for i, (h, x, w) in enumerate(zip(["모듈", "입력 → 출력", "불변 규칙"], m_col_x, m_col_w)):
    rect(sl, x, 1.60, w - 0.08, 0.40, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 1.63, w-0.18, 0.32,
       size=13, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

modules_d1 = [
    ("API /\nOrchestrator",  "요청(request)\n→ 워크플로우 상태(event)",          "중복 실행 금지\n(idempotency key)",        GOLD),
    ("Policy Engine",        "출금 초안\n→ 허용 / 거절 / 추가검증",               "승인 없이 Signer로\n직접 전달 불가",       GOLD),
    ("Approval",             "정책 통과 건\n→ 승인 여부",                         "승인 없이는 서명 금지",                    WARN),
    ("Signer\n(HSM / MPC)",  "서명 요청\n→ 서명 결과",                            "정책 판단 금지\n서명만 수행",              WARN),
]

for ri, (name, io, rule, ac) in enumerate(modules_d1):
    y = 2.06 + ri * 1.05
    h = 0.98
    bg = CARD_DARK if ri % 2 else CARD_NAVY
    rect(sl, m_col_x[0], y, m_col_w[0]-0.08, h, bg)
    rect(sl, m_col_x[0], y, 0.06, h, ac)
    tb(sl, name, m_col_x[0]+0.12, y+0.08, m_col_w[0]-0.24, h-0.14,
       size=13.5, bold=True, color=ac)
    rect(sl, m_col_x[1], y, m_col_w[1]-0.08, h, bg)
    tb(sl, io, m_col_x[1]+0.12, y+0.08, m_col_w[1]-0.24, h-0.14,
       size=12.5, color=WHITE)
    rect(sl, m_col_x[2], y, m_col_w[2]-0.08, h, bg)
    rect(sl, m_col_x[2], y, 0.06, h, WARN)
    tb(sl, rule, m_col_x[2]+0.12, y+0.08, m_col_w[2]-0.24, h-0.14,
       size=12.5, color=WHITE)

highlight_bar(sl, "Policy → Approval → Signer: 각 단계는 다음 단계의 \"입장권\" 발급  |  경계 = 보안")

# SL — 모듈 역할 상세 Part 2 (Broadcaster ~ Ledger)
sl = slide_body(prs, "01", "모듈 역할 상세 ② — Broadcaster / Confirmation Tracker / Ledger")

for i, (h, x, w) in enumerate(zip(["모듈", "입력 → 출력", "불변 규칙"], m_col_x, m_col_w)):
    rect(sl, x, 1.60, w - 0.08, 0.40, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 1.63, w-0.18, 0.32,
       size=13, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

modules_d2 = [
    ("Broadcaster",          "서명된 트랜잭션\n→ 전파 결과 (tx hash)",            "중복 전파해도 결과 동일\n(idempotent)",    GOLD),
    ("Confirmation\nTracker","tx hash + (from, nonce)\n→ 포함/실패/교체 이벤트",  "nonce 기반 추적\n여러 블록 확인 후 반영", GOLD_LIGHT),
    ("Ledger &\nAudit",      "모든 상태 이벤트\n→ 회계적 원장 + 감사 로그",      "체인 ≠ 원장\n내부 원장이 단일 진실",      GOLD_LIGHT),
]

for ri, (name, io, rule, ac) in enumerate(modules_d2):
    y = 2.06 + ri * 1.05
    h = 0.98
    bg = CARD_DARK if ri % 2 else CARD_NAVY
    rect(sl, m_col_x[0], y, m_col_w[0]-0.08, h, bg)
    rect(sl, m_col_x[0], y, 0.06, h, ac)
    tb(sl, name, m_col_x[0]+0.12, y+0.08, m_col_w[0]-0.24, h-0.14,
       size=13.5, bold=True, color=ac)
    rect(sl, m_col_x[1], y, m_col_w[1]-0.08, h, bg)
    tb(sl, io, m_col_x[1]+0.12, y+0.08, m_col_w[1]-0.24, h-0.14,
       size=12.5, color=WHITE)
    rect(sl, m_col_x[2], y, m_col_w[2]-0.08, h, bg)
    rect(sl, m_col_x[2], y, 0.06, h, WARN)
    tb(sl, rule, m_col_x[2]+0.12, y+0.08, m_col_w[2]-0.24, h-0.14,
       size=12.5, color=WHITE)

card(sl, 0.6, 5.25, 12.1, 0.96,
     title="목표 아키텍처 vs 실습 구현",
     body="Confirmation Tracker: included/safe/finalized 구분  →  실습: receipt 발견 기준 단순화  |  Ledger: finality 충족 후 settle  →  실습: reserve 시점 중심  |  \"다이어그램은 완성형, 코드는 핵심 경계 최소 구현\"",
     accent_bar=True, body_size=12.5)

# ═══════════════════════════════════════════
# SECTION 02 — Approval & Signer 분리
# ═══════════════════════════════════════════
slide_section(prs, "02", "Approval과 Signer 분리 — 사고 시나리오")

# SL — 사고 시나리오 3가지
sl = slide_body(prs, "02", "분리하지 않을 때 — 사고 시나리오 3가지")

card(sl, 0.6,  1.58, 3.8, 3.0,
     title="① 내부자 · 키 탈취",
     body="Signer가 정책 판단까지 수행 →\n키 탈취 공격자가 정책 우회 후\n대량 출금 즉시 실행 가능\n\nNCC Group: 승인 없는 메시지 큐를\n그대로 처리하면 악성 요청이\n승인 없이 실행될 수 있음",
     accent_bar=True, bg_color=CARD_DARK)
card(sl, 4.60, 1.58, 3.8, 3.0,
     title="② 피싱 · 오남용",
     body="서명 요청에 승인 절차 없음 →\n정상처럼 보이는 악성 요청 차단 불가\n\nDeFi 사이트 위조 →\n무제한 token approval 요청 →\n정책·승인 없으면 키 보유자 서명",
     accent_bar=True, bg_color=CARD_DARK)
card(sl, 8.60, 1.58, 3.8, 3.0,
     title="③ 책임 소재 붕괴",
     body="정책 결정 + 서명이 한 모듈 →\n사고 시 누가 어떤 이유로\n승인했는지 추적 불가\n\n감사 추적·포렌식을 위해\n정책 판단·사람 승인·서명 로그를\n명확히 분리해야 함",
     accent_bar=True, bg_color=CARD_DARK)

tb(sl, "분리했을 때 얻는 통제 포인트", 0.6, 4.72, 6.0, 0.42, size=14, bold=True, color=GOLD)
gold_line(sl, 0.6, 5.16, 11.5)

numbered_list(sl, [
    "정책·승인: 출금 한도·속도 제한·화이트리스트로 1차 위험 차단. 다중 기기 승인 → 단일 워크스테이션 장악해도 승인 통과 불가",
    "Signer 단순화: 정책이 반영된 구조화 입력만 검증 → 공격면 최소화. 서명 모듈 자체를 단순하게 유지",
    "포렌식 가능 로그 체인: trace ID로 요청·정책·승인·서명·체인 결과를 재구성 가능 — 감사 요건 핵심",
], left=0.6, top=5.22, row_h=0.52, text_size=13.5)

# SL — Signer 입력 계약
sl = slide_body(prs, "02", "Signer 입력 계약 — 무엇을 받고 무엇을 거부하는가")

card(sl, 0.6, 1.58, 12.1, 0.70,
     title="설계 원칙",
     body="\"Signer는 정책을 결정하지 않지만, 정책이 반영된 구조화 입력을 검증하는 마지막 방어선이어야 한다\"",
     accent_bar=True)

tb(sl, "필수 검증 필드", 0.6, 2.42, 4.0, 0.38, size=13, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.82, 6.6)

input_fields = [
    ("request_id",                       "재사용된 요청 ID가 아닌가"),
    ("chain_id / verifying_contract",    "허용된 체인·계약인가  /  replay 위험 없는가"),
    ("from, to, amount",                 "승인된 payload와 바이트 단위로 일치하는가"),
    ("nonce",                            "이미 사용된 nonce가 아닌가"),
    ("policy_decision_id /\napproval_bundle_id", "승인된 정책 결정과 연결·quorum 충족했는가"),
    ("signing_scope",                    "광범위 권한 위임(무제한 allowance)이 아닌가"),
]

for ri, (field, check) in enumerate(input_fields):
    y = 2.88 + ri * 0.52
    bg = CARD_DARK if ri % 2 == 0 else CARD_NAVY
    rect(sl, 0.6, y, 3.0, 0.46, bg)
    rect(sl, 0.6, y, 0.06, 0.46, GOLD_LIGHT)
    tb(sl, field, 0.76, y+0.04, 2.80, 0.38, size=11, color=GOLD_LIGHT, bold=True)
    rect(sl, 3.68, y, 3.50, 0.46, bg)
    tb(sl, check, 3.80, y+0.06, 3.34, 0.36, size=11, color=WHITE)

tb(sl, "거부해야 할 케이스", 7.40, 2.42, 5.0, 0.38, size=13, bold=True, color=WARN)
gold_line(sl, 7.40, 2.82, 5.3)

reject_cases = [
    "승인된 to 주소와 실제 payload의 to 주소가 다를 때",
    "chain_id 또는 verifying_contract가 정책 범위를 벗어날 때",
    "만료된 승인 토큰이나 이미 사용된 request_id 재전송",
    "\"이번 한 건\" 승인인데 무제한 allowance나\n임의 calldata가 들어올 때",
]

for ri, case in enumerate(reject_cases):
    y = 2.88 + ri * 0.82
    rect(sl, 7.40, y, 5.28, 0.74, CARD_DARK if ri % 2 == 0 else CARD_NAVY)
    rect(sl, 7.40, y, 0.06, 0.74, RED_COLOR)
    tb(sl, f"❌  {case}", 7.56, y+0.08, 5.08, 0.60, size=11.5, color=WHITE)

# ═══════════════════════════════════════════
# SECTION 03 — HSM vs MPC
# ═══════════════════════════════════════════
slide_section(prs, "03", "HSM vs MPC — 선택 기준과 차이")

sl = slide_body(prs, "03", "HSM vs MPC — 키 보관 방식부터 선택 기준까지")

hm_col_x = [0.6, 4.3, 8.35]
hm_col_w = [3.62, 3.97, 4.32]

for i, (h, x, w) in enumerate(zip(["항목", "HSM", "MPC"], hm_col_x, hm_col_w)):
    rect(sl, x, 1.58, w-0.06, 0.38, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 1.61, w-0.18, 0.30,
       size=13, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

hsm_mpc_rows = [
    ("키 보관\n방식",    "물리적 하드웨어 내 단일 키 저장\n키는 절대 외부로 나오지 않음",    "키를 수학적 분할(key share)하여\n여러 서버·디바이스에 분산 저장"),
    ("서명 방식",        "HSM 내부에서 단독 서명 연산",                                        "분산된 키 조각이 프로토콜로 공동 서명\n키가 한 곳에 모이지 않음"),
    ("단일\n장애 지점",  "있음. 장치 고장·분실 시 서명 불가\n→ 백업 HSM 필요",               "없음. t-of-n 이상 참여자 가용 시 서명 가능"),
    ("지리적\n분산",     "어려움. 물리 장치 이동·복제 복잡",                                   "용이. 키 조각을 지리적으로 분산 배치 가능"),
    ("인증·\n규정",      "FIPS 140-2 Level 3/4 인증\n규제기관이 익숙한 검증 방식",            "표준 인증 체계 미성숙\n일부 규제기관 추가 검증 요구"),
    ("구현\n복잡도",     "상대적으로 단순\nPKCS#11 등 표준 인터페이스 사용",                   "높음. 분산 프로토콜 구현·운영\n네트워크 장애 처리 필요"),
    ("적합\n케이스",     "규제 요건 엄격, 소수 고가치 자산\n검증된 방식 선호",                "대규모 운영, 지리적 분산 필요\n단일 장애 지점 제거 우선"),
]

row_h_hm = 0.60
for ri, (item, hsm_v, mpc_v) in enumerate(hsm_mpc_rows):
    y = 2.02 + ri * row_h_hm
    bg = CARD_DARK if ri % 2 == 0 else CARD_NAVY
    rect(sl, hm_col_x[0], y, hm_col_w[0]-0.06, row_h_hm-0.06, bg)
    rect(sl, hm_col_x[0], y, 0.06, row_h_hm-0.06, GOLD_LIGHT)
    tb(sl, item, hm_col_x[0]+0.12, y+0.05, hm_col_w[0]-0.22, row_h_hm-0.14,
       size=12, bold=True, color=GOLD_LIGHT)
    rect(sl, hm_col_x[1], y, hm_col_w[1]-0.06, row_h_hm-0.06, bg)
    tb(sl, hsm_v, hm_col_x[1]+0.10, y+0.04, hm_col_w[1]-0.20, row_h_hm-0.12,
       size=11, color=WHITE)
    rect(sl, hm_col_x[2], y, hm_col_w[2]-0.06, row_h_hm-0.06, bg)
    tb(sl, mpc_v, hm_col_x[2]+0.10, y+0.04, hm_col_w[2]-0.20, row_h_hm-0.12,
       size=11, color=WHITE)

warn_bar(sl, "실무 핵심: \"MPC가 무조건 낫다\"는 없음  |  많은 기관이 HSM + MPC 함께 사용 (키 조각 자체를 HSM에 저장)  |  t-of-n 예: 2-of-3")

# ═══════════════════════════════════════════
# SECTION 04 — 상태 머신
# ═══════════════════════════════════════════
slide_section(prs, "04", "상태 머신 — 화이트리스트 · 정책 변경")

# SL — 화이트리스트 주소 상태 머신
sl = slide_body(prs, "04", "화이트리스트 주소 상태 머신")

card(sl, 0.6, 1.58, 12.1, 0.74,
     title="왜 화이트리스트가 필요한가",
     body="가장 흔한 사고 유형: 공격자가 출금 주소를 변조. 화이트리스트는 이를 방어하는 1차 구조적 통제. 주소는 등록 즉시 사용 불가 — 반드시 승인·보류 기간을 거쳐야 함",
     accent_bar=True, bg_color=CARD_DARK)

# State flow row
st_w = 2.62
st_gap = 0.34
st_y = 2.46
st_h = 1.00

wl_states = [
    ("REGISTERED",       "주소 등록 요청 접수\n초기 유효성 검사",              CARD_NAVY, GOLD),
    ("APPROVAL_PENDING", "담당자 승인 대기\n(AML/KYC 스크리닝)",               CARD_DARK, WARN),
    ("HOLDING",          "승인 완료\n보류 기간 대기 (기본 48h)",                CARD_DARK, WARN),
    ("ACTIVE",           "출금 가능 상태\n(AML 주기적 재스크리닝)",            CARD_NAVY, SAFE_COLOR),
]

for i, (state, desc, bg, ac) in enumerate(wl_states):
    x = 0.6 + i * (st_w + st_gap)
    rect(sl, x, st_y, st_w, st_h, bg)
    rect(sl, x, st_y, st_w, 0.06, ac)
    tb(sl, state, x+0.1, st_y+0.12, st_w-0.2, 0.36,
       size=11.5, bold=True, color=ac, align=PP_ALIGN.CENTER)
    tb(sl, desc, x+0.1, st_y+0.50, st_w-0.2, 0.46,
       size=10.5, color=WHITE, align=PP_ALIGN.CENTER)
    if i < len(wl_states) - 1:
        arr_x = x + st_w + 0.02
        tb(sl, "►", arr_x, st_y + 0.32, st_gap-0.04, 0.36,
           size=14, color=GOLD, align=PP_ALIGN.CENTER)

# REJECTED boxes (under APPROVAL_PENDING and HOLDING)
for i, (state_i, label, note) in enumerate([(1, "REJECTED", "승인 거절"), (2, "REJECTED", "보류 중 취소")]):
    x = 0.6 + state_i * (st_w + st_gap)
    bx_y = 3.60
    tb(sl, "↓", x + st_w/2 - 0.15, st_y + st_h, 0.30, 0.14,
       size=10, color=RED_COLOR, align=PP_ALIGN.CENTER)
    rect(sl, x, bx_y, st_w, 0.74, CARD_DARK)
    rect(sl, x, bx_y, st_w, 0.06, RED_COLOR)
    tb(sl, label, x+0.1, bx_y+0.12, st_w-0.2, 0.30,
       size=11.5, bold=True, color=RED_COLOR, align=PP_ALIGN.CENTER)
    tb(sl, note, x+0.1, bx_y+0.44, st_w-0.2, 0.24,
       size=10, color=WHITE, align=PP_ALIGN.CENTER)

# Explanation
tb(sl, "48시간 보류의 이유", 0.6, 4.48, 4.5, 0.38, size=13, bold=True, color=GOLD)
gold_line(sl, 0.6, 4.88, 11.5)

card(sl, 0.6, 4.94, 12.1, 0.98,
     body="계정 탈취 공격자가 새 주소 등록 후 즉시 출금하는 시나리오 차단  |  보류 기간 동안 정상 계정 주인이 이상 알림을 확인하고 취소할 수 있는 시간 확보  |  화이트리스트 변경은 출금 승인과 별도 쿼럼으로 처리  |  ACTIVE 주소도 주기적 AML 재스크리닝 대상",
     accent_bar=False, body_size=12.5)

# SL — 정책 변경 상태 머신
sl = slide_body(prs, "04", "정책 변경(Policy Change) 상태 머신")

card(sl, 0.6, 1.58, 12.1, 0.74,
     title="왜 정책 변경이 즉시 적용되면 안 되는가",
     body="공격자가 내부 시스템 접근 → 정책 먼저 완화 → 대량 출금 실행 시나리오가 실제 사고에서 반복. 정책 변경 자체가 고위험 이벤트",
     accent_bar=True, bg_color=CARD_DARK)

pc_states = [
    ("PROPOSED",        "정책 변경안 제출\n내용·변경자 기록",                CARD_NAVY, GOLD),
    ("QUORUM_APPROVED", "별도 쿼럼(최소 2인)\n변경안 검토·승인",             CARD_DARK, WARN),
    ("DELAYED",         "자동 적용 지연\n(기본 24~72시간)",                  CARD_DARK, WARN),
    ("APPLIED",         "정책 실제 적용\n변경 이력 불변 기록",               CARD_NAVY, SAFE_COLOR),
]

for i, (state, desc, bg, ac) in enumerate(pc_states):
    x = 0.6 + i * (st_w + st_gap)
    rect(sl, x, 2.48, st_w, st_h, bg)
    rect(sl, x, 2.48, st_w, 0.06, ac)
    tb(sl, state, x+0.1, 2.60, st_w-0.2, 0.36,
       size=11, bold=True, color=ac, align=PP_ALIGN.CENTER)
    tb(sl, desc, x+0.1, 2.98, st_w-0.2, 0.46,
       size=10.5, color=WHITE, align=PP_ALIGN.CENTER)
    if i < len(pc_states) - 1:
        arr_x = x + st_w + 0.02
        tb(sl, "►", arr_x, 2.48+0.32, st_gap-0.04, 0.36,
           size=14, color=GOLD, align=PP_ALIGN.CENTER)

# REJECTED / CANCELLED branches
for i, (state_i, label, note) in enumerate([(1, "REJECTED", "쿼럼 부결"), (2, "CANCELLED", "지연 중 긴급 취소")]):
    x = 0.6 + state_i * (st_w + st_gap)
    bx_y = 3.62
    tb(sl, "↓", x + st_w/2 - 0.15, 2.48 + st_h, 0.30, 0.14,
       size=10, color=RED_COLOR, align=PP_ALIGN.CENTER)
    rect(sl, x, bx_y, st_w, 0.74, CARD_DARK)
    rect(sl, x, bx_y, st_w, 0.06, RED_COLOR)
    tb(sl, label, x+0.1, bx_y+0.12, st_w-0.2, 0.30,
       size=11.5, bold=True, color=RED_COLOR, align=PP_ALIGN.CENTER)
    tb(sl, note, x+0.1, bx_y+0.44, st_w-0.2, 0.24,
       size=10, color=WHITE, align=PP_ALIGN.CENTER)

card(sl, 0.6, 4.50, 12.1, 1.68,
     title="설계 원칙",
     body="① 정책 변경 워크플로우는 출금 승인과 완전히 분리\n"
          "② 변경안 내용(before / after diff), 제안자, 승인자, 적용 시각을 감사 로그에 기록\n"
          "③ 지연 기간 중 변경안을 모니터링 시스템에 노출 → 운영팀이 검토 가능하게 설계\n"
          "④ 긴급 즉시 적용 경로는 최고 권한(CEO/CTO 쿼럼)으로 제한",
     accent_bar=True, body_size=12.5)

# ═══════════════════════════════════════════
# SECTION 05 — 내부 원장
# ═══════════════════════════════════════════
slide_section(prs, "05", "내부 원장 설계 — 잔고 · 정산 · 대사 · 감사")

# SL — 잔고 4단계
sl = slide_body(prs, "05", "내부 원장 — 잔고 4단계 상태 모델")

card(sl, 0.6, 1.58, 12.1, 0.74,
     title="핵심 오해 수정",
     body="\"체인 잔고 = 우리 잔고\" → ❌  |  내부 원장이 단일 진실(SoT), 블록체인은 외부 증빙  |  고객별 잔고는 온체인 주소 잔고와 별도 관리 필수  |  잔고는 스냅샷이 아닌 저널(이벤트 기록) 중심 설계",
     accent_bar=True, bg_color=CARD_DARK)

balance_states = [
    ("Available",  "사용 가능 잔고\n출금 중인 금액 미리 차감\n→ 즉시 사용 가능 금액\n→ 출금 한도 계산 기준",  SAFE_COLOR),
    ("Reserved",   "출금 요청 시 예약·홀드\n아직 체인 미전파\n→ 오버스펜딩 방지\n→ Pending 전 단계",        WARN),
    ("Pending",    "체인 전파 후 확정 대기\nfinality 충족 전\n→ 취소 가능성 있음\n→ 입금 감지 시 최초 상태",  GOLD),
    ("Settled",    "finality 충족 후 확정\n내부 원장 최종 반영\n→ append-only 기록\n→ 재구성 불가 최종 상태", GOLD_LIGHT),
]

bal_w = 2.82
bal_h = 2.38
bal_gap = 0.14
for i, (name, desc, ac) in enumerate(balance_states):
    x = 0.6 + i * (bal_w + bal_gap)
    rect(sl, x, 2.46, bal_w, bal_h, CARD_DARK if i % 2 == 0 else CARD_NAVY)
    rect(sl, x, 2.46, bal_w, 0.06, ac)
    tb(sl, name, x+0.1, 2.58, bal_w-0.2, 0.44,
       size=15, bold=True, color=ac, align=PP_ALIGN.CENTER)
    tb(sl, desc, x+0.1, 3.06, bal_w-0.2, 1.70,
       size=12, color=WHITE, align=PP_ALIGN.CENTER)

tb(sl, "출금 흐름:  요청 →  Available 차감 → Reserved → (체인 전파) → Pending → (finality 충족) → Settled",
   0.6, 4.95, 12.1, 0.38, size=13, color=GOLD_LIGHT)

card(sl, 0.6, 5.38, 5.9, 0.82,
     title="정산(Settlement) 원칙",
     body="요청 즉시 Reserved 홀드 → 체인 성공 시 Pending → finality 후 Settled  |  후정산은 체인 실패 시 롤백 로직 필수",
     accent_bar=True, body_size=12)
card(sl, 6.65, 5.38, 5.9, 0.82,
     title="대사(Reconciliation) 원칙",
     body="내부 원장 ↔ 블록체인 일일 매칭  |  reorg·타이밍 지연 대비 예외 큐 운영  |  NYDFS 등 규제기관 일일 대사 요구",
     accent_bar=True, body_size=12)

# SL — 감사(Audit) 설계
sl = slide_body(prs, "05", "감사(Audit) 설계 — trace ID로 모든 단계를 연결")

card(sl, 0.6, 1.58, 12.1, 0.70,
     title="감사의 목적",
     body="규제 충족 + 포렌식: 누가 요청 → 어떤 정책 적용 → 누가 승인 → 어떤 키로 서명 → 블록체인 결과 — 하나의 trace ID로 전 단계 재구성",
     accent_bar=True)

audit_items = [
    ("API /\nOrchestrator",     "요청자 정보 · 목적 · 시간 · idempotency key",                          GOLD),
    ("Policy Engine",           "적용된 규칙 · 화이트리스트 상태 · 리스크 평가 데이터",                  GOLD),
    ("Approval Module",         "승인자 ID · 순서 · 시간 · 코멘트 · 디바이스 인증 정보",                 WARN),
    ("Signer Module",           "HSM/MPC ID · 서명 요청 ID · 서명 결과 · 승인 토큰 검증 여부",          WARN),
    ("Confirmation\nTracker",   "tx hash · 블록 높이 · nonce · 재편(reorg) 여부",                       GOLD_LIGHT),
    ("Ledger &\nAudit",         "잔고 상태 전환 이벤트 · 대사 결과 · 감사 로그 (append-only)",          GOLD_LIGHT),
]

tb(sl, "모듈별 감사 로그 항목", 0.6, 2.42, 5.0, 0.38, size=13, bold=True, color=GOLD)
gold_line(sl, 0.6, 2.82, 11.5)

for ri, (module, items, ac) in enumerate(audit_items):
    y = 2.88 + ri * 0.55
    bg = CARD_DARK if ri % 2 == 0 else CARD_NAVY
    rect(sl, 0.6, y, 3.52, 0.48, bg)
    rect(sl, 0.6, y, 0.06, 0.48, ac)
    tb(sl, module, 0.76, y+0.06, 3.30, 0.38, size=12, bold=True, color=ac)
    rect(sl, 4.20, y, 8.50, 0.48, bg)
    tb(sl, items, 4.32, y+0.08, 8.34, 0.36, size=12, color=WHITE)

warn_bar(sl, "모든 로그는 trace ID로 연결 · append-only · 외부 감사 기관 열람 가능 포맷  —  내부 원장이 단일 진실")

# ═══════════════════════════════════════════
# SECTION 06 — 입금 플로우
# ═══════════════════════════════════════════
slide_section(prs, "06", "입금(Deposit) 플로우")

sl = slide_body(prs, "06", "입금 감지부터 원장 크레딧까지")

# Deposit flow stages — horizontal
dep_stages = [
    ("블록체인\n네트워크",       "새 블록 생성\n입금 이벤트 발생",                CARD_DARK,  GRAY_LIGHT),
    ("Deposit\nDetector",        "주소 스캔\nincluded(latest) 감지",              CARD_NAVY,  GOLD),
    ("Policy /\nAML 스크리닝",   "발신 주소 AML\n금액 검증",                      CARD_DARK,  WARN),
    ("Ledger\n(Pending)",         "Pending 잔고 증가\nfinality 대기",             CARD_DARK,  GOLD),
    ("Ledger\n(Settled)",         "safe / finalized 후\nAvailable 증가",          CARD_NAVY,  SAFE_COLOR),
]

dep_w = 2.18
dep_gap = 0.22
dep_h = 1.44

for i, (name, desc, bg, ac) in enumerate(dep_stages):
    x = 0.6 + i * (dep_w + dep_gap)
    rect(sl, x, 1.58, dep_w, dep_h, bg)
    rect(sl, x, 1.58, dep_w, 0.06, ac)
    tb(sl, name, x+0.1, 1.70, dep_w-0.2, 0.44,
       size=11.5, bold=True, color=ac, align=PP_ALIGN.CENTER)
    tb(sl, desc, x+0.1, 2.16, dep_w-0.2, 0.80,
       size=10.5, color=WHITE, align=PP_ALIGN.CENTER)
    if i < len(dep_stages) - 1:
        arr_x = x + dep_w + 0.02
        tb(sl, "►", arr_x, 1.58 + 0.50, dep_gap-0.04, 0.44,
           size=14, color=GOLD, align=PP_ALIGN.CENTER)

# Comparison table: Withdrawal vs Deposit
tb(sl, "출금 플로우와의 핵심 차이", 0.6, 3.15, 6.0, 0.38, size=13, bold=True, color=GOLD)
gold_line(sl, 0.6, 3.55, 11.5)

diff_rows = [
    ("시작점",        "사용자 요청 (능동)",               "블록체인 이벤트 감지 (수동)"),
    ("AML 스크리닝",  "출금 전 주소 검증",                "입금 후 발신 주소 검증"),
    ("잔고 처리",     "Reserved → 출금 후 Settled 차감",  "감지 후 Pending → Settled"),
    ("Reorg 위험",    "낮음 (finality 이후 서명)",         "있음 — Pending 크레딧 후 reorg 시 롤백 필요"),
    ("finality 기준", "Confirmation Tracker 추적",         "Deposit Detector: safe / finalized 확인 후 크레딧"),
]

diff_col_x = [0.6, 4.02, 8.30]
diff_col_w = [3.34, 4.20, 4.40]

for i, (h, x, w) in enumerate(zip(["항목", "출금(Withdrawal)", "입금(Deposit)"], diff_col_x, diff_col_w)):
    rect(sl, x, 3.62, w-0.06, 0.36, GOLD if i > 0 else CARD_DARK)
    tb(sl, h, x+0.08, 3.65, w-0.18, 0.28,
       size=12, bold=True, color=DARK if i > 0 else GOLD, align=PP_ALIGN.CENTER)

for ri, (item, wd, dep) in enumerate(diff_rows):
    y = 4.04 + ri * 0.44
    bg = CARD_DARK if ri % 2 == 0 else CARD_NAVY
    for ci, (val, x, w) in enumerate(zip([item, wd, dep], diff_col_x, diff_col_w)):
        rect(sl, x, y, w-0.06, 0.38, bg)
        if ci == 0:
            rect(sl, x, y, 0.06, 0.38, GOLD_LIGHT)
        col_c = WARN if (ri == 3 and ci == 2) else WHITE
        col_c = GOLD_LIGHT if ci == 0 else col_c
        tb(sl, val, x+0.10, y+0.05, w-0.22, 0.30, size=11, color=col_c, bold=(ci==0))

warn_bar(sl, "크레딧 원칙: latest 감지 → safe / finalized 이후에만 Settled  |  이중 크레딧 방지: tx_hash + log_index 유니크 키  |  더스팅 공격: 최소 입금액 이하 → AML 보류 큐")

# ═══════════════════════════════════════════
# SL — Session 2 핵심 메시지
# ═══════════════════════════════════════════
slide_section(prs, "07", "핵심 메시지 · 다음 세션 연결")

sl = slide_body(prs, "07", "Session 2 핵심 메시지")

messages_s2 = [
    ("①", "경계가 곧 보안",
          "각 모듈은 명확한 책임과 입출력을 갖고, 다른 모듈의 내부 상태를 알 필요가 없다. idempotency + 상태 머신이 모든 단계에서 중요"),
    ("②", "권한과 키를 분리",
          "Policy/Approval = \"허용 여부\"  /  Signer = \"서명만\". 다중 승인 + 정책 엔진 + 독립 디바이스 인증으로 내부자·피싱·악성코드 방어"),
    ("③", "내부 원장이 단일 진실",
          "고객별 잔고는 블록체인 잔고와 다르다. Available/Reserved/Pending/Settled 4단계 관리 + finality 기준 크레딧"),
    ("④", "감사 가능성을 내재화",
          "모든 단계 로그를 trace ID로 연결. 누가·무엇을·언제·왜 했는지 기록 — 규제·감사·포렌식 대응의 기반"),
    ("⑤", "다음 세션 연결",
          "이 7모듈 구조가 Session 3 출금 상태 머신의 각 전이(transition)와 1:1 대응. 아키텍처를 먼저 그린 뒤 코드를 보면 \"왜\"가 보인다"),
]

acc = [GOLD, GOLD_LIGHT, GOLD, GOLD_LIGHT, GOLD]
for i, (num, head, body_t) in enumerate(messages_s2):
    y = 1.62 + i * 0.90
    rect(sl, 0.6, y, 0.52, 0.76, NAVY)
    tb(sl, num, 0.62, y+0.12, 0.48, 0.52,
       size=16, bold=True, color=GOLD, align=PP_ALIGN.CENTER)
    rect(sl, 1.22, y, 11.1, 0.76, CARD_NAVY)
    rect(sl, 1.22, y, 0.06, 0.76, acc[i])
    tb(sl, head, 1.38, y+0.04, 3.5, 0.34, size=14.5, bold=True, color=acc[i])
    tb(sl, body_t, 1.38, y+0.38, 10.8, 0.34, size=13, color=WHITE)

highlight_bar(sl, "Session 3: 출금 Lifecycle · TxAttempt 상태 머신  |  Session 4: Nonce & Replacement  |  Session 5: Finality & Reorg 구현")

# ═══════════════════════════════════════════
# SL — Thanks
# ═══════════════════════════════════════════
slide_thanks(prs, website="coincraft.io")

save(prs, OUT)
