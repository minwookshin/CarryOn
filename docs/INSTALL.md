# CarryOn 2.10 설치와 실행

이 APK는 Pleos/Android Automotive 에뮬레이터용입니다. 일반 Android 휴대폰을 대상으로 하지 않습니다. Pleos Connect 설치는 [공식 개발 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다. SDK와 시스템 이미지는 이 저장소에 포함하지 않습니다.

## 1. APK 설치

[CarryOn_CarryOn_release.apk](../apk/CarryOn_CarryOn_release.apk)를 내려받습니다. GitHub 파일 화면에서 **Download raw file**을 선택하세요. 공개 저장소이므로 로그인 없이 받을 수 있습니다.

에뮬레이터를 실행하고 APK를 창에 드래그하거나 다음 명령을 사용합니다.

```sh
adb install -r apk/CarryOn_CarryOn_release.apk
adb shell am start -n com.minwook.pleoswork.demo/com.minwook.pleoswork.MainActivity
```

화면 확인 환경은 **Pleos Connect 2.1.0 ARM64 / API 34 / 2560×1440 px / 160 dpi**입니다. 차량은 P·정차 상태로 둡니다. 시연 APK는 별도 `.demo` 패키지이며 기존 CarryOn의 데이터와 서명을 변경하지 않습니다. 버전은 `2.10-workflow-demo`, versionCode는 `37`입니다.

## 2. Mac 단말 연결

프로젝트 ZIP을 내려받아 압축을 풀거나 저장소를 복제합니다. Python 3와 Android SDK의 `adb`가 필요합니다. Android Studio의 일반적인 Mac SDK 경로는 아래와 같으며, 다른 곳에 설치했다면 해당 경로를 사용합니다.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
sh scripts/run-local-integration.sh
```

터미널은 실행한 상태로 두고 Mac 브라우저에서 [단말 시험 화면](http://127.0.0.1:8798/control)을 엽니다.

1. 차량 앱에서 업무 유형을 선택하고 **현재 업무로**를 누릅니다.
2. **오른쪽 위 설정 → 단말 연결**에서 QR과 8자리 코드를 확인합니다.
3. Mac 단말에 같은 코드를 입력하고 **김민준 · 택배** 또는 **박지호 · 장거리 화물**로 승인합니다.
4. 차량 설정에서 **앱 지도 · 이동 시험**을 선택합니다.
5. Mac 단말에서 처리 결과와 현재 방문지 변경을 보내면 차량 화면에 반영됩니다.

QR의 주소는 같은 컴퓨터에서 실행한 시험 서버입니다. 휴대폰 카메라로 원격 회사 로그인에 접속하는 환경은 포함하지 않습니다. `adb reverse`가 에뮬레이터의 `127.0.0.1:8798`을 Mac 서버로 연결합니다. 서버는 인터넷에 공개하지 않습니다.

서버 없이 화면만 보려면 **예시 업무 보기**를 사용할 수 있습니다. 단말 처리 결과부터 다음 방문지까지 이어서 보려면 위 서버 연결이 필요합니다. [택배·화물 시험 순서](../integration/README.md)

## 3. 확인할 흐름

택배는 한빛아파트 배송 12개·회수 2건에서 시작합니다. 일부 배송 결과 → 남은 배송 → 회수 → 현재 방문지 변경 순서로 전송합니다. 물량이 0이 되거나 위치상 도착했다고 해서 다음 목적지로 바뀌지는 않습니다. 시험 단말의 현재 방문지 변경이 다음 안내를 시작합니다.

하단의 두 탭은 **현재 업무·현재 남은 업무**이고 좌우 스와이프로도 전환합니다. 설정은 오른쪽 위에서 열며, 동·라인별 잔량은 추가 상세 페이지 없이 읽습니다. 주차 브레이크 안내는 도착 후 정차/P 상태에서만 나옵니다.

## 4. 직접 빌드

JDK 17 이상(확인 환경 JBR 21), Android SDK 35, Build Tools 35.0.0을 준비합니다.

```sh
./gradlew testDemoReleaseUnitTest testReleaseUnitTest lintDemoRelease assembleDemoRelease
```

위 명령은 서명되지 않은 시연 release를 만듭니다. 직접 설치할 APK를 만들려면 저장소 밖에 보관한 자신의 서명 키를 사용합니다.

```sh
export CARRYON_KEYSTORE=/absolute/path/carryon-demo.jks
export CARRYON_STORE_PASSWORD_FILE=/absolute/path/keystore-password.txt
export CARRYON_KEY_ALIAS=carryon-demo
sh scripts/build-demo-release.sh
```

스크립트는 정렬·서명·서명 검증을 수행하고 `apk/CarryOn_CarryOn_release.apk`를 만듭니다. 비밀번호 파일은 본인만 읽을 수 있게 보관하고, 키와 비밀번호는 커밋하지 않습니다. 다른 키로 서명한 APK는 기존 시연 앱 위에 업데이트할 수 없습니다. 공개된 APK를 실행하기만 할 때는 키가 필요하지 않습니다.

일반 서비스용 `release`는 `-PworkApiUrl=https://your-adapter.example`로 승인된 회사 주소를 지정하고 별도로 서명합니다. 시연 빌드의 로컬 HTTP 허용은 일반 release에 적용되지 않습니다.

## 연결이 안 될 때

- `adb devices`에서 에뮬레이터를 확인합니다. 여러 대면 `ANDROID_SERIAL`에 대상 ID를 설정합니다.
- `8798` 포트를 이미 사용 중이면 기존 시험 서버의 `/control` 페이지를 먼저 확인합니다.
- QR이 만료됐다면 새 코드를 요청합니다. 서버를 재시작해도 기존 작업은 보존됩니다.
- 오래된 APK와 혼동하지 않도록 `.demo` 패키지와 2.10 버전을 확인합니다.
- 공식 기본 내비의 빈 지도·GPS 문제와 앱 내부 예시 이동은 별개입니다.
