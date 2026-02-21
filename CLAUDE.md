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

## 주의사항
- 주방폰은 원격 기기 → 로그는 Firebase로만 접근 가능
- 웹 대시보드 수정 시 앱 재설치 불필요 (GitHub Pages 자동 반영)
