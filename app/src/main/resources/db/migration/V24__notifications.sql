-- 알림 최소 도메인 (이슈 #97, ERD v3.0 §G `notifications`).
-- NotificationController 는 지금까지 List.of() 를 하드코딩해 반환했다 — 전용 테이블 자체가 없었다.
-- 이 마이그레이션은 "조회"만 되게 하는 최소 스키마다. 발송(적재)·읽음 처리 유스케이스는 이 이슈 밖이므로
-- 그때 필요한 컬럼은 지금 만들지 않는다 — 쓰이지 않는 컬럼을 먼저 깔아 두지 않는다.
-- ddl-auto=validate 이므로 컬럼/타입은 JPA 엔티티(domain/notification/entity/Notification)와 정확히 일치해야 한다.
--
-- ERD 와의 차이 3건 (의도된 것):
--  1) 소유자 FK 는 `user_id` 가 아니라 이 레포의 기존 관례인 `owner_id`
--     (holdings·fx_deposits·goals·krw_assets 동일, V10 마이그레이션 참고).
--  2) `read_at` 타임스탬프 대신 `is_read` 불리언을 쓴다 — "언제 읽었는지"는 이 이슈 범위 밖이고
--     지금 필요한 것은 "읽었는지 여부"뿐이다. 읽은 시각이 필요해지면 그때 컬럼을 추가한다.
--  3) `dedup_key`·`sent_at`·`goal_id` 는 두지 않는다 — 전부 "발송" 유스케이스가 있어야 의미가 생기는
--     컬럼이라, 발송 경로가 생길 때 그 마이그레이션에서 함께 추가한다.
--
-- kind 값 집합(CHECK)은 ERD의 notification_type ENUM 6종을 그대로 쓴다
-- (step_due/regime_shift/deadline_near/target_zone/safe_mode/concentration). user_settings 의
-- 알림 스위치는 5종뿐이라(notify_safe_mode 가 없다) 이 값과 1:1로 대응하지 않는다 — 그 5종은
-- "사용자가 끌 수 있는 알림 종류"이고, 여기 kind 는 "무엇을 알렸는지"이므로 서로 다른 집합이다.
-- safe_mode 진입 알림은 사용자가 끌 수 없는 시스템 알림으로 읽어, 설정 스위치 집합이 아니라
-- ERD 가 정의한 notification_type 전체 6종을 채택한다.

create table notifications (
    id         uuid         primary key default gen_random_uuid(),
    owner_id   uuid         not null references users(id),
    kind       varchar(32)  not null,
    title      text         not null,
    body       text         not null,
    is_read    boolean      not null default false,
    created_at timestamptz  not null default now(),
    constraint chk_notifications_kind
        check (kind in ('step_due', 'regime_shift', 'deadline_near', 'target_zone', 'safe_mode', 'concentration'))
);

create index idx_notifications_owner on notifications (owner_id, created_at desc);
