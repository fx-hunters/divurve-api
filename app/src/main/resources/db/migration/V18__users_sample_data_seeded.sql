-- 샘플 자산 시드 여부 (이슈 #112)
--
-- is_demo 로는 "이 자산이 시드된 샘플인가" 를 알 수 없다. 둘러보기 계정만 is_demo=true 인데,
-- 실연동이 없는 동안에는 일반 가입 계정도 같은 샘플을 받기 때문이다(이슈 #108).
-- is_demo 는 계정의 성격이고, 이 컬럼은 자산의 출처다 — 프론트의 "체험용 데이터" 배지는 이쪽을 본다.
--
-- 실연동(이슈 #109)이 도착하면 가입 계정은 이 값이 false 로 남고, 데모 계정만 true 를 유지한다.
alter table users
    add column sample_data_seeded boolean not null default false;

-- 기존 데모 계정은 전부 샘플을 받은 상태다 — 컬럼이 없던 시절에 만들어졌을 뿐이다.
-- 일반 계정은 백필하지 않는다: 이 컬럼 이전의 가입 계정에는 시드가 없었다.
update users set sample_data_seeded = true where is_demo = true;
