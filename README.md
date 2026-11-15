# CarryOn

차량 상태와 배송 및 운송 기록을 연결해, 기사가 현재 방문지와 남은 업무를 이어서 확인할 수 있도록 돕는 Pleos Connect용 앱입니다. Kotlin·Jetpack Compose로 개발했으며 Carbon 디자인 체계를 바탕으로 화면을 구성했습니다.

**[APK 다운로드 · 2.10](apk/CarryOn-2.10-release.apk)** · **[53초 시연 영상](demo/CarryOn-Demo.mp4)** · **[설치와 실행](docs/INSTALL.md)**

![CarryOn의 지도와 업무 화면](docs/images/carryon-2.10.jpg)

## 사용 흐름

QR로 업무 연결 → 이동과 현재 방문지 확인 → 도착 → 단말의 배송·회수 결과 반영 → 다음 방문지 안내

내비게이션 옆에서 필요한 업무를 확인하고, 배송 완료·증빙·인수 처리는 기존 단말에서 수행하는 흐름입니다. 앱은 연동된 기록에 따라 남은 수량을 갱신합니다. 도착이나 재탑승을 업무 완료로 판단하지 않으며, 다음 목적지는 업무 시스템의 현재 방문지 변경에 따라 연결합니다.

- 현재 업무와 현재 남은 업무를 하단 탭 또는 좌우 스와이프로 확인합니다. 설정은 기사 이름 오른쪽 위에 있습니다.
- 배송·회수 수량과 동·라인별 잔량을 보여줍니다. 출입구 목록은 읽기 전용입니다.
- 장거리 화물은 접수·도크 배정·상하차·인수를 구분합니다. 냉장·냉동 옵션은 별도 센서 수신 형식으로 다룹니다.
- 주차 브레이크 안내는 현재 방문지에 도착한 뒤 정차/P 상태에서만 표시합니다.

## 바로 실행하기

1. [서명된 release APK](apk/CarryOn-2.10-release.apk)를 받아 Pleos 에뮬레이터에 설치합니다.
2. 이 저장소를 내려받고 `sh scripts/run-local-integration.sh`를 실행합니다. Python 3와 Android SDK의 `adb`가 필요합니다.
3. Mac 브라우저에서 `http://127.0.0.1:8798/control`을 열고, 차량 앱의 **설정 → 단말 연결**에 표시된 코드를 승인합니다.
4. 차량 앱에서 **설정 → 앱 지도 · 이동 시험**을 선택하고, Mac 단말에서 배송·회수 결과와 다음 방문지를 보냅니다.

시연용 APK는 `com.minwook.pleoswork.demo` 패키지로 설치됩니다. 기존 CarryOn 설치와 업무 기록을 덮어쓰지 않습니다. QR은 같은 컴퓨터의 시험 서버를 가리키므로, 이번 실행 환경에서는 Mac 브라우저로 승인합니다. [자세한 실행 순서](docs/INSTALL.md)

## 테스트 환경

| 항목 | 확인 환경 |
|---|---|
| 호스트 | Mac Apple Silicon |
| 에뮬레이터 | Pleos Connect 2.1.0 · ARM64 · AAOS API 34 |
| 화면 | 2560 × 1440 px · 160 dpi, Pleos 분할 창 |
| 앱 | 2.10-workflow-demo · versionCode 37 · 서명된 비디버그 release |
| 빌드 | JBR 21 · Gradle 8.11.1 · AGP 8.9.2 · Kotlin 2.1.20 · compile SDK 35 |
| SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 |
| 시험 단말 | Python 3 표준 라이브러리 · 로컬 서버 8798 포트 |

## 구현 및 시연 범위

시연 영상은 실행 중인 앱을 촬영한 약 53초·1080p·무음 영상입니다. 배송 12개와 회수 2건이 있는 택배 방문지를 보여줍니다. 이동과 도착은 OpenStreetMap 기반 예시 경로로, 단말 처리 결과는 함께 제공한 모의 업무 서버로 재현했습니다. 차량 목업과 QR 강조 효과는 영상 연출입니다.

Pleos 차량 상태와 NaviHelper 목적지 요청을 연결한 코드가 포함되어 있습니다. 공식 기본 내비의 지도 배경·GPS 이동·자동 도착 문제를 해결한 것으로 주장하지 않습니다. 실제 택배사·운송사 API, 회사 SSO, 실차와 냉동 센서 연동은 후속 검증 범위입니다. 장거리 화물과 냉장·냉동 흐름은 앱에서 시험할 수 있으며 이번 짧은 영상에는 포함하지 않았습니다.

`demoRelease`는 로컬 시험 서버만 추가 허용합니다. 일반 `release`는 HTTPS 연결만 허용하며 실제 회사 API 주소·서명 키·운영 계정은 별도로 준비해야 합니다. 앱이 종료된 동안의 백그라운드 수신은 구현하지 않았고, 다시 실행하면 저장된 업무와 수신 위치에서 이어갑니다.

## 문서

- [설치·실행·직접 빌드](docs/INSTALL.md)
- [검증 결과와 직접 시험할 항목](docs/TEST.md)
- [택배·화물 전체 시험 순서](integration/README.md)
- [업무 API·인증·센서 연결 규격](integration/CONTRACT.md)
- [공모전 제출 자료 안내](docs/SUBMISSION.md)
- [변경 내역](CHANGELOG.md) · [이전 버전 기록](docs/history/README.md)

Carbon·ZXing Apache-2.0, IBM Plex OFL-1.1, Lucide ISC/MIT, OpenStreetMap ODbL의 출처와 라이선스 자료를 유지합니다. 공식 Carbon Android 라이브러리나 접근성 인증을 의미하지 않습니다.
