# MOA

따로 사는 다섯 가족을 위한 안드로이드 앱입니다. 가족 사진 공유와 각자 편한 시간에 이어 하는 포인트 기반 게임을 목표로 합니다.

## 구성

| 경로 | 기술 | 역할 |
| --- | --- | --- |
| `android/` | Kotlin 2.3.10, Jetpack Compose, Material 3 | 안드로이드 앱 |
| `server/` | Java 21, Spring Boot 4.1.2 | 집 PC에서 실행할 API 서버 |
| `docs/` | Markdown | 개발 및 운영 안내 |

두 프로젝트는 독립적인 Gradle 빌드입니다. Android Studio에서는 `android/`, IntelliJ IDEA에서는 `server/` 폴더를 엽니다. JDK는 **21**을 사용합니다. 기존 시스템의 Java 설정을 바꿀 필요는 없습니다.

안드로이드는 Android 10(API 29) 이상을 지원하고 API 36을 대상으로 합니다. AGP 8.13.2, Kotlin Compose 플러그인 2.3.10, Compose BOM 2026.02.01을 명시적으로 고정했습니다.

## 현재 구현 범위

- Compose 기반 가족 홈 / 사진 / 게임 탭과 밝은 테마·어두운 테마
- 앱에서 실제 서버 상태 API를 호출하는 연결 확인
- Spring Boot 상태 API 및 기본 보안 설정
- Android APK 빌드·Lint / 서버 통합 테스트용 GitHub Actions

**사진 업로드·앨범, 가족 인증, DB, 게임 규칙·포인트, 푸시 알림은 아직 구현하지 않았습니다.** 사진과 게임 탭은 준비 중 화면입니다. 상태 확인을 제외한 서버 경로는 인증 설계를 완료하기 전까지 접근을 차단합니다.

## 빠른 시작 — PowerShell

### 서버

```powershell
$env:JAVA_HOME = 'D:\java\jdk-21' # 본인 JDK 21 경로
Set-Location D:\MOA\server
.\gradlew.bat bootRun
```

다른 터미널에서:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/health
```

예상 응답:

```json
{"application":"MOA","status":"UP"}
```

### 앱

1. Android Studio에서 `android/`를 엽니다.
2. Gradle JDK를 21로 선택하고 Android SDK 36을 설치합니다.
3. 에뮬레이터를 실행하고 앱을 실행합니다.
4. 홈의 **연결 확인**을 누릅니다. 기본 주소 `http://10.0.2.2:8080`은 에뮬레이터에서 PC를 가리킵니다.

실제 휴대폰의 `localhost`는 PC가 아닙니다. USB로 연결한 경우 다음 명령으로 연결할 수 있습니다.

```powershell
adb reverse tcp:8080 tcp:8080
Set-Location D:\MOA\android
.\gradlew.bat :app:installDebug '-Pmoa.debugApiBaseUrl=http://127.0.0.1:8080'
```

원격 테스트는 HTTPS 주소를 지정합니다.

```powershell
.\gradlew.bat :app:assembleDebug '-Pmoa.debugApiBaseUrl=https://api.example.com'
```

배포용 URL은 별도 속성 `moa.releaseApiBaseUrl`로 지정합니다. 기본 `https://api.moa.invalid`는 실제 서비스에 접속하지 않는 예약된 예시 주소입니다. 배포 전 주소 지정과 서명 구성이 필요합니다. 인증서·키·비밀번호를 소스에 넣지 마세요.

## 확인 명령

```powershell
Set-Location D:\MOA\server
.\gradlew.bat check bootJar

Set-Location D:\MOA\android
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

현재 PC에는 SDK를 `D:\MOA\.tools\android-sdk`에 준비했습니다. `adb`가 PATH에 없다면 `D:\MOA\.tools\android-sdk\platform-tools\adb.exe`를 사용하세요. 로컬 빌드 도구는 `.tools/`, SDK 경로는 `android/local.properties`에 있으며 모두 Git에서 제외됩니다. 다른 PC에서는 SDK를 설치한 후 로컬 경로를 지정해야 합니다.

## 개발 원칙

- 주사위·턴·포인트 계산은 서버가 결정합니다. 중복 요청과 동시 요청을 고려해 구현합니다.
- 사진은 집 PC에 파일로 보관하고, 목록은 미리보기를 제공하도록 구현할 예정입니다. 원본은 자동으로 삭제하지 않습니다.
- 가족 인증과 사진별 접근 제어를 구현하기 전에는 외부에 공개하지 않습니다.
- 운영 DB·사진·인증 정보·빌드 도구는 저장소에 올리지 않습니다.

자세한 내용은 [홈 서버 안내](docs/home-server.md)와 [Git 계정 안내](docs/git-account.md)를 참고하세요.
