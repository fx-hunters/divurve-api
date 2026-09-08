-- 소유자 FK 에 on delete cascade 부여 (이슈 #137).
--
-- 데모 계정 정리(이슈 #138)는 `delete from users where is_demo = true and ...` 한 줄로 소유 데이터가
-- 함께 지워져야 성립한다. 그런데 이 레포의 소유자 FK 는 전부 `references users(id)` 만 붙어 있고
-- 삭제 동작이 없어(= no action), users 행을 지우면 FK 위반으로 실패한다.
--
-- 왜 애플리케이션 코드의 "삭제 순서 목록" 이 아니라 cascade 인가 —
-- 이 레포는 여러 세션이 병렬로 새 소유자 테이블을 붙인다(V24 notifications 가 직전에 들어왔다).
-- 도메인 서비스에 삭제 순서를 하드코딩하면 새 테이블이 추가될 때 그 목록이 갱신되지 않고, 정리
-- 배치가 FK 위반으로 조용히 실패한다. 그 실패는 "더미 데이터가 계속 쌓인다" 라는 증상으로만 보인다.
-- cascade 는 새 테이블을 자동으로 따라간다.
--
-- 제약 이름을 카탈로그에서 찾아 바꾸는 이유 — Postgres 기본 생성명(`<table>_<column>_fkey`)을
-- 그대로 가정할 수 없다. V4 가 `deposits` 를 `fx_deposits` 로 rename 했는데 **테이블 rename 은
-- 제약 이름을 바꾸지 않으므로** 그 FK 는 아직 `deposits_owner_id_fkey` 다. 이름을 문자열로 박으면
-- 그 한 건에서 drop 이 실패한다. 아래 DO 블록은 (자식 테이블, 자식 컬럼, 부모 테이블) 로 실제
-- 제약을 찾아 지우고, `fk_<자식>_<컬럼>` 규약의 명시적 이름으로 다시 건다 — 다음 마이그레이션은
-- 더 이상 이름을 추측하지 않아도 된다.
--
-- 찾지 못하면 예외로 멈춘다. 조용히 건너뛰면 cascade 가 없는 상태로 통과하고, 그 사실은 #138 의
-- 정리 배치가 운영에서 실패할 때에야 드러난다.
--
-- ddl-auto=validate 는 컬럼·타입만 검증하고 FK 삭제 동작은 보지 않으므로 엔티티 변경은 없다.
--
-- `risk_profile_answers` 는 목록에 없다 — 이슈 본문의 "확인 필요" 항목이었는데, V9 가 그 테이블을
-- drop 하고 응답을 `risk_profiles.answers` jsonb 컬럼으로 옮겼다(V9:40). 존재하지 않는 테이블이므로
-- 대상이 아니다. 아래 DO 블록은 찾지 못한 FK 에 대해 예외를 던지므로, 목록에 남겨 뒀다면 이
-- 마이그레이션 자체가 실패했을 것이다.
--
-- 이 규칙의 예외 — 이슈 #143 이 추가할 `ai_call_logs.user_id` 는 cascade 대상이 아니다.
-- cascade 로 두면 #138 이 데모 계정을 지울 때 그 계정이 쓴 AI 비용 이력이 함께 사라진다. 데모
-- 트래픽이 비용의 대부분일 가능성이 큰데 정확히 그 부분이 매일 증발한다. 그 테이블은 아직 없으므로
-- 여기서 할 일은 없고, #143 이 자기 마이그레이션에서 `on delete set null` 로 만든다.

do $$
declare
    target   record;
    existing text;
begin
    for target in
        select *
        from (
            values
                -- users 를 직접 소유자로 갖는 테이블
                ('holdings',             'owner_id',        'users',         'cascade'),
                ('fx_deposits',          'owner_id',        'users',         'cascade'),
                ('goals',                'owner_id',        'users',         'cascade'),
                ('krw_assets',           'owner_id',        'users',         'cascade'),
                ('notifications',        'owner_id',        'users',         'cascade'),
                ('risk_profiles',        'owner_id',        'users',         'cascade'),
                ('user_settings',        'owner_id',        'users',         'cascade'),
                ('stress_test_runs',     'user_id',         'users',         'cascade'),
                -- 소유 그래프의 나머지 — 여기까지 전파되지 않으면 goals 삭제가 plans 에서 막힌다
                ('plans',                'goal_id',         'goals',         'cascade'),
                ('plan_steps',           'plan_id',         'plans',         'cascade'),
                -- 자기참조 back-pointer 는 set null 이다(cascade 가 아니다).
                -- 이 컬럼은 "이 계획을 대체한 계획" 을 가리키는 nullable 포인터이고 소유 관계가
                -- 아니다. 유저 삭제 경로에서는 어차피 plans 전체가 goal_id 로 지워지므로 cascade 가
                -- 더 얻는 것이 없는 반면, 계획 한 버전을 단독으로 지울 때 그 후속 버전까지 연쇄
                -- 삭제되는 사고를 만든다.
                ('plans',                'superseded_by',   'plans',         'set null')
        ) as t(child_table, child_column, parent_table, delete_action)
    loop
        select con.conname
          into existing
          from pg_constraint con
          join pg_class child on child.oid = con.conrelid
          join pg_class parent on parent.oid = con.confrelid
          join pg_attribute att
            on att.attrelid = con.conrelid
           and att.attnum = con.conkey[1]
         where con.contype = 'f'
           and child.relnamespace = current_schema()::regnamespace
           and child.relname = target.child_table
           and parent.relname = target.parent_table
           and att.attname = target.child_column
           and array_length(con.conkey, 1) = 1;

        if existing is null then
            raise exception
                '%.% -> % 외래키를 찾지 못했다 — 스키마가 이 마이그레이션의 가정과 다르다',
                target.child_table, target.child_column, target.parent_table;
        end if;

        execute format('alter table %I drop constraint %I', target.child_table, existing);
        execute format(
            'alter table %I add constraint %I foreign key (%I) references %I(id) on delete %s',
            target.child_table,
            format('fk_%s_%s', target.child_table, target.child_column),
            target.child_column,
            target.parent_table,
            target.delete_action);
    end loop;
end $$;
