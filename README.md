# MOA · v0.2

따로 사는 다섯 가족이 사진을 올리고, 하트로 반응하는 안드로이드 앱입니다. 게임은 후속 버전에서 추가합니다.

| 프로젝트 | 기술 |
| --- | --- |
| `android/` | Kotlin 2.3.10, Jetpack Compose, Material 3, OkHttp, Coil |
| `server/` | Java 21, Spring Boot 4.1.2, Spring Security, JDBC, SQLite |
| `scripts/` | PowerShell 7: 서버 실행, 가족 초대·로그인 해제, APK 서명, 임시 접속 데모 |

Android 10(API 29) 이상 / compile·target SDK 36을 사용합니다. Android Studio는 `android/`, IntelliJ IDEA는 `server/`를 엽니다. 두 프로젝트의 Gradle 빌드는 독립적입니다.

## 구현된 기능

- 다섯 개의 고정 가족 계정, 일회용 초대코드 로그인, 90일 로그인 유지, 로그아웃
- 휴대폰의 Android Keystore 키로 로그인 정보를 AES-GCM 암호화하여 보관
- 사진 1~5장과 설명 업로드, 전송률·실패 알림, 동일 요청 재시도 중복 방지
- 최신순 앨범과 페이지네이션, 사진 크게 보기, 원본을 `Download/MOA`에 저장
- 하트 추가·취소, 본인 게시물 삭제 확인, 원본·미리보기 전체 인증
- 집 PC의 SQLite 및 사진 파일 저장, 썸네일·미리보기 생성, EXIF 회전 반영

현재 **JPG·PNG, 한 장당 10MiB, 설명 500자**를 지원합니다. HEIC·동영상·댓글·게임·푸시 알림은 포함하지 않습니다. 업로드 중 앱 프로세스가 종료되면 전송이 중단될 수 있습니다. 실패 시 초안은 현재 앱 실행 중에 유지되며, 앱을 완전히 종료한 뒤까지 보관하지는 않습니다.

## 1. 빌드

PowerShell 7에서:

```powershell
Set-Location D:\MOA
$env:JAVA_HOME = 'D:\java\jdk-21'
$env:GRADLE_USER_HOME = 'D:\MOA\.tools\gradle-home'
$env:ANDROID_HOME = 'D:\MOA\.tools\android-sdk'
$env:ANDROID_USER_HOME = 'D:\MOA\.tools\android-user'

Set-Location server
.\gradlew.bat check bootJar
Set-Location ..\android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

현재 PC에는 위 경로에 SDK와 빌드 도구를 준비했습니다. 다른 PC에서는 JDK 21, SDK 36·Build Tools 35.0.0을 설치하고 `android/local.properties`에 로컬 SDK 경로를 지정합니다. 시스템 전역 Java 설정은 바꾸지 않습니다.

## 2. 가족 초대코드 발급

서버 JAR를 빌드한 뒤 저장소 루트에서:

```powershell
.\scripts\Manage-Family.ps1 -Member me -DisplayName '내 이름'
.\scripts\Manage-Family.ps1 -Member sister -DisplayName '누나 이름'
.\scripts\Manage-Family.ps1 -Member brother-in-law -DisplayName '매형 이름'
.\scripts\Manage-Family.ps1 -Member mom -DisplayName '엄마'
.\scripts\Manage-Family.ps1 -Member dad -DisplayName '아빠'
```

코드는 `.tools/private/invite-*.txt` 파일에만 저장하고 콘솔이나 Git에 넣지 않습니다. 가족 각자에게 해당 파일의 코드만 직접 전달하세요. 코드는 7일 이내 한 번 사용할 수 있고, 같은 계정에 새 코드를 발급하면 이전 미사용 코드는 무효화됩니다. 초대코드 재발급 자체는 이미 로그인한 휴대폰을 로그아웃시키지 않습니다.

휴대폰을 분실했거나 해당 가족의 모든 기기 로그인을 해제하려면:

```powershell
.\scripts\Manage-Family.ps1 -Member mom -Revoke
```

이후 새 초대코드를 발급합니다. 계정 수를 늘리는 공개 회원가입 API는 없습니다. 별도 구글 로그인 프로젝트나 SMS 서비스가 필요하지 않습니다.

## 3. 서버와 앱 실행

```powershell
.\scripts\Run-Server.ps1
```

데이터는 **실행한 폴더와 무관하게 `D:\MOA\data`**에 저장됩니다. 기본 바인딩은 `127.0.0.1:8080`이며 공유기·방화벽·절전 설정을 변경하지 않습니다. 환경에 맞는 JDK를 자동으로 찾지 못하면 `-JavaHome 'JDK 21 경로'`를 지정하세요.

앱의 로그인 화면에서 서버 주소와 초대코드를 입력합니다.

- 에뮬레이터: 디버그 앱에 `http://10.0.2.2:8080`
- USB 연결 휴대폰: 아래 명령 후 디버그 앱에 `http://127.0.0.1:8080`
- 원격 가족 휴대폰: 관리자가 설정한 `https://...` 주소

```powershell
& 'D:\MOA\.tools\android-sdk\platform-tools\adb.exe' reverse tcp:8080 tcp:8080
```

배포용 앱은 HTTPS만 허용합니다. 개발 빌드도 HTTP는 위 루프백·에뮬레이터 주소에만 허용합니다. 로그인은 해당 서버 주소에 귀속되며 다른 서버로 리다이렉트하지 않습니다.

## 4. 가족이 설치할 APK

서명 키는 한 번만 생성하고 별도로 안전하게 백업합니다. 기존 키를 덮어쓰지 않습니다.

```powershell
.\scripts\New-AndroidSigningKey.ps1
Set-Location android
.\gradlew.bat :app:assembleRelease '-Pmoa.signingProperties=D:/MOA/.tools/private/android-signing.properties'
```

결과: `android/app/build/outputs/apk/release/app-release.apk`

Expo Go나 앱 개발 서버는 필요 없습니다. 가족은 APK를 설치하고, 최초 실행 시 HTTPS 서버 주소와 본인의 초대코드를 입력합니다. 다음 업데이트도 같은 서명 키를 사용해야 합니다. 코드·서명 설정·키·DB·사진은 모두 Git에서 제외합니다.

## 외부 접속 및 운영

현재 코드와 로컬 실행 검증은 준비되어 있습니다. 공개 Tunnel 실행은 별도 승인 단계이며, 승인 전에는 외부에 서버를 열지 않습니다. 자세한 내용은 [홈 서버 안내](docs/home-server.md)를 참고하세요.

서버·앱 테스트와 Android Lint는 GitHub Actions에서 자동 실행됩니다. [Git 계정 안내](docs/git-account.md)처럼 이 PC의 저장소 전용 푸시 계정은 `HS-hyeokjin`입니다.
