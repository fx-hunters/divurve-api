-- 고시 부재 확정 (이슈 #116, 1단계 구멍 탐지).
--
-- fx_rates(V22) 를 만들었지만 계산 경로는 여전히 ECOS 를 실시간 호출한다. 읽기 경로를
-- "DB 우선, 없으면 ECOS 폴백" 으로만 바꾸면 배치가 돈 날은 DB 값이, 빠진 날은 실시간 값이 나가
-- 같은 질문에 사용자·시점마다 다른 수치가 나간다. 일관되게 실시간인 지금보다 나쁘다.
--
-- 그래서 전환 전에 "이 구간이 완전한가" 를 답할 수 있어야 하는데, fx_rates 만으로는 답할 수 없다.
--   빠진 영업일이 보일 때 그것이 (a) 공휴일이라 애초에 고시가 없는 날인지,
--   (b) 배치가 실패해 우리가 못 받은 날인지 구분할 근거가 테이블에 없다.
-- BusinessDayCalendar 는 주말만 제외하므로(공휴일 테이블이 아직 없다) 단순 대조하면 한국 공휴일
-- 15일 내외가 매년 구멍으로 잡히고, "완전할 때만 DB 를 신뢰한다" 는 규칙이 영원히 false 가 된다.
--
-- 이 테이블은 그 (a) 를 기록한다 — "우리가 ECOS 에 실제로 물었고, 그 날짜에 고시가 없었다".
-- 값을 만들어 채우는 것이 아니라 부재라는 관측을 기록하는 것이므로 NFR-DT-01 에 어긋나지 않는다.
-- 그 결과 구멍의 정의가 뺄셈 하나로 떨어진다:
--
--   구멍 = 영업일 − fx_rates 에 있는 날 − fx_rate_absences 에 있는 날
--
-- 백필이 한 번 돈 뒤 남아 있는 구멍은 전부 (b) 다. 이제 구분할 수 있다.
--
-- 공휴일 달력을 직접 들이지 않은 이유 — 대체공휴일·임시공휴일이 매년 바뀌어 유지 대상이 되고,
-- BusinessDayCalendar 를 바꾸면 플래너 회차 날짜와 마감 버퍼까지 함께 움직인다(calc 변경).
-- 부재는 출처에 물어서 알아내는 편이 정확하고 이 이슈의 범위 안에서 닫힌다.
--
-- rate_type 을 키에 넣는 이유는 fx_rates 와 대칭을 지키기 위해서다. 지금 적재되는 것은 mid
-- 하나뿐이지만(ECOS 는 매매기준율만 고시한다), 두 표의 키가 어긋나면 대조하는 쿼리가 특수해진다.
--
-- confirmed_at 은 "우리가 부재를 확인한 시각" 이다. quote_date(고시가 없던 날)와 섞지 않는다.
-- ECOS 가 뒤늦게 그 날짜를 채워 고시하면 백필이 fx_rates 에 행을 넣고 여기 행은 지운다 —
-- 두 표에 같은 (쌍·날짜·종류) 가 동시에 있으면 어느 쪽이 사실인지 알 수 없다.

create table fx_rate_absences (
    pair_code    char(6)     not null references currency_pairs (pair_code),
    quote_date   date        not null,
    rate_type    text        not null,
    confirmed_at timestamptz not null,
    primary key (pair_code, quote_date, rate_type),
    constraint ck_fx_rate_absences_rate_type check (rate_type in ('mid', 'tt_buy', 'tt_sell', 'cash_buy', 'cash_sell'))
);

-- 구멍 판정은 (쌍 · 종류 · 기간) 으로 들어온다. fx_rates 의 인덱스와 같은 모양이어야
-- 두 표를 같은 구간으로 훑는 비용이 대칭이 된다.
create index idx_fx_rate_absences_pair_type_date on fx_rate_absences (pair_code, rate_type, quote_date desc);
