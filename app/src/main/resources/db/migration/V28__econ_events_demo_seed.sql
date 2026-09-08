-- 이슈 #162: econ_events 시연용 예시 일정 시드.
--
-- 왜 필요한가 — 이 이슈가 읽기 경로를 econ_events 로 돌리면서 MockEconomicEventProvider 의
-- 하드코딩 8건이 사라졌다. 그런데 이 표를 채우는 유일한 경로인 추출 배치는 기본이 꺼짐이고
-- (ANTHROPIC_ENABLED · ANTHROPIC_EXTRACT_ENABLED · ANTHROPIC_EXTRACT_SCHEDULE_ENABLED 모두
-- 기본 false), 원문 공급도 아직 MockRawArticleSource 다. 시드가 없으면 표가 확실히 비어
-- GET /events 와 홈 attention.upcoming_events 가 항상 빈 배열이 된다.
--
-- ⚠️ 실제 일정이 아니다. source_kind = 'DEMO_SAMPLE' 이 행 단위로 그것을 말한다 — 이슈 #74 가
--    이 컬럼을 만든 목적이 정확히 "공식 파서·AI 추출·시연용 예시의 신뢰도를 섞지 않는 것"이다.
--    아래 날짜는 시연 구간을 덮도록 고른 값이며 각 기관이 공표한 일정을 옮긴 것이 아니다.
--    출처를 지어내지 않으므로(FR-CM-10) source_url 은 NULL 로 둔다 — V14 가 DEMO_SAMPLE 을
--    위해 이 컬럼의 NULL 을 허용해 두었다.
--
-- 수명 — 고정 날짜라 시간이 지나면 조회 창(홈 14일 · /events 90일) 밖으로 밀려나 다시 빈다.
--        중앙은행 공식 일정 파서가 붙으면(후속 이슈) 이 시드는 삭제한다.
--
-- region 은 EconEventValidator 의 허용 목록, impact 는 1(낮음)~3(높음) 규약을 따른다.
-- EconEventVocabulary 가 조회 시 region → currency_code, impact → importance 로 옮긴다.
-- GLOBAL 은 귀속 통화가 없어 currency_code 가 null 로 나가는 경로다.
insert into econ_events (event_date, region, title, impact, source_url, fetched_at, source_kind)
values
    -- 홈 attention(14일 창)에 걸리는 구간
    ('2026-09-11', 'US', '미국 소비자물가지수(CPI) 발표', 3, null, now(), 'DEMO_SAMPLE'),
    ('2026-09-17', 'US', '미국 연방공개시장위원회(FOMC) 결과 발표', 3, null, now(), 'DEMO_SAMPLE'),
    ('2026-09-19', 'JP', '일본은행 금융정책결정회의', 2, null, now(), 'DEMO_SAMPLE'),
    -- GET /events(90일 창)에만 걸리는 구간
    ('2026-09-24', 'EU', '유럽중앙은행(ECB) 통화정책회의', 3, null, now(), 'DEMO_SAMPLE'),
    ('2026-10-08', 'KR', '한국은행 금융통화위원회', 2, null, now(), 'DEMO_SAMPLE'),
    ('2026-10-30', 'US', '미국 고용상황(비농업 취업자수) 발표', 3, null, now(), 'DEMO_SAMPLE'),
    ('2026-11-20', 'GLOBAL', 'G20 재무장관·중앙은행총재 회의', 1, null, now(), 'DEMO_SAMPLE')
-- 유니크 제약 (event_date, region, title) 과 같은 키다. 추출 배치가 같은 사건을 먼저 넣었다면
-- 그쪽 출처를 남긴다 — 시연용 행으로 덮어쓰지 않는다.
on conflict (event_date, region, title) do nothing;
