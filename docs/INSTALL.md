# Pleos Work 2.0 설치

Pleos 에뮬레이터 준비는 [공식 개발 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)를 따릅니다. 이 저장소는 SDK·시스템 이미지·인증 정보를 재배포하지 않습니다. 확인 환경은 Mac Apple Silicon / Pleos Connect 2.1.0 ARM64 / API 34입니다.

1. SDK 35와 JDK 17 이상을 준비합니다. 이 버전은 JBR 21로 빌드했습니다.
2. 저장소에서 `./gradlew assembleDebug`를 실행합니다.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`로 Pleos 에뮬레이터에 설치합니다.
4. `ANDROID_HOME` 또는 `ANDROID_SDK_ROOT`를 지정하고 `./scripts/run-local-integration.sh`를 실행합니다.
5. [단말 시험 순서](../integration/README.md)를 따라 코드 승인·결과 전송을 진행합니다.

처음에는 기본 내비 모드이며, 전체 예시 이동은 설정의 앱 지도·이동 시험에서 볼 수 있습니다. 작업을 재시작하려면 시험 단말의 새 예시 업무 시작을 사용합니다. 앱 저장소를 지울 필요가 없습니다.

다른 개발자의 debug 서명은 기존 APK와 다를 수 있습니다. 기존 설치를 지우면 로컬 기록도 지워지므로 업데이트 서명을 먼저 확인하세요. 이 저장소에 서명 키를 포함하지 않습니다.
