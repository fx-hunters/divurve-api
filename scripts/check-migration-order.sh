#!/usr/bin/env bash
#
# PR 이 추가하는 Flyway 마이그레이션이 base 브랜치의 최대 버전보다 큰지 검사한다.
#
# 왜 필요한가 (이슈 #104):
#   V13 이 이미 배포·적용된 V14 보다 낮은 번호로 나중에 develop 에 도착했다. Flyway 는
#   out-of-order=false(기본값) 에서 "적용된 것보다 낮은 버전의 미적용 마이그레이션" 을 만나면
#   기동을 거부한다 — Validate failed. 배포가 죽고 나서야 드러났다.
#
#   테스트로는 잡히지 않는다. Testcontainers 는 매번 빈 DB 에 V1 부터 적용하므로 번호 순서가
#   어떻든 통과한다. 이 규칙은 "base 에 이미 무엇이 있는가" 를 알아야 판정되므로 CI 에서만 볼 수 있다.
#
# 규칙:
#   이 PR 이 새로 추가한 마이그레이션의 최소 버전 > base 브랜치의 최대 버전
#
# 사용법: check-migration-order.sh <base-ref>
set -euo pipefail

BASE_REF="${1:?사용법: check-migration-order.sh <base-ref>}"
MIGRATION_DIR="app/src/main/resources/db/migration"

version_of() { basename "$1" | sed -E 's/^V([0-9]+)__.*/\1/'; }

max_version_in_base() {
    local max=0
    while read -r path; do
        [ -z "$path" ] && continue
        local v; v=$(version_of "$path")
        (( v > max )) && max=$v
    done < <(git ls-tree --name-only "$BASE_REF" "$MIGRATION_DIR/" || true)
    echo "$max"
}

# base 에는 없고 이 브랜치에만 있는 마이그레이션 = 이 PR 이 추가한 것.
# 현재 쪽은 워킹 트리를 본다 — CI 에서는 체크아웃된 트리가 곧 PR 내용이고, 로컬에서는 커밋 전에도
# 검사할 수 있다. HEAD 를 보면 커밋하기 전까지 아무것도 잡지 못한다.
added_migrations() {
    comm -13 \
        <(git ls-tree --name-only "$BASE_REF" "$MIGRATION_DIR/" | xargs -r -n1 basename | sort) \
        <(ls -1 "$MIGRATION_DIR" | sort)
}

BASE_MAX=$(max_version_in_base)
echo "base(${BASE_REF}) 최대 마이그레이션 버전: V${BASE_MAX}"

ADDED=$(added_migrations)
if [ -z "$ADDED" ]; then
    echo "이 브랜치가 추가한 마이그레이션 없음 — 검사할 것이 없다."
    exit 0
fi

echo "이 브랜치가 추가한 마이그레이션:"
echo "$ADDED" | sed 's/^/  /'

FAILED=0
while read -r name; do
    [ -z "$name" ] && continue
    v=$(version_of "$name")
    if (( v <= BASE_MAX )); then
        echo "::error file=${MIGRATION_DIR}/${name}::V${v} 는 base 의 최대 버전 V${BASE_MAX} 보다 크지 않다." \
             "이미 적용된 마이그레이션보다 낮은 번호는 Flyway 검증(out-of-order=false)에서 배포를 죽인다." \
             "V$((BASE_MAX + 1)) 이상으로 개명하라 — 번호를 재사용하지 말고 뒤로 옮겨라."
        FAILED=1
    fi
done <<< "$ADDED"

if [ "$FAILED" -ne 0 ]; then
    echo ""
    echo "낮은 번호가 먼저 실행돼야 한다면(expand → contract 처럼) 관련 파일을 '함께' 뒤로 옮겨"
    echo "상대 순서를 보존하라. 한쪽만 옮기면 순서가 뒤집혀 SQL 이 깨진다."
    exit 1
fi

echo "마이그레이션 순서 정상 — 추가된 버전이 모두 V${BASE_MAX} 보다 크다."
