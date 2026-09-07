-- 통화 마스터 (이슈 #111, ERD v3.0 §0.A · §6 구현순서 1단계).
--
-- 지금까지 통화 정보는 domain/master/CurrencyMaster.java 의 하드코딩 상수 5종이었다. 그래서
--   - "DB 에 등록된 통화" 라는 개념 자체가 없었고,
--   - ECOS 가 고시하지 않는 GBP 를 지원 통화로 내려보내는 것을 막을 자리가 없었다(이슈 #95).
-- ERD 가 처음부터 이 테이블을 1단계로 지정해 두었으므로 스펙을 새로 만들지 않고 그대로 옮긴다.
--
-- 컬럼 의미는 ERD v3.0 §4.A 를 따른다:
--   minor_units  소수 자릿수 (JPY·KRW 0, 대부분 2) — 표시 반올림 자릿수의 유일한 근거
--   quote_unit   호가 단위 — ECOS 는 JPY 를 원/100엔으로 고시한다
--   usd_side     삼각환산 방향. USD 자신은 self, USD 페어에서 USD 의 위치가 base/quote, KRW 는 none
--   is_supported 실제로 환율을 조달할 수 있는가. 표시 가능 여부가 아니라 데이터 조달 가능 여부다
--   support_note 미지원 사유를 사람이 읽을 수 있게 남긴다 — false 만 있고 이유가 없으면 아무도 못 고친다

-- ★ ERD 는 usd_side 를 Postgres ENUM(usd_pair_side)으로 정의했지만 text + CHECK 로 내린다.
-- 이 레포에는 Postgres ENUM 타입이 하나도 없다 — V10 의 krw_assets.kind 와 V14 의
-- econ_events.source_kind 둘 다 ERD 가 ENUM 으로 적은 컬럼을 같은 방식으로 내렸다.
-- 이유는 ddl-auto=validate 다. ENUM 컬럼은 JDBC 타입이 OTHER 라 String 필드(VARCHAR)와
-- 불일치로 잡히고, 맞추려면 엔티티에 @JdbcTypeCode(NAMED_ENUM) + 소문자 자바 enum 상수가 필요하며
-- 조회 파라미터마다 cast(:x as usd_pair_side) 를 붙여야 한다. 얻는 것은 같은 제약 하나뿐이다.
-- 나중에 승격이 필요하면 마이그레이션 한 줄이다:
--   create type usd_pair_side as enum ('self','base','quote','none');
--   alter table currencies alter column usd_side type usd_pair_side using usd_side::usd_pair_side;

create table currencies (
    currency_code    char(3)  primary key,
    name_ko          text     not null,
    symbol           text     not null,
    minor_units      smallint not null,
    quote_unit       smallint not null default 1,
    usd_side         text     not null,
    is_home_currency boolean  not null default false,
    is_supported     boolean  not null default false,
    support_note     text,
    color_token      text,
    sort_order       smallint not null default 99,
    constraint ck_currencies_minor_units check (minor_units between 0 and 4),
    constraint ck_currencies_quote_unit  check (quote_unit in (1, 100)),
    constraint ck_currencies_usd_side     check (usd_side in ('self', 'base', 'quote', 'none'))
);

-- 자국 통화는 하나뿐이다. 부분 유니크 인덱스로 DB 가 강제한다 (ERD §4.A).
create unique index uq_home_currency on currencies (is_home_currency) where is_home_currency;

-- 시드 — 기존 CurrencyMaster 상수 5종 + 자국 통화 KRW.
-- sort_order 는 CurrencyMaster 의 표시 순서(USD·EUR·JPY·GBP·CNY)를 그대로 보존한다.
-- GET /api/v1/currencies 의 응답 순서가 이 값으로 결정되므로, 바꾸면 프론트 표시 순서가 바뀐다.
--
-- GBP 만 is_supported=false 인 이유 — ECOS 731Y001 의 item-codes 에 GBP 가 없다(application.yml).
-- 목표를 GBP 로 세우면 환율 조회가 실패해 계산이 성립하지 않는다. 지금까지는 이 사실이 코드
-- 주석에만 있었고 데이터에는 없어서, /currencies 는 GBP 를 지원 통화로 계속 내려보냈다(이슈 #95).
insert into currencies
    (currency_code, name_ko,      symbol, minor_units, quote_unit, usd_side, is_home_currency, is_supported, support_note,                        color_token,     sort_order)
values
    ('KRW',         '대한민국 원',   '₩',    0,           1,          'none',   true,             true,         null,                                'currency-krw',  0),
    ('USD',         '미국 달러',     '$',    2,           1,          'self',   false,            true,         null,                                'currency-usd',  1),
    ('EUR',         '유로',         '€',    2,           1,          'quote',  false,            true,         null,                                'currency-eur',  2),
    ('JPY',         '일본 엔',      '¥',    0,           100,        'base',   false,            true,         'ECOS 는 원/100엔으로 고시한다',        'currency-jpy',  3),
    ('GBP',         '영국 파운드',   '£',    2,           1,          'quote',  false,            false,        'ECOS 731Y001 미고시 — 환율 조달 불가', 'currency-gbp',  4),
    ('CNY',         '중국 위안',     '¥',    2,           1,          'base',   false,            true,         null,                                'currency-cny',  5);
