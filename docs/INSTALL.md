# Pleos Work 2.3 설치

Pleos 에뮬레이터 준비는 [공식 개발 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다. 이 저장소는 SDK·시스템 이미지·인증 정보를 재배포하지 않습니다. 확인 환경은 Mac Apple Silicon / Pleos Connect 2.1.0 ARM64 / API 34입니다.

## 빌드 없이 바로 보기

1. 접근 권한이 있는 GitHub 계정으로 로그인한 뒤 [2.3 릴리스](https://github.com/minwookshin/pleos-work/releases/tag/v2.3-workflow)의 `Pleos-Work-2.3.apk`를 받습니다. 저장소는 비공개입니다.
2. Pleos 에뮬레이터를 켜고 APK를 에뮬레이터 창에 드래그해 설치하거나, SDK의 `adb`로 아래 명령을 실행합니다. 일반 휴대폰용 APK가 아닌 AAOS/Pleos 차량 앱입니다.

```sh
adb install -r Pleos-Work-2.3.apk
adb shell am start -n com.minwook.pleoswork/.MainActivity
```

3. 차량을 P·정차 상태로 두고 첫 화면에서 택배 또는 장거리 화물을 선택한 뒤 **현재 업무로 → 예시 업무 보기**를 누릅니다. 이미 설치한 사용자는 설정 → **예시 업무로 전환**으로 볼 수 있습니다.
4. 지도와 예시 이동·도착, 현재 수량, 바로 다음·그 다음, … 방문지 목록을 확인합니다. 필요한 경우 설정 → **앱 지도 · 이동 시험**을 선택합니다. 한 칸에서는 지도 위·업무 아래, 넓은 화면에서는 지도 왼쪽·업무 오른쪽입니다.

하단의 세 아이콘은 **현재 업무 · 운송 현황 · 설정**입니다. 업무 영역을 좌우로 스와이프해도 같은 화면으로 이동하며, 지도는 자리를 유지합니다. 세로 스크롤이나 스와이프로 업무 완료가 입력되지는 않습니다.

이 경로는 단말 없이 화면과 예시 이동을 보는 방법입니다. 실제로 결과를 보내고 다음 방문지까지 진행하려면 아래 시험 단말을 연결합니다. APK 설치만으로 회사 데이터가 들어오지는 않습니다.

## 단말 결과부터 다음 방문지까지 시험

1. 같은 릴리스에서 `Pleos-Work-2.3-source.zip`을 풀거나 이 저장소의 `v2.3-workflow` 태그를 받습니다. 서버는 Python 3 표준 라이브러리만 사용하며 Android 앱을 다시 빌드할 필요가 없습니다.
2. 압축을 푼 프로젝트 폴더에서 Python 3와 Android SDK 경로를 확인한 뒤 실행합니다. Mac Android Studio의 일반적인 SDK 경로는 다음과 같습니다. 다른 경로라면 바꿔 주세요.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
sh scripts/run-local-integration.sh
```

3. 이 터미널을 켜둔 채 Mac 브라우저로 [단말 시험 화면](http://127.0.0.1:8797/control)을 엽니다. 에뮬레이터가 여러 대라면 실행 전 `ANDROID_SERIAL`에 사용할 기기 ID를 지정합니다.
4. 차량 앱 설정 → **단말 연결**. Mac에서 같은 8자리 코드를 확인하고 **김민준 · 택배** 또는 **박지호 · 장거리 화물**로 승인합니다. 휴대폰으로 이 로컬 QR 주소에 접속하는 방식은 아닙니다.
5. 차량 설정 → **앱 지도 · 이동 시험**. [택배·화물 시험 순서](../integration/README.md)에 따라 Mac에서 부분 결과·회수·도크·인수·현재 방문지 변경을 보냅니다. 차량 화면은 약 2초마다 갱신됩니다.

도착은 업무 완료가 아닙니다. 수량이 0이 되어도 다음 방문지를 추정해 바꾸지 않으며, Mac 시험 단말에서 현재 작업을 변경하면 다음 목적지 이동·도착으로 이어집니다. 다시 시작할 때는 시험 단말의 **새 예시 업무 시작**을 사용합니다. 앱 저장소를 지울 필요가 없습니다.

이미 8797 서버가 켜져 있으면 기존 단말 페이지를 사용하세요. APK는 에뮬레이터의 `http://127.0.0.1:8797`로 연결하며 실행 스크립트가 `adb reverse`를 설정합니다. 서버를 인터넷에 공개할 필요가 없습니다.

## 직접 빌드할 때만

SDK 35와 JDK 17 이상을 준비하고 `./gradlew -PworkApiUrl=http://127.0.0.1:8797 assembleDebug`를 실행합니다. 확인된 빌드 환경은 JBR 21입니다. 생성 APK는 `app/build/outputs/apk/debug/app-debug.apk`입니다.

앱 버전은 **2.3-workflow / versionCode 30**입니다. `v1.25-demo`의 APK·영상은 현재 흐름과 다릅니다. 기본 내비 목적지 요청은 확인했지만, 기본 내비 지도 배경·실시간 교통·GPS 자동 도착은 해결됐다고 주장하지 않습니다. 앱 내부 지도는 OpenStreetMap 기반 예시 이동입니다. 실제 회사 SSO·센서 연결과 접근성 검증 범위는 [2.3 검증 내용](../WORKFLOW-2.3.md)을 확인하세요.

다른 개발자의 debug 서명은 기존 APK와 다를 수 있습니다. 기존 설치를 지우면 로컬 기록도 지워지므로 업데이트 서명을 먼저 확인하세요. 이 저장소에 서명 키를 포함하지 않습니다.
