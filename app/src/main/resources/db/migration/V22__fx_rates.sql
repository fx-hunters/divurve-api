-- 일별 환율 저장 (이슈 #111, ERD v3.0 §0.E · §6 구현순서 2단계).
--
-- 지금까지 환율은 어디에도 저장하지 않고 요청 시점에 ECOS 를 호출해 Caffeine 캐시(6시간)에만
-- 담았다. 그래서
--   - 과거 어느 시점에 어떤 값으로 계산했는지 재현할 수 없고,
--   - 캐시가 비면 ECOS 장애가 그대로 서비스 장애가 되며,
--   - "환율 데이터가 실제로 들어오고 있는가" 를 확인할 대상 자체가 없었다.
--
-- rate_type ENUM 은 ERD 대로 5종을 만들되, 이번 단계에서 적재하는 것은 mid 하나뿐이다.
-- ECOS 731Y001 은 매매기준율만 고시한다. tt_*/cash_* 는 은행별 스프레드로 파생되는 값이고
-- 그 스프레드는 현재 BankFxTermsMaster 가 갖고 있다 — 없는 값을 만들어 저장하지 않는다(NFR-DT-01).
-- ENUM 을 미리 5종으로 만드는 이유는, 나중에 값만 추가하면 되도록 ALTER TYPE 을 피하기 위해서다.
--
-- rate 는 "1 외화당 원화" 다(네이밍 규칙 _rate). ECOS 가 100엔당으로 주는 JPY 는
-- 어댑터가 QuoteUnitNormalizer 로 1단위에 접은 뒤 저장한다 — 저장된 값의 의미가 통화마다
-- 달라지면 이 테이블을 읽는 모든 코드가 통화별 분기를 갖게 된다.
--
-- data_source 는 어디서 긁어왔는가(ECOS)다. fetched_at 은 우리가 가져온 시각이고,
-- quote_date 는 그 값이 고시된 날이다. 둘을 섞지 않는다.

-- rate_type 도 ENUM 이 아니라 text + CHECK 다 (V20 주석과 같은 이유). 특히 이 컬럼은 조회
-- 조건(where rate_type = :rateType)으로 계속 쓰이는데, ENUM 이면 문자열 바인딩이
-- "operator does not exist: rate_type = character varying" 로 죽어 모든 쿼리를 네이티브 +
-- 캐스팅으로 바꿔야 한다. CHECK 에는 5종을 전부 열어 둔다 — 은행 고시 출처가 생기면
-- 마이그레이션 없이 행만 늘리면 된다.

create table fx_rates (
    pair_code   char(6)        not null references currency_pairs (pair_code),
    quote_date  date           not null,
    rate_type   text           not null,
    rate        numeric(14, 6) not null,
    data_source text           not null,
    fetched_at  timestamptz    not null,
    primary key (pair_code, quote_date, rate_type),
    constraint ck_fx_rates_rate_type check (rate_type in ('mid', 'tt_buy', 'tt_sell', 'cash_buy', 'cash_sell')),
    constraint ck_fx_rates_rate_positive check (rate > 0)
);

-- 차트 조회는 (쌍 · 종류 · 기간) 으로 들어온다. PK 선두가 pair_code 라 범위 스캔은 되지만,
-- quote_date 정렬을 인덱스로 끝내려면 rate_type 이 앞에 와야 한다.
create index idx_fx_rates_pair_type_date on fx_rates (pair_code, rate_type, quote_date desc);
