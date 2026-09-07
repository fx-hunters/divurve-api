-- 관리자 권한과 접속 기록 (이슈 #111).
--
-- 왜 필요한가:
--   1) 권한 모델이 없었다. users 의 유일한 구분자는 is_demo 뿐이라 "운영자만 볼 수 있는 것" 을
--      표현할 방법이 없었다. /api/v1/admin/** 는 이 컬럼 하나로 갈린다.
--   2) 로그인 성공을 어디에도 남기지 않았다. 누가 마지막으로 언제·어디서 접속했는지 DB 에 붙어도
--      알 수 없었다.
--
-- role 을 ENUM 타입이 아니라 varchar + CHECK 로 두는 이유 — Postgres ENUM 은 값 추가에
-- ALTER TYPE 이 필요하고 JPA 매핑에 커스텀 타입이 든다. 값이 둘뿐이고 늘어날 계획도 없다.
--
-- last_login_ip 를 inet 이 아니라 varchar(45) 로 두는 이유 — inet 은 Hibernate 기본 매핑이 없어
-- 커스텀 타입을 붙여야 한다. IPv6 최대 표기가 45자이므로 문자열로 충분하다. 이 값은 표시용이고
-- 대역 연산(<<=) 을 할 계획이 없다.
--
-- ddl-auto=validate 이므로 컬럼/타입은 JPA 엔티티와 정확히 일치해야 한다.

alter table users
    add column role          varchar(16) not null default 'USER',
    add column last_login_at timestamptz,
    add column last_login_ip varchar(45);

alter table users
    add constraint ck_users_role check (role in ('USER', 'ADMIN'));

-- 관리자 목록 조회는 가입 역순이 기본이다.
create index idx_users_created_at on users (created_at desc);
