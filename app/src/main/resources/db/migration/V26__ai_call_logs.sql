-- AI 호출 로그 (이슈 #143).
--
-- 토큰 사용량은 이미 수집하고 있었다 — AnthropicMessageClient 가 SDK message.usage() 에서 읽어
-- ClaudeMessageClient.Completion 으로 돌려준다. 그런데 두 어댑터(ClaudeAiProvider,
-- ClaudeEconEventExtractor)가 그것을 log.info 로 찍고 버렸다. 배포 환경에서 "이번 주 AI 비용이
-- 왜 늘었는가" 에 답할 근거가 서버 로그 한 줄뿐이었다. 이 테이블이 그 자리를 대신한다.
--
-- 페이로드(프롬프트·응답 전문)는 담지 않는다 — 이슈 #56 의 범위이며 마스킹 범위 결정을 기다린다.
-- facts 에는 보유 자산 평가액이 들어간다. 여기는 메타만 남겨 그 결정과 무관하게 진행한다.
--
-- 비용 금액 컬럼을 두지 않는 이유 — 단가는 모델별로 개정된다. 토큰만 저장하고 단가는 프로퍼티로
-- 두어 조회 시점에 곱한다. 금액을 적어 두면 단가 개정으로 과거 행의 값이 조용히 달라진다.
--
-- user_id 는 이 레포의 소유자 FK 규칙(V25, 이슈 #137)의 유일한 예외다: cascade 가 아니라
-- **on delete set null** 이다. cascade 로 두면 이슈 #138 의 데모 정리가 매일 도는 동안 데모 계정이
-- 쓴 AI 비용 이력이 함께 사라진다 — 데모 트래픽이 비용의 대부분일 가능성이 큰데 정확히 그 부분이
-- 증발한다. 유저가 사라져도 is_demo·model·토큰 수는 남아 집계가 성립한다.
-- nullable 이어야 하는 이유는 하나 더 있다: purpose='extract' 는 배치에서 돌아 user_id 가 없다.
--
-- 실 호출이 없는 경로(cache_hit·fallback·quota_blocked)도 행을 남긴다. 남기지 않으면 관리자
-- 화면에서 호출량이 줄어든 것이 "캐시가 잘 듣는다" 인지 "장애로 폴백 중" 인지 구분되지 않고,
-- 반대로 캐시 히트를 실 호출로 집계하면 비용이 부풀려 보인다 — outcome 이 그 둘을 가른다.
-- 그 경로들은 토큰이 0 이므로 input_tokens·output_tokens 에 default 0 을 둔다.
--
-- cache_read_input_tokens·cache_creation_input_tokens 는 Anthropic Usage 가 Optional<Long> 로
-- 주는 값이다(SDK 2.61.0 확인). 프롬프트 캐싱을 쓰면 단가가 달라져 이 둘 없이는 비용이 계산되지
-- 않는다. 지금은 캐싱을 쓰지 않아 항상 비어 있지만, 켜는 순간 값이 필요해지고 그때 컬럼을 추가하면
-- 그 이전 기간의 비용은 영구히 계산 불가로 남는다.
--
-- model 이 nullable 인 이유 — 서술 요청이 항상 LLM 을 부르는 것은 아니다. 규약이 확정된 화면
-- (forecast_summary) 밖의 surface 는 MockAiProvider 의 템플릿으로 응답하고, ANTHROPIC_ENABLED 가
-- 꺼진 기본 설정에서는 모든 서술이 그렇다. 그 행은 model 이 비고 토큰이 0 이다 — 관리자 화면에서
-- "요청은 있었지만 비용은 없었다" 가 그대로 보인다. 'template' 같은 가짜 모델명을 적어 넣으면
-- 모델별 집계에 존재하지 않는 모델이 섞인다.
--
-- 모든 서술 요청을 남기는 이유는 하나 더 있다 — 이슈 #140 의 사용자별 쿼터는 "요청 수" 를 세야
-- 하고, LLM 을 실제로 부른 호출만 남기면 그 판정 근거가 없다.
--
-- ddl-auto=validate 이므로 컬럼/타입은 JPA 엔티티(domain/ai/entity/AiCallLog)와 정확히 일치해야 한다.

create table ai_call_logs (
    id                          uuid         primary key default gen_random_uuid(),
    requested_at                timestamptz  not null default now(),
    user_id                     uuid,
    is_demo                     boolean      not null default false,
    purpose                     varchar(16)  not null,
    surface                     varchar(64),
    model                       varchar(128),
    input_tokens                bigint       not null default 0,
    output_tokens               bigint       not null default 0,
    cache_read_input_tokens     bigint,
    cache_creation_input_tokens bigint,
    outcome                     varchar(16)  not null,
    fallback_reason             varchar(32),
    latency_ms                  integer,
    error_summary               text,
    constraint fk_ai_call_logs_user_id
        foreign key (user_id) references users (id) on delete set null,
    constraint chk_ai_call_logs_purpose
        check (purpose in ('narrate', 'extract')),
    constraint chk_ai_call_logs_outcome
        check (outcome in ('success', 'fallback', 'cache_hit', 'quota_blocked', 'error'))
);

-- 관리자 목록은 항상 최신순이다.
create index idx_ai_call_logs_requested_at on ai_call_logs (requested_at desc);
-- narrate/extract 는 비용 성격이 달라 따로 본다.
create index idx_ai_call_logs_purpose on ai_call_logs (purpose, requested_at desc);
