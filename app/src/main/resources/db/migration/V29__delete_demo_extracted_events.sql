-- 이슈 #184: 시연용 예시 원문에서 추출된 가짜 경제 일정을 지운다.
--
-- 무슨 일이 있었나 — MockRawArticleSource 는 실제 보도가 아니라 이 레포가 직접 작성한 예시
-- 문장 3건을 공급한다(source_url 도 demo://sample/... 이라는 가짜 주소다). 추출 배치가 켜진
-- 상태에서 돌면서 LLM 이 그 예시에서 뽑은 일정이 econ_events 에 AI_EXTRACTED 로 저장됐고,
-- GET /events 와 홈 attention 응답에는 source_kind 가 없어 공식 일정과 똑같이 나갔다.
-- 출처를 지어내지 않는다는 원칙(FR-CM-10)에 어긋나는 상태였다.
--
-- ⚠️ source_kind = 'AI_EXTRACTED' 전체를 지우지 않는다. 마이그레이션은 영구 기록이므로 조건이
--    정확해야 한다 — 실제 크롤러가 붙은 뒤 이 문장을 다시 읽는 사람이 "추출분은 다 지워도 되는
--    것" 으로 오해하면 안 된다. 지우는 대상은 시연용 원문에서 나온 것뿐이고, 그 표식이 demo:// 다.
--    지금은 두 조건의 결과가 같지만 뜻이 다르다.
--
-- 재발 방지는 코드 쪽에 있다 — 같은 이슈에서 MockRawArticleSource 가 빈 목록을 돌려주도록
-- 바꾼다. 설정으로만 끄면 누군가 다시 켤 때 같은 일이 반복된다.
delete from econ_events
 where source_kind = 'AI_EXTRACTED'
   and source_url like 'demo://%';
