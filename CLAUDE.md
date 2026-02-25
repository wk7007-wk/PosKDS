# PosKDS 프로젝트 규칙

## 앱 개요
주방 KDS(Kitchen Display System) 조리중 건수를 Firebase에 업로드. 주방폰에서 실행.

## Firebase 구조
- **DB**: `poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app`
- **건수 업로드**: `/kds_status.json` — count, completed, time, orders 배열, source
- **로그**: `/kds_log.json`
- **원격 업데이트**: `/app_update/poskds.json` — version, url

## 로그 확인 (원격)
```bash
# 로그
curl -s https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_log.json
# 건수
curl -s https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app/kds_status.json
```

## 웹 대시보드
- GitHub Pages: `https://wk7007-wk.github.io/PosKDS/`
- 파일: `/root/PosKDS/docs/index.html`
- PosDelay 앱의 WebView가 이 대시보드를 로드
- 조리모드, 모니터링, 광고 제어 UI 포함

## 주방폰 제약
- **물리적으로 원격** — 업데이트 거의 불가능 (매장에 있고 접근 어려움)
- 로그는 Firebase로만 접근 가능
- **KDS 앱 수정/업데이트 전제 금지** — 데이터 보정은 항상 수신 측(PosDelay/웹)에서 처리
- 웹 대시보드 수정 시 앱 재설치 불필요 (GitHub Pages 자동 반영)

## KDS 데이터 신뢰도
- **탭 건수(조리중 N) 신뢰**: KDS `count` 값은 탭 헤더에서 추출 — 가장 정확한 소스
- **orders 배열 불안정**: `rootInActiveWindow`가 systemui 반환 시 orders=[] (주문번호 추출 실패)
- **count>0 + orders=[] → 0 보정 금지**: 탭 건수가 정확, orders 빈배열은 추출 실패일 뿐
- **추출 실패 시 이전 값 유지**: count=null + orders=[] → UI 전환 중 일시적 실패. 절대 0으로 강제하지 않음
- `orders` 없이 `count`만 올 때 → 30분간 건수 변동 없으면 0으로 강제 보정 (웹 대시보드)
- `orders` 있을 때 → 25분 초과 개별 주문 차감 (기존 필터)
- **KDS 건수 즉시 반영**: 안정화 지연 삭제됨 — PosDelay/웹 모두 즉시 적용
- **completed 필드**: 완료탭 건수 추출 (트리탐색 fallback), 조리완료 버튼 누를 때 증가

## KDS rootInActiveWindow 문제 (핵심 버그 패턴)
- **증상**: KDS가 조리중 건수를 감지했다가 수초 내 0으로 되돌림
- **원인**: `rootInActiveWindow`가 KDS 앱이 아닌 systemui/잠금화면 반환 → 추출 실패
- **해결 (KDS 측)**: `findKdsRoot()` — 패키지명으로 윈도우 탐색 (`windows` API + `flagRetrieveInteractiveWindows`)
- **해결 (수신 측)**: count>0+orders=[] 신뢰, 0 안정화 90초, 추출 실패 시 이전 값 유지
- **교훈**: 접근성 서비스에서 `rootInActiveWindow`는 항상 대상 앱 윈도우를 반환하지 않음. 교차검증으로 0을 강제하면 오히려 정확한 값을 덮어씀
