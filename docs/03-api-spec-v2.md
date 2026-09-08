# API 명세서 v2

> 원본: Notion「API 명세서 v2」(2026-09-06 기준)
> 근거 문서: 요구사항 정의서 v2 · 화면 정의서 v2 · ERD 데이터 모델 v3.0 · AI 기반 사용처 v2
> Base URL: `https://{host}/api/v1` — 호스트명 미확정. 응답은 모두 `application/json; charset=utf-8`
> 앱 이름 미확정으로 본문에서는 `서비스`로 표기한다 (프로젝트명만 Divurve).
> **필드명은 DB 컬럼명과 동일하다**: `currency_code` · `pair_code` · `explain_level` · `entry_method`

## 0. v1 대비 개정 내역

v1은 2026-09-04 작성분으로 요구사항 v2 피벗 이전 설계를 기준으로 했다. 아래가 이번 개정의 전부다.

### 0.1 삭제된 것

| v1 항목 | 삭제 사유 |
|---|---|
| `/fit/simulate`의 `suggested_goal` | **FR-FT-06(W)** 통화별 투자 순위·추천 금지. v1은 이를 FR-FT-04를 근거로 인용했으나 FR-FT-04는 정반대로 추천 표현 금지 조항이다 |
| `503 SAFE_MODE_ACTIVE` | **FR-SF-01(M)** 급변 상태에서도 최신 정보를 숨기지 않는다. 전망 산출 중단은 정보 은폐다. → §5.10 `GET /market/regime`으로 대체 |
| `/xray/attribution`의 `mode` 분기 (three_way / shapley) | **FR-CM-08 · FR-FC-08** 위반. 사용자 설정에 따라 계산 결과가 바뀌면 안 된다. 요구사항 §4.6 4분해로 고정 |
| `display_mode` (초보자/전문가 2단계) | v2에서 설명 선호 3단계 `explain_level`로 교체 (FR-CM-06) |
| `/plans/preview`의 확정 스펙 | `safe_ratio` · `floor` · `split_count` · `g_factor` · `achieve_prob` · 몬테카를로 4000회 → 요구사항 v2 §4.12에서 전부 **미확정** 선언. §6 Route(P)로 강등 |
| 에러 4종 — 안전 버킷 하한 위반 · 예산 부족 · 마감일 임박 · 활성 계획 중복 | 전부 Route 계산 로직 의존. 로직 확정 전까지 보류 |
| 투기 목적 게이트 (403) | `is_speculative`는 ERD에만 있고 요구사항 v2에 근거가 없다. §8 미결정으로 이동 |
| `POST /ai/parse-goal` | 목표 생성 자체가 Route(P) 영역이라 입력 파싱도 MVP 범위 밖 |
| `FX ATLAS` · `api.fx-atlas.app` | 앱 이름 미확정 (요구사항 v2 §9). 도메인 하드코딩 제거 |

### 0.2 추가된 것 — v1에 빠져 있던 M 우선순위 기능

| 추가 | 근거 |
|---|---|
| `/krw-assets` CRUD | 외화 비중 계산의 분모. v1은 `total_asset_krw`를 반환하면서 원화 자산 입력 경로가 없었다 (FR-XR-01, ERD §6.3) |
| `/me/risk-profile/simple` · `/detail` · 진단 재개 | FR-DG-01 · FR-DG-03 · FR-DG-04 · ERD `detail_progress` |
| `/deposits/:id` PUT · DELETE | FR-XR-07 "추가·수정·삭제" |
| `/stress/scenarios` · `/stress/runs` | FR-ST-01 · FR-ST-05, ERD `stress_scenarios` · `stress_test_runs` |
| `/me/onboarding/complete` | FR-IS-01 · FR-IS-07, ERD `users.onboarded_at` |
| `/market/regime` | FR-SF-02 상태 구분. ERD `fx_stats.regime` 4종 → 화면 배지 3종 매핑을 서버가 책임 |
| `/route/context` | FR-RT-01 RouteContext 데이터 계약. v1에는 대응 개념이 없었다 |
| `/home/summary` 6블록 | v1은 3블록. 화면 v2 §11은 6블록 |

### 0.3 변경된 것

| v1 | v2 | 사유 |
|---|---|---|
| `GET /fit/concentration` | `GET /fit` | 화면 v2 §14 Fit은 집중도만이 아니라 성향·관계 설명·판단 기준을 한 화면에 담는다 |
| `POST /fit/simulate` | `POST /fit/preview` | "simulate"는 추천 뉘앙스가 있어 FR-FT-03 "변화 미리보기"의 용어로 통일 |
| `/forecast`의 `path`, `volatility.regime: "high"` | `band`, `regime: "elevated"` | `"high"`는 ERD `vol_regime` ENUM에 없던 값. `path`는 `model_path`와 헷갈려 `band`로 변경 |
| 에러 9종 | 6종 + "에러로 처리하지 않는 세 가지" | 데이터 없음 · AI 실패 · 급변 상태는 모두 200으로 응답한다 |
| 부호 규약 근거 FR-CM-04 | **FR-CM-05** | FR-CM-04는 "원화 병기". 규약 내용 자체는 옳았다 |
| 우선순위 표기 없음 | M / S / P / W 열 추가 | 요구사항 v2 우선순위를 그대로 따라 구현 범위를 한눈에 보이게 함 |

---

## 1. 공통 규약

### 1.1 인증

```
Authorization: Bearer {access_token}
```

- 액세스 토큰 30분, 리프레시 토큰 14일.
- 비밀번호는 평문 저장하지 않는다(NFR-SE-01). MVP는 Mock 인증이나 인터페이스는 동일하게 유지한다.
- 사용자 자산과 진단 데이터는 소유자 단위로 분리한다(NFR-SE-02).
- 둘러보기는 `POST /auth/demo`로 데모 토큰을 발급받으며 모든 응답의 `meta.is_demo`가 `true`가 된다(FR-IS-09).

### 1.2 응답 형식

성공 응답은 단일 루트 객체. **계산 수치가 포함되면 `meta` 필수.**

```json
{
  "data": { },
  "meta": {
    "as_of": "2026-09-01T15:30:00Z",
    "data_state": "mock",
    "sources": [],
    "is_demo": true,
    "regime": "elevated"
  }
}
```

| meta 필드 | 규칙 | 근거 |
|---|---|---|
| `as_of` | 수치를 반환하는 모든 응답에 필수. 시장·자산 데이터의 기준 시각(UTC) | FR-CM-01, NFR-DT-01 |
| `data_state` | `live` 또는 `mock`. 클라이언트는 `mock`이면 `시연용 예시 데이터` 배지를 노출한다 | FR-CM-02, NFR-DT-03 |
| `sources` | `data_state=live`일 때만 값을 채운다. **Mock 단계에서는 빈 배열이며 출처를 만들어내지 않는다** | FR-CM-10 |
| `regime` | `calm` / `normal` / `elevated` / `stress`. 시장 수치를 포함하는 응답에 동반 | FR-SF-02 |
| `model_version` | `/forecast` 계열에만 포함 | FR-FC-11 |

### 1.3 에러 형식

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "수량은 0보다 커야 합니다.",
    "field": "quantity"
  }
}
```

| HTTP | code | 상황 |
|---|---|---|
| 400 | VALIDATION_FAILED | 입력값 형식·범위 오류 |
| 401 | UNAUTHORIZED | 토큰 없음 또는 만료 |
| 403 | FORBIDDEN | 타인 리소스 접근 (NFR-SE-02) |
| 404 | NOT_FOUND | — |
| 409 | DUPLICATE_RESOURCE | `fx_deposits`의 (user, currency, bank) 유니크 위반 등 |
| 501 | NOT_IMPLEMENTED | Route 계열 등 기능 플래그가 꺼져 있는 엔드포인트 (화면 v2 §20 `Route 미구현`) |

> ⚠️ **에러로 처리하지 않는 세 가지**
> 1. **데이터 없음** → 200 + 빈 배열/`null`. 0으로 그린 차트가 아니라 전용 빈 상태를 클라이언트가 그린다(FR-CM-09).
> 2. **AI 생성·검증 실패** → 200 + `explanation.fallback: true`. 계산값은 그대로 내려보낸다(FR-AI-06, NFR-AI-03).
> 3. **급변 상태** → 200 + `meta.regime`. 전망 산출을 중단하지 않는다(FR-SF-01).

### 1.4 표기 규약

| 항목 | 규칙 | 근거 |
|---|---|---|
| 날짜 | ISO 8601. 날짜만은 `2026-10-15`, 시각은 UTC `Z`. 표시 시 Asia/Seoul 변환은 클라이언트 책임 | ERD v3.0 헤더 |
| 통화 | 필드명 `currency_code`. ISO 4217 3자리 대문자 | ERD `currencies` |
| 통화쌍 | 필드명 `pair_code`. `USDKRW` 6자리 | ERD `currency_pairs` |
| 금액 | 숫자형. 문자열 금지. 원화는 정수, 외화는 `currencies.minor_units` 자릿수 | ERD 설계원칙 |
| 비율 | 0과 1 사이 소수. `0.639`는 63.9퍼센트 | — |
| JPY | 저장과 전송은 1엔 기준. 100엔 환산은 `currencies.quote_unit`을 보고 클라이언트가 표시 단계에서 처리 | ERD §4.1 |
| 부호 | USD/KRW 상승은 원화 약세이며 외화자산 평가액 증가(+). 전 응답에서 동일 | **FR-CM-05** |
| 유도 환율 | JPYKRW·EURKRW는 USDKRW·USDJPY·EURUSD에서 서버가 삼각 유도해 반환. 방향은 `currencies.usd_side`가 정한다 | ERD §4.1·4.2 |

---

## 2. 상태 어휘 매핑

v1에서 세 문서가 서로 다른 상태 어휘를 쓴 것을 여기서 하나로 고정한다. **매핑 책임은 서버에 있으며 클라이언트는 `badge` 값을 그대로 그린다.**

| `fx_stats.regime` (ERD, 4종) | API `badge` (3종) | 화면 표시 | 동작 |
|---|---|---|---|
| `calm` | `normal` | 정상 | 일반 데이터 상태 표시 |
| `normal` | `normal` | 정상 | 일반 데이터 상태 표시 |
| `elevated` | `caution` | 주의 | 변동성 확대와 이벤트 안내 |
| `stress` | `turbulent` | 급변 | 최신 정보 유지 + 불확실성 경고 + 계획 가정 확인 |

> 🔑 어느 단계에서도 **응답을 막지 않는다.** `turbulent`에서도 `/forecast`는 200을 내려보내고, 대신 구간 폭과 `uncertainty_note`가 넓어진다(FR-SF-01, FR-SF-03).

---

## 3. 엔드포인트 전체 목록

우선순위는 요구사항 v2 표기를 따른다. **M**: MVP 필수 · **S**: 여유 시 · **P**: 구조만 준비 · **W**: MVP 제외

**인증 · 초기 설정 (FR-IS)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| POST | `/auth/signup` | 회원가입 | M |
| POST | `/auth/login` | 로그인. 응답에 `onboarded` 포함 → 초기 설정 이동 여부 결정 (FR-IS-01, FR-IS-07) | M |
| POST | `/auth/refresh` | 토큰 갱신 | M |
| POST | `/auth/demo` | 샘플 계정 발급 (FR-IS-09) | S |
| POST | `/me/onboarding/complete` | 초기 설정 종료. `users.onboarded_at` 기록. 전부 건너뛰어도 호출 가능 (FR-IS-05) | M |

**진단 (FR-DG)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/me/risk-profile` | 대표 유형·상태·응답 근거·미응답 문항 | M |
| POST | `/me/risk-profile/simple` | Q1~Q3 제출. 부분 제출 허용 (FR-DG-01, FR-DG-04) | M |
| POST | `/me/risk-profile/detail` | Q4~Q6 제출. 중단 시 `detail_progress`에 저장 (FR-DG-03, FR-DG-05) | M |

**마이페이지 (FR-MY)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET / PUT | `/me` | 이름·이메일 등 계정 정보 | M |
| GET / PUT | `/me/settings` | `explain_level` · `explain_domain` · 알림 스위치 | M |

**자산 (FR-XR-07)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET / POST | `/holdings` | 보유 종목 목록·추가 | M |
| PUT / DELETE | `/holdings/:id` | 수정·삭제 (soft delete) | M |
| GET / POST | `/deposits` | 외화 예금 목록·추가 | M |
| PUT / DELETE | `/deposits/:id` | 수정·삭제 | M |
| GET / POST | `/krw-assets` | 원화 자산 목록·추가. 외화 비중의 분모 | M |
| PUT / DELETE | `/krw-assets/:id` | 수정·삭제 | M |

**X-Ray (FR-XR)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/xray` | 외화 비중·통화 노출·집중도·민감도 | M |
| GET | `/xray/attribution` | 손익 4분해 (자산·환율·상호작용·비용) | M |

**Fit (FR-FT)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/fit` | 성향과 현재 노출의 관계·집중도·참고 기준선 | M |
| POST | `/fit/preview` | 통화 비중 가정 변경 시 집중도·민감도 변화만 반환 (FR-FT-03) | S |

**Forecast (FR-FC)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/forecast` | 팬차트·예측 범위·변동성·내 자산 영향 | M |
| GET | `/forecast/factors` | 전망 동인 (L2, 참고 정보) | S |
| GET | `/forecast/model-performance` | 모델 성적표 (FR-FC-11) | M |
| GET | `/events` | 주요 정책회의·지표 발표 일정 | S |

**Stress Test (FR-ST)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/stress/scenarios` | 시나리오 마스터. 기본 2종과 추가 시나리오 | M |
| POST | `/stress/runs` | 시나리오 적용·결과 저장 | M |
| GET | `/stress/runs` | 과거 실행 이력 | S |

**공통 · 홈**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/home/summary` | 홈 6블록 통합 조회 | M |
| GET | `/market/regime` | 시장 상태 배지와 판정 근거 | M |
| GET | `/currencies` | 지원 통화와 표시 규칙. `minor_units` `quote_unit` `usd_side` `color_token` | M |
| GET | `/notifications` | 알림 목록 | S |

**AI (FR-AI)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| POST | `/ai/explain` | 엔진 결과를 설명 선호에 맞춰 서술. 실패 시 폴백 템플릿 | M |

**Route 연결 (FR-RT)**

| METHOD | PATH | 설명 | 우선 |
|---|---|---|---|
| GET | `/route/context` | RouteContext 직렬화. 계산은 하지 않는다 | P |
| GET | `/goals` | 목표 목록. 현재는 항상 빈 배열 + 빈 상태 메타 | P |
| — | `/plans/*` | 계획 생성·미리보기·회차 집행. **계산 로직 미확정으로 명세 보류** (§6) | P |
| GET | `/banks/:bank_code/fx-terms` | 은행·통화·채널별 스프레드. **요구사항 근거 없음** (§8) | P |
| — | 마이데이터·증권사 연동, 실제 환전·주문 | 구현하지 않는다 | W |

---

## 4. Mock fixture (공유 기준값)

NFR-DT-02 "동일 수치는 한 Mock fixture에서 공급한다"를 지키기 위해 본 문서의 모든 예시는 아래 단일 fixture에서 나온다. **화면 정의서 §21 체크리스트의 "X-Ray·Forecast·Stress Test의 금액이 같은 Mock fixture와 일치" 항목이 이것을 검증한다.**

| 항목 | 값 |
|---|---|
| 기준일 | 2026-09-01T15:30:00Z |
| 저장 통화쌍 | USDKRW 1382.40 · USDJPY 147.20 · EURUSD 1.0850 |
| 유도 환율 | JPYKRW 9.3913 (1엔) · EURKRW 1499.90 |
| 총자산 | 68,400,000 KRW |
| 원화 자산 | 43,680,000 KRW |
| 외화 자산 | 24,720,000 KRW (외화 비중 0.361) |
| └ 해외주식 | 20,000,000 KRW |
| └ 외화예금 | 4,720,000 KRW |
| 통화별 노출 | USD 15,790,000 (0.639) · JPY 5,470,000 (0.221) · EUR 3,460,000 (0.140) |
| 위험성향 | Q1=B, Q2=C, Q3=B → score 4 → `balanced` (균형항로형), `concentration_threshold` 0.60 |

---

## 5. 핵심 엔드포인트 상세

### 5.1 GET `/me/risk-profile`

진단 상태와 근거를 함께 내려보낸다. **미응답이 있으면 유형을 만들지 않는다**(FR-DG-02).

```json
{
  "data": {
    "status": "simple_done",
    "grade": "balanced",
    "grade_label": "균형항로형",
    "score": 4,
    "diagnosed_on": "2026-09-01",
    "concentration_threshold": 0.60,
    "simple": {
      "answers": { "q1": "B", "q2": "C", "q3": "B" },
      "rationale": [
        { "question": "q1", "choice": "B", "points": 1,
          "reading": "작은 손실은 받아들이지만 커지면 불편하게 느낍니다." },
        { "question": "q2", "choice": "C", "points": 2,
          "reading": "손실 가능성이 커져도 더 높은 수익을 기대하는 쪽입니다." },
        { "question": "q3", "choice": "B", "points": 1,
          "reading": "자산 금액이 조금씩 오르내리는 정도는 괜찮게 느낍니다." }
      ],
      "mixed_response_note": null
    },
    "detail": {
      "completed": false,
      "answered": { "q4": "B" },
      "next_question": "q5",
      "title_modifier": "지출 균형을 함께 고려하는"
    },
    "limitation_note": "이 판정은 해커톤 MVP용 가설이며 통계적으로 검증된 금융회사 표준 진단이 아닙니다."
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock", "sources": [] }
}
```

| 필드 | 규칙 | 근거 |
|---|---|---|
| `status` | `not_measured` / `simple_done` / `detail_done`. ERD `diagnosis_status` 그대로 | FR-MY-04 |
| `grade` · `score` | Q1~Q3만으로 계산. **Q4~Q6은 이 값을 바꾸지 않는다** | FR-DG-05 |
| `rationale` | `왜 이렇게 나왔나요?` 아코디언의 원본 데이터 | FR-DG-07 |
| `mixed_response_note` | 상충 응답은 새 유형을 만들지 않고 보조 설명 문자열로만 내려보낸다 | FR-DG-06 |
| `detail.next_question` | 미응답 문항부터 재개하기 위한 커서. ERD `detail_progress`에서 산출 | FR-DG-04 |
| `title_modifier` | Q4 응답에서 파생된 상세 제목 수식어. 점수에 영향 없음 | 요구사항 §4.3 |

> 🚫 진단을 건너뛴 경우 `status: "not_measured"`이며 `grade`는 `null`이다. **임의의 기본 성향을 채워 넣지 않는다**(FR-IS-06). 클라이언트는 이 경우 유형 대신 진단 CTA를 그린다(화면 v2 §20).

### 5.2 POST `/me/risk-profile/detail`

상세 진단은 Q4부터 시작하며 Q1~Q3을 다시 묻지 않는다(FR-DG-03). 부분 제출이 허용된다.

```json
// Request — 중단 가능. 답한 문항만 보낸다
{ "answers": { "q4": "B", "q5": "standard" } }

// Response 200
{
  "data": {
    "status": "simple_done",
    "grade": "balanced",
    "score": 4,
    "detail": { "completed": false, "answered": { "q4": "B", "q5": "standard" },
                "next_question": "q6" },
    "applied": { "explain_level": "standard", "title_modifier": "지출 균형을 함께 고려하는" }
  }
}
```

- Q5 응답은 `user_settings.explain_level`에 즉시 반영된다. 클라이언트에는 A·B·C 같은 내부 코드를 노출하지 않는다(화면 v2 §9).
- Q6까지 마치면 `status`가 `detail_done`으로 바뀌고 `detail_progress` → `detail_answers`로 이동한다(ERD §11).
- **`grade`와 `score`는 어떤 경우에도 변하지 않는다**(FR-DG-05).

### 5.3 GET `/xray`

```json
{
  "data": {
    "total_asset_krw": 68400000,
    "krw_asset_krw": 43680000,
    "fx_asset_krw": 24720000,
    "fx_ratio": 0.361,
    "exposure": [
      { "currency_code": "USD", "krw": 15790000, "share": 0.639 },
      { "currency_code": "JPY", "krw": 5470000,  "share": 0.221 },
      { "currency_code": "EUR", "krw": 3460000,  "share": 0.140 }
    ],
    "concentration": {
      "top_currency_code": "USD",
      "share": 0.639,
      "threshold": 0.60,
      "threshold_source": "risk_profile.balanced",
      "status": "above_threshold"
    },
    "sensitivity_1pct": {
      "total_krw": 247200,
      "by_currency": { "USD": 157900, "JPY": 54700, "EUR": 34600 }
    },
    "day_change_krw": 84000
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock",
            "sources": [], "regime": "elevated" }
}
```

- `threshold`는 `risk_profiles.concentration_threshold`에서 온다. 성향 미측정이면 `null`이고 `status`는 `unknown`이다.
- `day_change_krw`는 `portfolio_snapshots` 전일치 대비. 스냅샷이 없으면 `null`.
- 자산이 없으면 `exposure: []`와 `fx_asset_krw: 0`을 내리고 클라이언트가 빈 상태를 그린다(FR-CM-09).

### 5.4 GET `/xray/attribution`

쿼리: `?currency_code=USD`

> 🧮 요구사항 v2 §4.6 검증식
> `R_KRW = (1 + R_asset) × (1 + R_fx) - 1 = R_asset + R_fx + R_asset × R_fx`
> 여기에 거래비용을 더해 4분해한다(FR-XR-04). **분해 방식은 고정이며 사용자 설정으로 바뀌지 않는다.**

```json
{
  "data": {
    "currency_code": "USD",
    "cost_basis_krw": 15050000,
    "current_krw": 15790000,
    "total_return": 0.0492,
    "components": [
      { "key": "asset",       "label": "자산 가격 효과", "krw": 1236000, "contribution_pp": 0.0821 },
      { "key": "fx",          "label": "환율 효과",     "krw": -421000, "contribution_pp": -0.0280 },
      { "key": "interaction", "label": "상호작용",       "krw": -30000,  "contribution_pp": -0.0020 },
      { "key": "cost",        "label": "비용",           "krw": -45000,  "contribution_pp": -0.0030 }
    ],
    "by_holding": [
      { "ticker": "VOO", "krw": 11240000,
        "local_return": 0.091, "fx_return": -0.030, "krw_return": 0.058 }
    ]
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock", "sources": [] }
}
```

검산: `components[].krw` 합계 740,000 = `current_krw - cost_basis_krw`. `by_holding`은 `(1+0.091) × (1-0.030) - 1 = 0.0583`.

### 5.5 GET `/fit`

```json
{
  "data": {
    "risk_profile": { "status": "simple_done", "grade": "balanced",
                      "grade_label": "균형항로형", "diagnosed_on": "2026-09-01" },
    "concentration": { "top_currency_code": "USD", "share": 0.639,
                       "threshold": 0.60, "status": "above_threshold" },
    "relation": {
      "code": "concentration_above_profile",
      "facts": { "share": 0.639, "threshold": 0.60, "gap_pp": 0.039 }
    },
    "basis_note": "참고 기준선은 MVP 가설값이며 통계적으로 검증된 배분 기준이 아닙니다."
  }
}
```

> ⚠️ `relation`은 **코드와 사실값만** 내려보낸다. "적합"·"부적합"은 물론 점수나 등급도 내리지 않는다(FR-FT-04). 문장화는 `/ai/explain`이 담당하며 서술 계층에서도 추천 표현은 차단된다.

### 5.6 POST `/fit/preview`

통화 노출을 **가정해서** 바꿨을 때의 집중도·민감도 변화만 보여준다(FR-FT-03). 저장하지 않는다.

```json
// Request
{ "currency_code": "JPY", "delta_share": 0.10 }

// Response 200
{
  "data": {
    "assumption": "외화자산 총액 24,720,000원을 고정한 채 JPY 비중만 10%p 높인 가정입니다.",
    "exposure": {
      "before": { "USD": 0.639, "JPY": 0.221, "EUR": 0.140 },
      "after":  { "USD": 0.557, "JPY": 0.321, "EUR": 0.122 }
    },
    "concentration": {
      "before": { "top_currency_code": "USD", "share": 0.639, "status": "above_threshold" },
      "after":  { "top_currency_code": "USD", "share": 0.557, "status": "within_threshold" },
      "threshold": 0.60
    },
    "sensitivity_1pct": {
      "before": { "USD": 157900, "JPY": 54700, "EUR": 34600, "total_krw": 247200 },
      "after":  { "USD": 137690, "JPY": 79350, "EUR": 30160, "total_krw": 247200 }
    }
  }
}
```

> 🚫 v1에 있던 목표 제안 필드는 **삭제**했다. 특정 통화의 매수 목표를 서버가 제안하는 것은 FR-FT-06(W) "통화별 투자 순위와 기대수익 기반 추천을 제공하지 않는다"에 정면으로 어긋난다. 화면 v2 §14 표현 금지 목록에도 같은 패턴이 있다.
> 대신 `assumption` 문자열과 변화값만 내려보내고, 클라이언트는 "이 선택을 추가하면 USD 집중도가 63.9%에서 55.7%로 낮아지는 가정입니다" 형태로만 표현한다.

### 5.7 GET `/forecast`

쿼리: `?pair_code=USDKRW&horizon_days=30` (기본 30일, FR-FC-02)

```json
{
  "data": {
    "pair_code": "USDKRW",
    "horizon_days": 30,
    "base_date": "2026-09-01",
    "current_rate": 1382.40,
    "base_rate": 1382.40,
    "history": [ { "d": "2026-08-05", "rate": 1361.20 } ],
    "band": [
      { "d": "2026-09-08",
        "p50_lo": 1371.0, "p50_hi": 1395.2,
        "p80_lo": 1361.4, "p80_hi": 1404.8 }
    ],
    "model_path": [ { "d": "2026-09-08", "rate": 1383.1 } ],
    "interval_80": { "lo": 1346.0, "hi": 1431.0, "width_pct": 0.0615 },
    "volatility": { "vol_30d": 0.061, "vol_percentile_5y": 0.72, "regime": "elevated" },
    "user_impact": { "per_1pct_krw": 157900, "asset_krw": 15790000 },
    "labels": {
      "band": "예측 범위 / 불확실성 구간",
      "model_path": "모델의 참고 중심 경로"
    },
    "model_info": {
      "interval_levels": [0.50, 0.80],
      "assumptions": "드리프트 0 기준선에 30일 변동성을 적용한 구간입니다.",
      "limitations": "실제 환율은 구간을 벗어날 수 있으며 급변 시 오차가 확대됩니다."
    },
    "uncertainty_note": "현재 변동성이 5년 상위 28% 구간이어서 예측 범위가 평시보다 넓습니다.",
    "disclaimer": "예상 구간은 보장 범위가 아니며 투자 권유가 아닙니다."
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock", "sources": [],
            "model_version": "fc-2026.08.3", "regime": "elevated" }
}
```

> 🔒 **L1 / L2 경계**
> - `base_rate` · `band` · `volatility` = **L1**. 계산에 쓰이는 유일한 값.
> - `model_path` · `/forecast/factors` = **L2**. 표시 전용이며 `/route/context`에 포함되지 않는다 (FR-FC-12).
> - **방향 확률 필드를 두지 않는다.** 요구사항 §2.2가 "지금 사라·팔라 형태의 지시"를 제공도 사용도 금지한다.
> - `band`를 `변동성`으로 부르지 않는다. 변동성은 `volatility`로 분리된 별도 지표다 (FR-FC-04, FR-FC-05).

### 5.8 GET `/forecast/model-performance`

```json
{
  "data": {
    "pair_code": "USDKRW", "horizon_days": 30,
    "model":       { "hit_rate": 0.540, "mae": 0.0190,
                     "coverage_80": 0.810, "avg_width": 0.0580 },
    "random_walk": { "hit_rate": 0.500, "mae": 0.0194 },
    "rw_improvement": 0.0206,
    "validation": { "method": "rolling_walk_forward", "folds": 24, "leakage_guard": true },
    "note": "구간 포함률은 구간을 넓히면 쉽게 오르므로 평균 구간 폭과 함께 봐야 합니다.",
    "evaluated_at": "2026-08-31T00:00:00Z"
  }
}
```

`coverage_80`은 반드시 `avg_width`와 함께 노출한다. `rw_improvement`가 음수여도, `hit_rate`가 50%에 가까워도 그대로 보여준다(ERD §8, FR-FC-11).

### 5.9 POST `/stress/runs`

```json
// Request
{ "scenario_code": "equity_down_krw_weak" }

// Response 201
{
  "data": {
    "id": "7c41...",
    "scenario": {
      "scenario_code": "equity_down_krw_weak",
      "name_ko": "주가 하락 + 원화 약세",
      "reference_event": "2020년 3월 변동성 급등 참고",
      "assumption_note": "해외주식 평가액에 주가 충격을 먼저 적용한 뒤 외화자산 전체에 환율 충격을 적용합니다."
    },
    "base_date": "2026-09-01",
    "shock": { "equity_shock_pct": -0.20, "fx_shock_pct": 0.10 },
    "before": { "fx_asset_krw": 24720000 },
    "effects": {
      "equity_effect_krw": -4000000,
      "fx_effect_krw": 2072000,
      "total_effect_krw": -1928000
    },
    "after": { "fx_asset_krw": 22792000 },
    "interpretation_code": "fx_cushions_equity_loss",
    "conditional_note": "이 결과는 미래 예측이 아니라 입력한 충격값에 대한 조건부 계산입니다."
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock", "sources": [] }
}
```

- **적용 순서를 명시한다**: 주가 충격 → 환율 충격. 그래야 `equity_effect + fx_effect = total_effect`가 정확히 성립한다(ERD `stress_test_runs`의 3컬럼 구조와 일치).
- 검산: 해외주식 20,000,000 × (-0.20) = -4,000,000 / (24,720,000 - 4,000,000) × 0.10 = +2,072,000 / 합계 -1,928,000.
- 결과는 `stress_test_runs`에 저장되며 삭제하지 않는다. 사용자에게 노출된 계산 근거다(FR-ST-05, ERD §12).
- 충격값은 실행 시점 스냅샷이다. 이후 시나리오 마스터가 바뀌어도 과거 결과는 그대로 재현된다.

### 5.10 GET `/market/regime`

v1의 안전모드 조회 엔드포인트를 대체한다. **상태를 알리되 기능을 끄지 않는다.**

```json
{
  "data": {
    "badge": "caution",
    "badge_label": "주의",
    "pair_regimes": {
      "USDKRW": { "regime": "elevated", "vol_30d": 0.061, "vol_percentile_5y": 0.72 },
      "USDJPY": { "regime": "normal",   "vol_30d": 0.048, "vol_percentile_5y": 0.41 },
      "EURUSD": { "regime": "calm",     "vol_30d": 0.039, "vol_percentile_5y": 0.22 }
    },
    "checks": [
      { "key": "data_freshness",    "passed": true },
      { "key": "source_divergence", "passed": true },
      { "key": "vol_percentile",    "passed": false,
        "detail": "USDKRW 30일 변동성이 5년 상위 28% 구간입니다." }
    ],
    "guidance": {
      "keep_serving_forecast": true,
      "widen_uncertainty": true,
      "show_plan_assumptions": true
    },
    "anomaly": { "data_error_detected": false, "note": "데이터 오류와 실제 시장 충격은 구분하며 실제 충격은 삭제하지 않습니다." }
  }
}
```

| 필드 | 의미 | 근거 |
|---|---|---|
| `badge` | `normal` / `caution` / `turbulent`. 홈과 전 화면 상단 배지에 그대로 쓴다 | FR-SF-02 |
| `guidance.keep_serving_forecast` | **항상 `true`.** 산출을 멈추는 경로를 두지 않는다 | FR-SF-01 |
| `guidance.widen_uncertainty` | 클라이언트가 불확실성 안내를 강화해서 표시 | FR-SF-03 |
| `guidance.show_plan_assumptions` | 기존 계획이 사용한 기준일·가정을 확인할 수 있게 한다 | FR-SF-04 (P) |
| `anomaly` | 데이터 오류와 실제 시장 충격을 구분 | FR-SF-06 (S) |

### 5.11 GET `/home/summary`

화면 v2 §11의 6블록을 그대로 따른다. **블록 순서는 고정이며 서버가 사용자별로 재정렬하지 않는다**(FR-HM-07, FR-CM-07, NFR-UI-01).

```json
{
  "data": {
    "blocks": [
      { "order": 1, "key": "today",       "state": "filled" },
      { "order": 2, "key": "profile_fit", "state": "filled" },
      { "order": 3, "key": "fx_status",   "state": "filled" },
      { "order": 4, "key": "goals_route", "state": "route_pending" },
      { "order": 5, "key": "attention",   "state": "filled" },
      { "order": 6, "key": "forecast",    "state": "filled" }
    ],
    "today": { "headline_code": "vol_elevated_usd", "badge": "caution" },
    "profile_fit": { "grade": "balanced", "concentration_status": "above_threshold" },
    "fx_status": { "fx_ratio": 0.361, "top_currency_code": "USD",
                   "sensitivity_1pct_krw": 247200, "day_change_krw": 84000 },
    "goals_route": { "active_goals": [], "route_enabled": false },
    "attention": { "regime_badge": "caution", "upcoming_events": [] },
    "forecast": { "pair_code": "USDKRW", "current_rate": 1382.40,
                  "interval_80": { "lo": 1346.0, "hi": 1431.0 } }
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock",
            "sources": [], "regime": "elevated" }
}
```

- `state`는 `filled` / `empty` / `route_pending` / `not_measured`. 빈 블록이어도 **블록 자체를 생략하지 않는다.**
- 프로필·설정 데이터는 홈에 포함하지 않는다. 마이페이지로 분리된다(FR-HM-08).

### 5.12 POST `/ai/explain`

```json
// Request — 계산 엔진이 이미 만든 값만 넣는다
{
  "surface": "forecast_summary",
  "facts": {
    "pair_code": "USDKRW",
    "current_rate": 1382.40,
    "interval_80": { "lo": 1346.0, "hi": 1431.0 },
    "vol_percentile_5y": 0.72,
    "per_1pct_krw": 157900,
    "regime": "elevated"
  }
}

// Response 200
{
  "data": {
    "explanation": {
      "sentences": [ "...", "...", "...", "..." ],
      "sentence_count": 4,
      "explain_level": "standard",
      "explain_domain": "dev",
      "fallback": false
    },
    "verification": {
      "numeric_match": true,
      "regime_disclosed": true,
      "blocked_phrases": [],
      "fallback_reason": null
    }
  },
  "meta": { "as_of": "2026-09-01T15:30:00Z", "data_state": "mock", "sources": [] }
}
```

#### `verification` — 폴백했다면 **왜** 인가 (이슈 #122)

폴백에 이르는 경로는 넷이고, `fallback_reason` 이 그 넷을 가른다. 이 필드가 없던 동안에는 네 경로가
모두 `numeric_match: true`, `blocked_phrases: []` 로 수렴해 응답만으로는 원인을 알 수 없었다.

| `fallback_reason` | 무슨 일이 있었나 | `numeric_match` / `regime_disclosed` | `blocked_phrases` |
|---|---|---|---|
| `provider_error` | 호출이 예외로 끝났다 — 타임아웃·429·5xx·응답 형식 위반 | `null` (검증까지 못 감) | `[]` |
| `blocked_phrases` | 금지 표현이 검출돼 차단했다 | `null` (검증까지 못 감) | **검출된 표현 목록** |
| `budget_exhausted` | 총예산 8초가 소진돼 재시도를 생략했다 | 마지막 시도의 측정값 | `[]` |
| `verification_failed` | 수치 대조 또는 급변 구간 고지 검사에 걸렸다 | 어느 쪽이 `false` 인지가 답이다 | `[]` |
| `null` | 폴백하지 않았다 (`fallback: false`) | 둘 다 `true` | `[]` |

- **`numeric_match`·`regime_disclosed` 는 nullable 이다.** `null` 은 "실패"가 아니라 **"측정하지
  않았다"** 다. 검증 단계에 도달하지 못한 경로에서 `true` 를 채워 넣지 않는다 — 통과한 출력이
  애초에 존재하지 않았는데 통과했다고 말하는 것이 가장 나쁘다.
- 이 두 필드와 `fallback_reason` 은 **`null` 이어도 응답에서 생략되지 않는다**(전역 `non_null` 을
  이 객체에서만 뒤집는다). 필드가 사라지면 "검증 안 됨" 과 "필드 없음" 을 구분할 수 없다.

| 규칙 | 근거 |
|---|---|
| `facts`에 없는 숫자를 AI가 만들지 않는다. 서버는 응답의 숫자를 `facts`와 대조한다 | FR-AI-02, FR-AI-05, NFR-AI-02 |
| `surface: forecast_summary`는 항상 4문장이다. `explain_level`은 내용 구성만 바꾼다 | FR-AI-04, FR-FC-07 |
| 검증 실패 시 `fallback: true`와 고정 템플릿 문장을 내리며 **200을 유지**한다 | FR-AI-06, NFR-AI-03 |
| 폴백했다면 `verification.fallback_reason` 이 네 경로 중 어느 것인지 말한다 | 이슈 #122 |
| 프롬프트와 응답 전문은 `audit_logs(action='ai_explained')`에 기록 | ERD §10 |
| `explain_level`·`explain_domain`은 이 엔드포인트 밖의 어느 계산에도 전달되지 않는다 | FR-CM-08, ERD 설계원칙 |

---

## 6. Route 연결 — 현재 범위 (P)

> 🚧 요구사항 v2 §4.12는 FR-RT-01~06을 **전부 P(구조만 준비)**, FR-RT-07을 W로 둔다. 목적함수·버킷 비율·분할 회차·달성 확률·몬테카를로 적용 여부는 모두 미확정이다.
> 따라서 **계산을 수행하는 엔드포인트는 본 버전에서 명세하지 않는다.** 데이터 계약만 먼저 고정한다.

### 6.1 GET `/route/context`

FR-RT-01의 RouteContext를 직렬화해 내려보낸다. 수집만 하고 어떤 계획도 계산하지 않는다.

```json
{
  "data": {
    "as_of": "2026-09-01T15:30:00Z",
    "diagnosis": { "status": "simple_done", "grade": "balanced", "score": 4,
                   "concentration_threshold": 0.60 },
    "portfolio": { "total_asset_krw": 68400000, "fx_asset_krw": 24720000,
                   "fx_ratio": 0.361,
                   "exposure": { "USD": 0.639, "JPY": 0.221, "EUR": 0.140 } },
    "forecast": { "pair_code": "USDKRW", "base_rate": 1382.40,
                  "interval_80": { "lo": 1346.0, "hi": 1431.0 },
                  "vol_30d": 0.061, "base_date": "2026-09-01" },
    "stress": { "last_run_id": "7c41...", "total_effect_krw": -1928000 },
    "regime": "elevated"
  }
}
```

> 🔒 **`forecast` 블록에 `model_path`와 `forecast_factors`는 들어가지 않는다.** 방향 전망을 Route의 계산 입력으로 전달하지 않는다는 FR-FC-12를 API 계약 수준에서 강제한다. ERD가 `forecasts.base_rate`(L1)와 `model_path`(L2)를 컬럼으로 분리한 것과 같은 원칙이다.

### 6.2 현재 구현 범위

- 사이드바 Route 메뉴와 목표 빈 상태 (화면 v2 §18)
- `GET /route/context` 직렬화
- `GET /goals`는 빈 배열과 `route_enabled: false` 반환
- 그 외 `/goals` 쓰기·`/plans/*`는 `501 NOT_IMPLEMENTED` + 기능 플래그
- 데이터 구조는 `invest`와 기한형 외화 목표를 함께 담을 수 있게 준비한다 (FR-RT-02, ERD `goals.kind`)

### 6.3 Route 확정 전까지 명세하지 않는 것

안전·기회 버킷 비율 · 목적별 최소 안전 비율 · 권장 분할 회차 · 목표 달성 확률 · 몬테카를로 횟수 · 목적함수 · Route 히어로 지표

---

## 7. 요구사항 추적성

| 엔드포인트 | 요구사항 | 화면 | ERD 테이블 |
|---|---|---|---|
| `/auth/*` · `/me/onboarding/complete` | FR-IS-01~09, NFR-SE-01~02 | §5, §6 | users, user_settings |
| `/me/risk-profile*` | FR-DG-01~09, FR-MY-03~04 | §7~§10, §16 | risk_profiles |
| `/me/settings` | FR-CM-06, FR-MY-05~06 | §16 | user_settings |
| `/holdings` · `/deposits` · `/krw-assets` | FR-XR-07, FR-IS-04 | §13 | holdings, fx_deposits, krw_assets |
| `/xray` | FR-XR-01~03, FR-XR-05 | §13 | holdings, fx_deposits, krw_assets, security_prices, fx_rates |
| `/xray/attribution` | FR-XR-04, FR-XR-06 | §13 | holdings, security_prices, fx_rates |
| `/fit` · `/fit/preview` | FR-FT-01~04 | §14 | risk_profiles, portfolio_snapshots |
| `/forecast*` · `/events` | FR-FC-01~12 | §12 | forecasts, forecast_factors, model_runs, econ_events, fx_stats |
| `/stress/*` | FR-ST-01~05 | §15 | stress_scenarios, stress_test_runs |
| `/home/summary` | FR-HM-01~08 | §11 | portfolio_snapshots 외 |
| `/market/regime` | FR-SF-01~06 | §2.4, §20 | fx_stats.regime |
| `/ai/explain` | FR-AI-01~08, NFR-AI-01~03 | §17 | user_settings, audit_logs |
| `/route/context` | FR-RT-01~02 (P) | §18, §19 | goals, plans (준비) |
| `/notifications` | 요구사항 근거 없음. 화면 v2 §16에만 존재 | §16 | notifications |

---

## 8. 미결정 항목

- [ ] 호스트명과 최종 앱 이름 — 요구사항 v2 §9 미결정 항목
- [ ] **알림 도메인의 요구사항 근거** — 화면 v2 §16은 급변·일정·계획 재검토 3종, ERD는 스위치 5개·타입 6종. 요구사항 FR-MY에는 항목 자체가 없다
- [ ] **은행·수수료 도메인의 위치** — `banks` · `bank_fx_terms` · `user_banks` · `fx_discount_ratio`는 ERD와 API에만 있고 요구사항·화면 정의서 어느 쪽에도 근거가 없다. 요구사항에 추가할지 스키마에서 내릴지 결정 필요
- [ ] **`goals.is_speculative`의 근거** — 투기 목적 게이트는 ERD에만 있다. 요구사항 범위 밖 항목인 "AML 본 구현"과의 경계 정리 필요
- [ ] `risk_profiles.is_manual`(수동 성향 지정)이 FR-IS-06과 충돌하는지 — API에는 의도적으로 노출하지 않았다
- [ ] `entry_method`의 `open_banking` 값 — FR-XR-08(W) 실제 계좌 연동 제외와 충돌 소지
- [ ] Fit 참고 기준선(`concentration_threshold`)의 산출 근거 — 요구사항 v2 §9 미결정
- [ ] `/ai/explain` 실제 LLM 연결 여부 — 요구사항 v2 §7에서 S 우선순위
- [ ] Route 계산 로직 전체 — 확정 후 본 문서 §6을 정식 명세로 승격
