-- 이슈 #140 — IP당 AI 호출 쿼터의 근거.
--
-- 쿼터 카운터를 별도 테이블로 만들지 않고 ai_call_logs 를 그대로 센다(이슈 #140 설계) — 남기는
-- 기록이 곧 쿼터의 근거여야 관리자 화면의 숫자와 차단 판정이 어긋나지 않는다. 그런데 #143 의
-- 이 표에는 요청 출처가 없어 사용자/전역 층만 셀 수 있었다. 이 컬럼이 IP 층을 가능하게 한다.
--
-- 값의 신뢰도는 users.last_login_ip 와 같다 — X-Forwarded-For 는 클라이언트가 위조할 수 있으므로
-- 이 컬럼에 기대는 IP 쿼터는 '벽' 이 아니라 '과속방지턱' 이다. 위조 불가능한 상한은 전역
-- 킬스위치(user_id·전체 건수 기준)다. 길이는 users.last_login_ip 와 같은 45자(IPv6 최대 표기).
alter table ai_call_logs add column client_ip varchar(45);

-- 쿼터는 매 서술 요청마다 최근 24시간을 센다. 이 두 인덱스가 그 세 카운트를 받친다.
-- (전역 카운트는 기존 idx_ai_call_logs_requested_at 이 받는다.)
create index idx_ai_call_logs_client_ip on ai_call_logs (client_ip, requested_at desc);
create index idx_ai_call_logs_user_id on ai_call_logs (user_id, requested_at desc);
