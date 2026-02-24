# PosKDS 프로젝트 규칙

## 앱 개요
주방 KDS(Kitchen Display System) 조리중 건수를 Firebase에 업로드. 주방폰에서 실행.

## Firebase 구조
- **DB**: `poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app`
- **건수 업로드**: `/kds_status.json` — count, time, orders 배열
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
- **탭 건수(조리중 N) 신뢰**: KDS `count` 값은 탭 헤더에서 추출 — 정확함
- **orders 배열 불안정**: `rootInActiveWindow`가 systemui 반환 시 orders=[] (주문번호 추출 실패)
- **count>0 + orders=[] → 0 보정 금지**: 탭 건수가 정확, orders 빈배열은 추출 실패일 뿐
- `orders` 없이 `count`만 올 때 → 30분간 건수 변동 없으면 0으로 강제 보정 (수신 측)
- `orders` 있을 때 → 25분 초과 개별 주문 차감 (기존 필터)
- **0 안정화**: 양수→0 전환 시 90초 대기 (KDS 하트비트 3회분, 오탐 방지)
