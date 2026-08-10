#!/usr/bin/env bash
# 부하 측정 1회를 시계열로 쪼개, 호스트 간섭 구간을 제외한 지표를 뽑는다.
#
# 왜 필요한가:
#   2026-08-09, 동일 코드/동일 도착률(10 req/s)로 4회를 재면 P95가
#   5862 / 5166 / 3502 / 633 ms로 나왔다. 부하는 일정한데 지연만 요동쳤다.
#   시계열을 뜨니 원인이 드러났다 — 런 중간 60초 구간에서만 지연이 20배로 뛰고
#   앞뒤 구간은 175ms로 멀쩡했다. 부하가 일정한데 지연이 튀었다 돌아오므로
#   서버 포화가 아니라 호스트에서 무언가가 끼어든 것이다.
#
#   런 전체를 버리면 대부분의 런을 버려야 한다(간섭이 상시 발생).
#   그래서 런을 10초 버킷으로 쪼개, 조용했던 버킷만 모아 지표를 계산한다.
#   버린 비율을 반드시 함께 출력해서 "얼마나 남은 데이터인지"를 숨기지 않는다.
#
# 조용한 버킷의 정의:
#   버킷 중앙값 <= (버킷 중앙값들의 중앙값) * QUIET_FACTOR (기본 1.5)
#   기준을 "최소 버킷"으로 잡으면 워밍업 직후의 우연히 빠른 한 버킷이 기준이 되어
#   정상 버킷까지 전부 오염으로 몰린다. 중앙값 기준이 이 왜곡을 없앤다.
#   단, 런의 절반 이상이 오염되면 기준 자체가 오염되므로 조용 버킷 비율을 함께 본다.
#
# 포화와 간섭의 구별:
#   간섭 - 조용한 버킷이 다수이고, 오염 버킷이 연속 구간으로 뭉쳐 있다
#   포화 - 거의 모든 버킷이 높고, 조용한 버킷 비율이 낮다
#   따라서 rate를 올려가며 "조용한 구간 P95"를 보면 간섭에 오염되지 않은 무릎이 보인다.
#
# 사용법:
#   bash load-test/ts-check.sh results/knee_r10_cpu.csv [QUIET_FACTOR]

CSV="$1"
FACTOR="${2:-1.5}"

if [ ! -f "$CSV" ]; then
  echo "파일 없음: $CSV" >&2
  exit 2
fi

awk -F, -v factor="$FACTOR" '
function median(arr, cnt,   i, j, tmp) {
  for (i = 1; i <= cnt; i++)
    for (j = i + 1; j <= cnt; j++)
      if (arr[j] < arr[i]) { tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp }
  return (cnt % 2) ? arr[(cnt + 1) / 2] : (arr[cnt / 2] + arr[cnt / 2 + 1]) / 2
}
NR > 1 && $1 == "http_req_duration" && $12 == "measure" {
  if (t0 == 0) t0 = $2
  b = int(($2 - t0) / 10)
  v[b][++n[b]] = $3
  if (b > maxb) maxb = b
}
END {
  if (maxb == 0 && n[0] == 0) { print "measure 구간 데이터 없음"; exit 2 }

  # 버킷별 중앙값
  for (b = 0; b <= maxb; b++) {
    if (n[b] < 10) continue          # 부분 버킷(런 끝자락)은 판정에서 제외
    split("", tmparr)
    for (i = 1; i <= n[b]; i++) tmparr[i] = v[b][i]
    med[b] = median(tmparr, n[b])
    valid[b] = 1
    medlist[++nmed] = med[b]
  }

  split("", ref)
  for (i = 1; i <= nmed; i++) ref[i] = medlist[i]
  base = median(ref, nmed)
  cutoff = base * factor
  print "  버킷별 중앙값 (10초 단위)"
  for (b = 0; b <= maxb; b++) {
    if (!valid[b]) continue
    mark = (med[b] <= cutoff) ? "조용" : "오염"
    printf "    %3ds-%3ds  n=%3d  중앙값=%6.0fms  [%s]\n", b*10, b*10+10, n[b], med[b], mark
    if (med[b] <= cutoff) {
      quiet++
      for (i = 1; i <= n[b]; i++) all[++cnt] = v[b][i]
    } else dirty++
  }

  total = quiet + dirty
  qpct = total > 0 ? quiet * 100.0 / total : 0

  # 조용한 버킷만 모아 백분위
  for (i = 1; i <= cnt; i++)
    for (j = i + 1; j <= cnt; j++)
      if (all[j] < all[i]) { t = all[i]; all[i] = all[j]; all[j] = t }
  i50 = int(cnt * 0.50); if (i50 < 1) i50 = 1
  i95 = int(cnt * 0.95); if (i95 < 1) i95 = 1
  i99 = int(cnt * 0.99); if (i99 < 1) i99 = 1
  p50 = all[i50]; p95 = all[i95]; p99 = all[i99]

  printf "\n  조용한 버킷: %d/%d (%.0f%%)   기준 중앙값 %.0fms, 컷오프 %.0fms\n", quiet, total, qpct, base, cutoff
  printf "  조용 구간 지표 (n=%d):  P50 %.0fms   P95 %.0fms   P99 %.0fms\n", cnt, p50, p95, p99
  if (qpct < 50)
    print "  주의: 조용한 버킷이 절반 미만이다. 포화이거나 간섭이 런 전체를 덮었다 — rate를 낮춰 재확인할 것."
}
' "$CSV"
