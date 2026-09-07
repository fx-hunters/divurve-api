-- 통화쌍 마스터 (이슈 #111, ERD v3.0 §0.A · §6 구현순서 1단계).
--
-- FK 대상이 생기므로 pair_code 오타를 DB 가 막는다. fx_rates(V22) 가 이 테이블을 참조한다.
--
-- ★ ERD 와 다르게 시드한 곳이 하나 있다 — is_stored.
--   ERD §4.A 는 저장 쌍을 USDKRW·USDJPY·EURUSD 3개로 못박고 JPYKRW·EURKRW 는 삼각환산으로
--   유도하라고 적었다. 그 모델은 "USD 를 축으로 하는 국제 고시" 를 전제한다.
--   그런데 이 서비스의 1차 출처인 ECOS(한국은행 731Y001)는 USDJPY·EURUSD 를 주지 않는다.
--   실제로 주는 것은 원화 직접 고시 4종뿐이다(application.yml item-codes):
--       USD_KRW=0000001 · JPY_KRW=0000002(100엔당) · EUR_KRW=0000003 · CNY_KRW=0000053
--   ERD 대로 is_stored 를 적으면 "저장한다고 적힌 쌍은 조달할 수 없고, 조달되는 쌍은 저장 안 한다고
--   적혀 있는" 상태가 된다. is_stored 가 거짓말이 되면 이 컬럼을 읽는 모든 코드가 틀린다.
--   그래서 ECOS 가 실제로 고시하는 4쌍을 is_stored=true 로 둔다. 국제 고시(USDJPY 등)를 붙이는
--   시점에 유도 쌍을 추가하면 되고, 그때 derive_via_pair_code 가 처음 쓰인다.
--
-- GBPKRW 행을 만들지 않는 이유 — ECOS 가 고시하지 않고(currencies.is_supported=false) 유도 경로도
-- 없다. ck_currency_pairs_derive 는 저장하지 않는 쌍에 유도 경로를 요구하므로 행을 만들 수 없다.
-- 넣을 수 없다는 것 자체가 "GBP 는 환율을 조달할 수 없다" 는 사실과 일치한다.
--
-- pair_code 는 API 명세 §2 의 6자리 표기(USDKRW)를 쓴다. ECOS 어댑터의 언더스코어 표기(USD_KRW)는
-- 어댑터 경계 밖으로 나오지 않는다.

create table currency_pairs (
    pair_code            char(6) primary key,
    base_currency_code   char(3) not null references currencies (currency_code),
    quote_currency_code  char(3) not null references currencies (currency_code),
    is_stored            boolean not null,
    derive_via_pair_code char(6) references currency_pairs (pair_code),
    constraint ck_currency_pairs_derive check (
        (is_stored and derive_via_pair_code is null)
        or (not is_stored and derive_via_pair_code is not null)
    )
);

insert into currency_pairs (pair_code, base_currency_code, quote_currency_code, is_stored, derive_via_pair_code)
values
    ('USDKRW', 'USD', 'KRW', true, null),
    ('EURKRW', 'EUR', 'KRW', true, null),
    ('JPYKRW', 'JPY', 'KRW', true, null),
    ('CNYKRW', 'CNY', 'KRW', true, null);

-- econ_event_pairs.pair_code 에 FK 를 지금 걸지 않는다 (V14 주석이 예고한 작업).
-- 이미 적재된 행의 pair_code 가 위 4개와 어긋나면 이 마이그레이션이 실패해 배포가 죽는다.
-- econ_event_pairs 는 코드에서 참조하는 곳이 0건이라 FK 부재로 인한 실질 피해가 없다.
-- ERD 정합 작업(이슈 #58)에서 기존 행을 확인한 뒤 붙인다.
