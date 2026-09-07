# Divurve 임시 관리자 콘솔 — 프론트엔드 AI Agent 프롬프트

> 이 문서를 프론트엔드 레포의 AI 에이전트에게 **그대로 붙여넣어** 사용한다.
> 백엔드 이슈: [#111](https://github.com/fx-hunters/divurve-api/issues/111)

---

너는 Divurve 백엔드(`/api/v1`)에 붙는 **임시 운영자용 관리자 콘솔**을 만든다.
디자인보다 **사실을 있는 그대로 보여주는 것**이 우선이다. 값을 꾸미거나 보정하지 마라.

## 0. 절대 규칙

- **프론트에서 어떤 수치도 계산하지 않는다.** 합계·비율·변화율 전부 서버 응답값만 표시한다.
  응답에 없는 값은 화면에서 만들지 말고 `-` 로 둔다.
- 모든 성공 응답은 `{ "data": ..., "meta": ... }` 로 감싸여 있다. 실제 내용은 `data` 안에 있다.
- 응답 필드는 전부 `snake_case` 다. 그대로 쓰고 축약하지 마라.
- 에러 응답은 `{ "error": { "code", "message", "field" } }` 형태다. `code` 는 닫힌 집합이다:
  `VALIDATION_FAILED`(400) · `UNAUTHORIZED`(401) · `FORBIDDEN`(403) · `NOT_FOUND`(404) ·
  `DUPLICATE_RESOURCE`(409) · `NOT_IMPLEMENTED`(501) · `INTERNAL_ERROR`(500).
  `message` 는 한국어이며 그대로 사용자에게 보여줘도 된다.

## 1. 인증

- 로그인: `POST /api/v1/auth/login` `{ "email", "password" }`
  → `data.access_token` · `data.refresh_token` · `data.expires_in`(초) · `data.is_demo` ·
  `data.onboarded`
- 이후 모든 요청에 `Authorization: Bearer <access_token>`.
- `/api/v1/admin/**` 은 **관리자 계정만** 통과한다.
  - 토큰이 없거나 무효 → **401 `UNAUTHORIZED`** → 로그인 화면으로.
  - 로그인은 됐지만 관리자가 아님 → **403 `FORBIDDEN`** → "관리자 권한이 없는 계정입니다" 를 띄우고
    로그인 화면으로. **403 에서 토큰 갱신을 시도하지 마라** — 갱신해도 권한은 생기지 않는다.
- access token 은 30분이다. **401 일 때만** `POST /api/v1/auth/refresh` `{ "refresh_token" }` 로
  갱신하고, 실패하면 로그아웃 처리한다.

> 관리자 계정은 백엔드가 환경변수(`ADMIN_EMAIL`/`ADMIN_PASSWORD`)로 기동 시 만든다.
> 프론트에 관리자 가입·승격 화면을 만들지 마라.

## 2. 화면 5개

### 2-1. 사용자 목록 — `GET /api/v1/admin/users`

쿼리: `?q=<이메일·이름 검색>&is_demo=<true|false>&page=0&size=50`

응답 `data`: `items[]` · `page` · `size` · `total_elements` · `total_pages`

`items[]` 컬럼 (표에 이 순서로):
`id` · `email` · `name` · `role` · `is_demo` · `sample_data_seeded` · `created_at` ·
`onboarded_at` · `last_login_at` · `last_login_ip`

- **`email` 이 곧 로그인 ID 다.** 이 스키마에 별도 `username` 은 없다. "아이디" 열을 만들지 말고
  `email` 을 그대로 써라.
- `is_demo=true` 는 둘러보기 계정이다. `POST /auth/demo` 호출마다 새로 생기므로 많을 수 있다.
- `sample_data_seeded` 는 **다른 사실**이다 — 자산이 시드된 샘플인지 여부. 일반 가입 계정도
  현재는 샘플을 받으므로 `is_demo` 와 갈린다. 두 배지를 구분해서 보여라.
- `last_login_at` / `last_login_ip` 는 아직 접속한 적 없으면 `null` → `-` 로 표시.
  **마지막 1건만 저장된다** — 접속 이력이 아니다. 화면에 "마지막 접속" 이라고 정확히 써라.
- 필터: 검색 입력(`q`), 데모 포함/제외 토글(`is_demo`), 페이지 이동.
- 행 클릭 → 2-2 로 이동.

### 2-2. 사용자 상세 데이터 — `GET /api/v1/admin/users/{id}/data`

응답 `data` 안의 도메인별 배열:
`holdings` · `fx_deposits` · `krw_assets` · `goals` · `plans` · `plan_steps` ·
`risk_profile`(객체 또는 null) · `user_settings`(객체 또는 null) · `stress_test_runs`

- **응답에 들어 있는 모든 키를 표의 컬럼으로 그대로 렌더한다.** 컬럼을 고르지 마라 —
  운영 점검이 목적이라 "무엇이 실제로 저장돼 있는가" 를 봐야 한다.
- 배열이 비면 "없음" 을 표시하되 **섹션은 남겨둬라**. 테이블이 사라지면 데이터가 없는 건지
  호출을 안 한 건지 구분할 수 없다.
- 도메인별 접힘/펼침 섹션 + 각 섹션 제목에 건수 표시.
- 소유자 참조는 `owner_id`(UUID) 로만 온다. 사용자 이름을 다시 붙이지 마라.
- 상단에 2-1 에서 받은 사용자 요약(`GET /api/v1/admin/users/{id}`)을 함께 보여주면 좋다.

### 2-3. 통화 마스터 — `GET /api/v1/admin/currencies`

응답 `data`: `currencies[]` 와 `currency_pairs[]` 두 표.

`currencies[]`: `currency_code` · `name_ko` · `symbol` · `minor_units` · `quote_unit` ·
`usd_side` · `is_home_currency` · `is_supported` · `support_note` · `color_token` · `sort_order`

- `is_supported=false` 는 **환율을 조달할 수 없는 통화**다(표시 불가가 아니다). 회색 처리하고
  `support_note`(사유)를 반드시 함께 보여라. 현재 GBP 가 여기 해당한다.
- `quote_unit=100` 은 100단위 고시라는 뜻이다(JPY).

`currency_pairs[]`: `pair_code` · `base_currency_code` · `quote_currency_code` · `is_stored` ·
`derive_via_pair_code`

- `is_stored=false` 는 환율을 저장하지 않고 유도하는 쌍이다. **2-4 의 차트에서 조회하면 400 이 난다** —
  통화쌍 선택지에서 비활성 처리하라.

### 2-4. 환율 차트 + 수동 갱신

**조회**: `GET /api/v1/admin/fx-rates?pair_code=USDKRW&from=YYYY-MM-DD&to=YYYY-MM-DD&rate_type=mid`
- `from`/`to` 를 생략하면 오늘 기준 1년이다. `rate_type` 기본값은 `mid`.
- 응답 `data`: `pair_code` · `rate_type` · `from` · `to` · `count` · `points[]`
- `points[]`: `quote_date` · `rate`(1 외화당 원화) · `data_source` · `fetched_at`
- **통화쌍 선택지는 2-3 의 `currency_pairs` 응답으로 만들어라.** 목록을 하드코딩하지 마라.
- **현재 적재되는 `rate_type` 은 `mid` 하나뿐이다.** 나머지 4종(`tt_buy`/`tt_sell`/`cash_buy`/
  `cash_sell`)은 스키마에만 있고 데이터가 없다 — 선택지에 넣더라도 0건이 정상임을 안내하라.
- 선형 차트 1개(x=`quote_date`, y=`rate`) + 원본 표.
  **이동평균·추세선 같은 파생값을 그리지 마라.** 빠진 날짜를 보간하지도 마라 —
  관측은 영업일에만 존재하고, 없는 값을 채우면 그것이 곧 지어낸 수치다.
- `fetched_at`(우리가 가져온 시각)과 `quote_date`(고시일)는 **다른 값**이다. 섞지 마라.

**갱신 버튼 2개**:
- `POST /api/v1/admin/fx-rates/refresh?lookback_days=14` (기본 14)
  → `data`: `evicted_caches[]` · `total_upserted` · `has_failure` · `refreshed_at` ·
  `elapsed_ms` · `pairs[]`(`pair_code`·`upserted`·`first_date`·`last_date`·`failure_reason`)
- `POST /api/v1/admin/macro/refresh` `{ "series_ids": ["DGS10"] }`
  → `data`: `evicted_caches[]` · `refreshed_at` · `elapsed_ms` ·
  `series[]`(`series_id`·`value`·`as_of`·`source`·`fetched_at`·`failure_reason`)
  **이쪽은 저장하지 않는다** — FRED 연동이 살아 있는지 확인하는 용도다. 화면에 그렇게 명시하라.

> **응답 내용을 그대로 결과 패널에 출력하라.** 성공 토스트만 띄우고 내용을 버리지 마라 —
> 이 화면의 목적이 "정말 갱신됐는가" 확인이다. `has_failure=true` 이거나 `failure_reason` 이
> 있으면 붉게 강조하라. `total_upserted=0` 은 조용히 넘기면 안 되는 신호다.

### 2-5. AI 테스트 2종

**(a) 자연어 설명** — `POST /api/v1/ai/explain`
> 관리자 전용 경로가 **아니라 기존 사용자 API** 다. 로그인 토큰으로 그대로 호출한다.

- 요청: `{ "surface": "forecast_summary", "facts": { ... } }` — `facts` 는 JSON 편집기로 자유 입력
- 응답 `data.explanation`: `sentences[]` · `sentence_count` · `explain_level` · `explain_domain` ·
  `fallback`
- 응답 `data.verification`: `numeric_match` · `blocked_phrases[]`
- **`fallback=true` 면 눈에 띄게 경고하라** — LLM 결과가 검증에 걸려 고정 템플릿이 나간 상태다.
  `verification` 을 반드시 함께 보여라: 어떤 검증에서 걸렸는지가 이 화면의 핵심 정보다.
- `explain_level`/`explain_domain` 은 요청이 아니라 **로그인 사용자의 설정**에서 온다.
  입력란을 만들지 마라.
- **이 API 는 실패해도 200 을 준다.** HTTP 상태로 성공을 판정하지 마라.

**(b) 비정형 데이터 정형화** — `POST /api/v1/admin/ai/extract-preview`

- 요청: `{ "source_url": "https://...", "text": "뉴스 원문 전문" }`
  - `source_url` 은 선택. `text` 는 필수이며 20,000자까지.
  - 큰 textarea 로 만들어라.
- 응답 `data`: `extractor` · `count` · `previewed_at` · `candidates[]`
- `candidates[]`: `event_date` · `region` · `title` · `impact` · `valid` · `reject_reason`
- **`valid=false` 행의 `reject_reason` 을 붉게 표시하라. 이것이 이 화면의 주된 산출물이다.**
- `event_date`·`region`·`impact` 는 **검증 전 원시값**이라 형식이 어긋난 문자열이나 `null` 이
  올 수 있다. 그대로 보여라 — 그것을 보는 것이 목적이다. 파싱해서 정규화하지 마라.
- `extractor` 가 `NoOpEconEventExtractor` 면 "추출기가 꺼져 있습니다
  (`ANTHROPIC_EXTRACT_ENABLED=false`)" 를 안내하라. 그때 `count=0` 은 정상이다.
- **저장되지 않는 미리보기**임을 화면에 명시하라. 이 호출은 `econ_events` 를 건드리지 않는다.

## 3. 톤과 범위

- 임시 운영 도구다. 라우팅·표·폼 이상으로 공들이지 마라.
- 낙관적 업데이트, 로컬 캐시, 자동 폴링을 넣지 마라. 버튼을 누르면 그때 부르고 결과를 보여준다.
- 숫자는 천단위 구분만 적용하고 **반올림하지 마라** — 원본 자릿수를 그대로 보여준다.
- 이 콘솔은 전 사용자의 데이터를 다룬다. 스크린샷·로그에 남지 않도록 주의를 안내 문구로 넣어라.

## 4. 알려진 한계 (화면에 반영할 것)

| 한계 | 화면에서 할 일 |
|---|---|
| `last_login_*` 는 마지막 1건만. 이력 없음 | "마지막 접속" 이라고 정확히 표기 |
| 비로그인 접속 IP 는 기록되지 않음 | 접속 이력 화면을 만들지 마라 (백엔드 이슈 #114 예정) |
| `rate_type` 은 `mid` 만 적재됨 | 나머지는 0건이 정상임을 안내 |
| 유도 쌍(`is_stored=false`) 차트 조회는 400 | 선택지에서 비활성화 |
| FRED 갱신은 저장하지 않음 | "연동 점검용" 이라고 명시 |
| 알림(`notifications`) 은 항상 빈 배열 | 관리자 화면에 넣지 마라 |
