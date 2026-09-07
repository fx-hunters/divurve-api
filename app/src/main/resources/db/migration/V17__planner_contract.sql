-- 근거 없는 계산 컬럼 정리 (플래너 명세 §23·§24, 이슈 #85).
--
-- V16 이 새 스키마를 넓혔고(expand), 이제 옛 모델의 컬럼을 걷어낸다(contract).
-- 명세 §23 은 safe_ratio · split_count · opportunity_amount · opportunity_trigger_rate 를
-- "산출 근거 불명"으로 지목했고, §24 는 MVP 계산 정책에서 안전/기회 버킷을 제외했다.
-- 소비자였던 PlanPreviewService · PlanConfirmService · PlanResponseMapper 는 이 이슈에서
-- 새 계약으로 교체되므로 컬럼을 남길 이유가 없다.
--
-- is_active 도 함께 제거한다. V16 이 추가한 status 가 여섯 상태(draft/active/needs_review/
-- completed/paused/superseded)를 담고, 목표당 활성 계획 하나는 uq_plans_active_per_goal
-- 부분 유니크 인덱스가 보장한다. 두 컬럼을 함께 두면 한쪽만 바뀌었을 때 인덱스와 조회가
-- 서로 다른 계획을 가리킨다.

alter table plans
    drop column safe_ratio,
    drop column split_count,
    drop column opportunity_amount,
    drop column opportunity_trigger_rate,
    drop column is_active;

-- 계산 메타데이터는 이제 모든 신규 계획이 채운다 (명세 §11.1 "계산 정책 버전").
-- 기존 행에는 값이 없으므로 not null 로 올리지 않는다 — 어떤 정책으로 계산됐는지 알 수 없는
-- 계획에 임의의 버전을 적어 넣으면 감사 기록이 거짓이 된다.
