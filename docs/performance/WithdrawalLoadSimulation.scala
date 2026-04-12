// 9-3-1/9-3-2: Gatling 부하 테스트 스크립트
// 실행 방법: gatling.sh -s custody.WithdrawalLoadSimulation
// 의존성: Gatling 3.x (https://gatling.io/open-source/)
//
// 참고: 이 파일은 Gradle 빌드에 포함되지 않음 (docs/performance 전용).
// 실제 실행 시 별도 Gatling 설치 필요.

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._
import java.util.UUID

class WithdrawalLoadSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .header("X-API-Key", "dev-operator-key")
    .header("Content-Type", "application/json")
    .acceptHeader("application/json")

  val fromAddress = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"
  val toAddress   = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"

  // ─── 9-3-1: 100 RPS 지속 부하 테스트 ────────────────────────────────

  val withdrawal100Rps = scenario("Withdrawal 100 RPS")
    .exec(
      http("POST /withdrawals")
        .post("/withdrawals")
        .header("Idempotency-Key", session => UUID.randomUUID().toString)
        .body(StringBody(s"""
          {
            "chainType": "evm",
            "fromAddress": "$fromAddress",
            "toAddress": "$toAddress",
            "asset": "USDC",
            "amount": 1
          }
        """))
        .check(status.is(200))
        .check(jsonPath("$$.status").is("W6_BROADCASTED"))
    )

  // ─── 9-3-2: 동시 멱등성 키 충돌 테스트 ──────────────────────────────

  val idempotencyKey = "idem-concurrent-stress-test-1"

  val idempotencyConcurrency = scenario("Idempotency Concurrency 1000")
    .exec(
      http("POST /withdrawals (same key)")
        .post("/withdrawals")
        .header("Idempotency-Key", idempotencyKey)
        .body(StringBody(s"""
          {
            "chainType": "evm",
            "fromAddress": "$fromAddress",
            "toAddress": "$toAddress",
            "asset": "USDC",
            "amount": 1
          }
        """))
        .check(status.in(200, 409))  // 200 (idempotent hit) or 409 (conflict body mismatch)
    )

  setUp(
    // 시나리오 1: 100 RPS 60초
    withdrawal100Rps.inject(
      constantUsersPerSec(100).during(60.seconds)
    ).protocols(httpProtocol),

    // 시나리오 2: 1000 동시 요청 (단일 폭발)
    idempotencyConcurrency.inject(
      atOnceUsers(1000)
    ).protocols(httpProtocol)
  ).assertions(
    // 9-3-4: 기준값 — P99 < 500ms, 오류율 < 1%
    global.responseTime.percentile3.lt(500),
    global.successfulRequests.percent.gte(99)
  )
}
