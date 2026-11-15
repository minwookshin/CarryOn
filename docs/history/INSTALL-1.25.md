# 다른 컴퓨터에서 실행하기

## 영상만 확인

릴리스의 `Pleos-Delivery-Pickup-Full-Workflow.mp4`를 재생하세요. Android Studio 없이 흐름을 확인할 수 있습니다.

## Pleos 에뮬레이터 준비

이 앱은 차량용 Android 앱입니다. Mac Apple Silicon의 Pleos Connect 2.1.0 ARM64 / AAOS API 34 환경에서 확인했습니다. 일반 휴대폰에 설치해서 같은 차량 환경을 체험하는 용도가 아닙니다.

[Pleos 공식 설치 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)에 따라 Android Studio에서 Pleos 시스템 이미지를 추가하고 Automotive AVD를 만드세요. 본 공유본에는 SDK와 에뮬레이터 이미지를 포함하지 않습니다. 설치 시 제공자의 약관과 접근 절차는 각자 확인합니다.

공식 안내의 API 34, Cold boot, RAM 4GB 설정을 따릅니다. Cold boot 설정은 차량 VHAL 사용에 필요합니다. 자세한 내용은 [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)을 참고하세요.

## APK 설치

1. Android Studio의 Device Manager에서 Pleos AVD를 실행합니다.
2. 릴리스의 `Pleos-Work-1.25.apk`를 받습니다.
3. Android SDK의 `platform-tools`가 PATH에 있는 터미널에서 아래 명령을 실행합니다.

```sh
adb devices
adb -s <Pleos-에뮬레이터-일련번호> install -r /다운로드한/경로/Pleos-Work-1.25.apk
```

`<Pleos-에뮬레이터-일련번호>`는 첫 명령에 나온 Pleos 기기 값으로 바꿉니다. 에뮬레이터 앱 목록에서 `배송`을 엽니다. 이 문서의 경로는 예시이므로 자신의 다운로드 위치를 사용하세요.

## 첫 실행

- 차량을 P·0km/h 상태로 두고 앱의 필요한 차량 정보 접근을 허용합니다.
- **Mac에서 연결 체험 → 김민준 → QR 스캔 · 데모 → 이 차량에 연결**을 선택합니다.
- **앱 지도 · 이동 체험** 모드에서 안내를 시작합니다. 넓은 창에서는 지도와 작업 화면이 나란히 보입니다.
- 배차를 다시 확인하려면 상단 목록 아이콘, 처음부터 진행하려면 설정의 **처음부터 다시 체험**을 사용합니다.

실제 단말 인증·TMS 연동은 없습니다. QR은 앱 안의 단말 체험으로 연결하며, 휴대폰 카메라로 스캔하는 것만으로 실제 연결되지는 않습니다.

## 빌드해서 실행

저장소 루트를 Android Studio로 열고 SDK 35 및 JDK 17 이상을 설정한 뒤 Gradle Sync를 진행합니다. Pleos SDK 의존성은 공식 Maven 저장소에서 내려받으므로 처음에는 인터넷 연결이 필요합니다.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

생성 파일은 `app/build/outputs/apk/debug/app-debug.apk`입니다. Android Studio에서 Pleos AVD를 실행 대상으로 선택해 Run을 사용해도 됩니다. 다른 컴퓨터에서 새로 빌드한 debug APK는 서명이 달라 기존 공유 APK 위에 업데이트 설치되지 않을 수 있습니다. 기존 설치를 지우면 로컬 진행 기록도 초기화되므로 필요할 때만 직접 처리하세요.
