-- 환전 플래너 스키마 확장 (플래너 명세 §5·§11·§13, 이슈 #84).
--
-- 플래너 명세가 요구사항 v2 §4.12 미확정을 해소하면서 목표 입력(§5) · 계산 메타데이터(§11.1) ·
-- 계획 상태(§13.1) · 회차 상태(§13.2)가 확정됐다. 이 마이그레이션은 그 값을 담을 컬럼을
-- **추가만** 한다.
--
-- 근거 없는 수치로 지목된 컬럼(plans.safe_ratio · split_count · opportunity_amount ·
-- opportunity_trigger_rate, 명세 §23)의 DROP 은 여기서 하지 않는다. 아직 PlanConfirmService ·
-- PlanController · PlanResponseMapper 가 그 값을 읽고 쓰기 때문이다. 소비자를 걷어낸 뒤
-- 이슈 #85 의 V17 에서 정리한다(expand → contract).
--
-- ddl-auto=validate 이므로 컬럼/타입은 JPA 엔티티와 정확히 일치해야 한다.

-- ---------------------------------------------------------------------------
-- goals — 목표 입력 (명세 §5)
-- ---------------------------------------------------------------------------
-- goal_type: 마감형(deadline) / 정기형(recurring). 마지막 Curve 노드가 "목표 도착"인지
--            "다음 점검"인지를 가른다(명세 §4).
-- allocated_holding_amount: 보유 외화 전체를 자동으로 목표에 넣지 않는다 — 사용자가 이 목표에
--            배정할 금액을 직접 확인한다(명세 §5.1).
-- priority_constraint: 상황이 바뀌었을 때 우선 유지할 조건. 시스템이 알리지 않고 임의로 바꾸지
--            않는다(명세 §17).
alter table goals
    add column goal_type                varchar(255)     not null default 'deadline',
    add column allocated_holding_amount double precision not null default 0,
    add column priority_constraint      varchar(255)     not null default 'amount',
    add column preferred_cadence        varchar(255),
    add column recur_start_date         date,
    add column review_horizon_months    integer,
    add column linked_purpose_name      varchar(255);

-- 기존 kind 값을 goal_type 으로 옮긴다. kind 는 아직 다른 소비자가 있을 수 있어 남긴다 —
-- 중복 여부를 확인한 뒤 후속 이슈에서 정리한다.
update goals set goal_type = 'recurring' where lower(kind) in ('recurring', 'recur', '정기');

-- 마감형의 기본 우선 조건은 금액+날짜, 정기형은 예산이다(명세 §5.2·§5.3).
update goals set priority_constraint = 'budget' where goal_type = 'recurring';

-- ---------------------------------------------------------------------------
-- plans — 계획 상태와 계산 메타데이터 (명세 §11.1·§13.1)
-- ---------------------------------------------------------------------------
-- status 는 is_active 를 대체한다. 여섯 상태(draft/active/needs_review/completed/paused/
-- superseded)를 boolean 하나로 표현할 수 없기 때문이다. is_active 는 소비자가 남아 있어
-- 이번에는 지우지 않고 다음 단계에서 정리한다.
--
-- base_rate/rate_low/rate_high 는 **외화 1단위당 원화로 정규화된** 값이다(명세 §7·§21-6).
-- 100엔 기준 고시를 그대로 저장하면 안 된다.
alter table plans
    add column status         varchar(255),
    add column plan_end_date  date,
    add column policy_version varchar(255),
    add column rate_as_of     timestamptz,
    add column forecast_as_of timestamptz,
    add column base_rate      double precision,
    add column rate_low       double precision,
    add column rate_high      double precision,
    add column spread_ratio   double precision,
    add column fee_krw        bigint,
    add column quote_unit     integer,
    add column budget_state   varchar(255),
    add column low_cost_krw   bigint,
    add column base_cost_krw  bigint,
    add column high_cost_krw  bigint,
    add column superseded_by  uuid references plans(id);

-- 기존 계획의 상태를 채운다. 활성 계획은 active, 나머지는 새 버전에 밀려난 것이므로 superseded.
update plans set status = case when is_active then 'active' else 'superseded' end;
alter table plans alter column status set not null;

-- 목표당 활성 계획은 하나뿐이다(명세 §21-9·10). 지금까지 애플리케이션 코드로만 지켜지던
-- 규칙을 DB 제약으로 올린다 — 동시 요청이 겹치면 코드만으로는 두 개가 만들어질 수 있다.
create unique index uq_plans_active_per_goal on plans(goal_id) where status = 'active';

-- ---------------------------------------------------------------------------
-- plan_steps — 회차 실행 기록 (명세 §11.4·§13.2·§14)
-- ---------------------------------------------------------------------------
-- budget_krw: 정기형은 회차마다 외화 금액이 아니라 원화 예산이 고정된다(명세 §10.3).
-- execution_key: 같은 완료 요청이 중복 전송돼도 두 번 반영되지 않게 한다(명세 §14·§21-12).
alter table plan_steps
    add column budget_krw    bigint,
    add column base_rate     double precision,
    add column low_cost_krw  bigint,
    add column high_cost_krw bigint,
    add column executed_rate double precision,
    add column executed_date date,
    add column execution_key varchar(255);

-- 회차 상태 어휘를 명세 §13.2 로 맞춘다: scheduled → due → completed ↘ skipped.
-- 기존 pending 은 아직 도래하지 않은 회차이므로 scheduled 다.
update plan_steps set status = 'scheduled' where status = 'pending';

-- 중복 완료 방어 (§21-12). null 은 여러 개 허용된다 — 아직 실행되지 않은 회차다.
create unique index uq_plan_steps_execution_key on plan_steps(execution_key)
    where execution_key is not null;

-- 한 계획 안에서 회차 번호는 유일하다. 지금까지 제약이 없어 같은 seq 가 두 번 저장될 수 있었다.
create unique index uq_plan_steps_plan_seq on plan_steps(plan_id, seq);
