#!/usr/bin/env bash
#
# fx_rates 의 과거 구멍을 통화쌍·연도 단위로 쪼개 백필한다.
#
# 왜 쪼개는가:
#   POST /api/v1/admin/fx-rates/backfill 은 구멍 하나를 ECOS 한 번의 요청으로 받아오고,
#   받은 값을 한 행씩 upsert 한다. 5년 4쌍(약 5,800행)을 한 번에 돌리면
#     - ECOS 요청이 2,000일치가 되어 읽기 타임아웃 15초(ExternalDataConfig)에 걸리고
#     - DB 왕복이 1만 회를 넘어 HTTP 요청이 버티지 못한다.
#   쌍 하나 × 1년(약 260행)이면 두 벽 모두 넉넉히 피한다.
#
#   백필은 빈칸만 메우므로 중복 실행이 안전하다. 중간에 끊기면 같은 구간을 다시 돌리면 된다.
#   근본 해결(배치 쓰기·비동기 실행)은 별도 이슈다 — 이 스크립트는 그때까지의 운영 도구다.
#
# 사용법:
#   BASE_URL=https://<서버> ADMIN_TOKEN=<관리자 액세스 토큰> scripts/backfill-fx-rates.sh
#
#   FROM_YEAR=2021   시작 연도 (기본: 올해 - 5)
#   PAIRS="USDKRW"   대상 통화쌍 (기본: 저장 대상 4쌍)
#   DRY_RUN=1        호출하지 않고 계획만 출력
set -uo pipefail

BASE_URL="${BASE_URL:?BASE_URL 이 필요하다 (예: https://divurve-api.onrender.com)}"
ADMIN_TOKEN="${ADMIN_TOKEN:?ADMIN_TOKEN 이 필요하다 (관리자 계정의 액세스 토큰)}"

BASE_URL="${BASE_URL%/}"
TODAY="$(date +%F)"
THIS_YEAR="${TODAY%%-*}"
FROM_YEAR="${FROM_YEAR:-$((THIS_YEAR - 5))}"
PAIRS="${PAIRS:-USDKRW EURKRW JPYKRW CNYKRW}"
DRY_RUN="${DRY_RUN:-0}"

# 호출 사이의 숨돌리기. ECOS 에 몰아치지 않게 하고, Render 무료 티어가 따라오게 한다.
SLEEP_BETWEEN="${SLEEP_BETWEEN:-3}"
# 쌍 하나 × 1년이면 30초 안쪽이지만, 콜드스타트와 재시도를 감안해 넉넉히 둔다.
MAX_TIME="${MAX_TIME:-180}"

command -v jq >/dev/null || { echo "jq 가 필요하다: brew install jq"; exit 1; }

declare -a FAILED=()
total_filled=0
total_absent=0

# 한 구간을 백필한다. 실패하면 한 번 더 시도하고, 그래도 실패하면 기록만 남기고 넘어간다
# — 한 구간의 실패가 나머지 19개를 막으면 안 된다.
backfill_one() {
    local pair="$1" from="$2" to="$3" attempt
    for attempt in 1 2; do
        local body
        body=$(curl -sS -X POST \
            --max-time "$MAX_TIME" \
            -H "Authorization: Bearer $ADMIN_TOKEN" \
            "$BASE_URL/api/v1/admin/fx-rates/backfill?pair_code=$pair&from=$from&to=$to" 2>&1)
        local curl_status=$?

        if [ $curl_status -ne 0 ]; then
            echo "    시도 $attempt 실패 (curl $curl_status): ${body:0:200}"
            [ $attempt -eq 1 ] && { sleep 10; continue; }
            FAILED+=("$pair $from~$to (통신 실패)")
            return 1
        fi

        # 서버가 4xx/5xx 를 JSON 으로 돌려주는 경우와 백필 자체가 실패한 경우를 나눈다.
        local pair_result
        pair_result=$(echo "$body" | jq -r '.data.pairs[0] // empty' 2>/dev/null)
        if [ -z "$pair_result" ]; then
            echo "    시도 $attempt 응답을 해석할 수 없다: ${body:0:300}"
            [ $attempt -eq 1 ] && { sleep 10; continue; }
            FAILED+=("$pair $from~$to (응답 이상)")
            return 1
        fi

        local filled absent missing_after reason
        filled=$(echo "$pair_result" | jq -r '.filled')
        absent=$(echo "$pair_result" | jq -r '.confirmed_absent')
        missing_after=$(echo "$pair_result" | jq -r '.missing_after')
        reason=$(echo "$pair_result" | jq -r '.failure_reason // empty')

        if [ -n "$reason" ]; then
            echo "    시도 $attempt 백필 실패: $reason"
            [ $attempt -eq 1 ] && { sleep 10; continue; }
            FAILED+=("$pair $from~$to ($reason)")
            return 1
        fi

        total_filled=$((total_filled + filled))
        total_absent=$((total_absent + absent))
        echo "    채움=$filled 부재확정=$absent 남은구멍=$missing_after"
        [ "$missing_after" != "0" ] && FAILED+=("$pair $from~$to (구멍 $missing_after 일 잔존)")
        return 0
    done
}

# 20여 번을 돌리기 전에 토큰부터 확인한다 — 401 은 재시도해도 소용없고,
# 그 사실을 스무 번째 실패에서 알게 되면 시간만 버린다.
if [ "$DRY_RUN" != "1" ]; then
precheck=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 60 \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/admin/fx-rates/gaps?from=$TODAY&to=$TODAY")
if [ "$precheck" != "200" ]; then
    echo "관리자 API 접근 실패 (HTTP $precheck). BASE_URL 과 ADMIN_TOKEN 을 확인해 달라."
    exit 1
fi
fi

echo "대상: $PAIRS"
echo "기간: ${FROM_YEAR}-01-01 ~ $TODAY"
echo "서버: $BASE_URL"
echo

for pair in $PAIRS; do
    for year in $(seq "$FROM_YEAR" "$THIS_YEAR"); do
        from="${year}-01-01"
        to="${year}-12-31"
        # 올해는 미래를 요청하지 않는다 — 아직 오지 않은 날은 구멍이 아니다.
        [ "$year" = "$THIS_YEAR" ] && to="$TODAY"

        echo "[$pair] $from ~ $to"
        if [ "$DRY_RUN" = "1" ]; then
            echo "    (dry-run)"
            continue
        fi
        backfill_one "$pair" "$from" "$to"
        sleep "$SLEEP_BETWEEN"
    done
done

echo
echo "── 합계 ─────────────────────────────"
echo "채운 날: $total_filled"
echo "부재 확정(공휴일 등): $total_absent"

if [ ${#FAILED[@]} -gt 0 ]; then
    echo
    echo "확인이 필요한 구간 ${#FAILED[@]}건:"
    printf '  %s\n' "${FAILED[@]}"
    echo
    echo "같은 구간을 다시 돌리면 된다 (중복 실행은 안전하다):"
    echo "  PAIRS=<쌍> FROM_YEAR=<연도> $0"
fi

echo
echo "── 최종 커버리지 ────────────────────"
curl -sS --max-time 60 \
    -H "Authorization: Bearer $ADMIN_TOKEN" \
    "$BASE_URL/api/v1/admin/fx-rates/gaps?from=${FROM_YEAR}-01-01&to=$TODAY" \
    | jq -r '.data.pairs[] | "\(.pair_code)  커버리지=\(.coverage_ratio)  남은구멍=\(.missing_business_days)일"' \
    || echo "커버리지 조회 실패 — 관리자 화면에서 직접 확인해 달라."
