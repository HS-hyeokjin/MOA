# 집 PC 운영 · v0.3

## 저장 경로

`scripts/Run-Server.ps1`과 `Manage-Family.ps1`은 기본적으로 저장소 루트의 `data/`를 사용합니다.

```text
data/
  moa.db                  SQLite 메타데이터·가족·로그인·하트·주사위·주간 포인트
  moa.db-wal / moa.db-shm  실행 중 SQLite 저널
  photos/
    originals/            업로드한 원본 바이트
    previews/             긴 변 최대 1600px JPEG
    thumbnails/           긴 변 최대 480px JPEG
```

사진의 실제 내용을 읽어 JPEG·PNG 여부와 크기·해상도를 검증합니다. 원본은 재인코딩하지 않으며, 저장 경로는 사용자 파일명이 아닌 서버 UUID로 결정합니다. 미리보기에는 EXIF 회전을 반영하고 메타데이터를 다시 넣지 않습니다. 원본 다운로드에는 원본의 EXIF·위치 정보가 그대로 포함될 수 있습니다.

사진 한 장은 10MiB, 한 게시물은 최대 5장, 전체 multipart 요청은 52MB로 제한합니다. 설명은 500자까지입니다. 현재 HEIC·GIF·WebP·동영상은 지원하지 않습니다.

## 인증

- 기본 가족 ID: `me`, `sister`, `brother-in-law`, `mom`, `dad`. 표시 이름은 초대 발급 시 지정할 수 있습니다.
- 초대는 7일, 한 번 사용 가능합니다. 로그인 토큰은 90일 후 만료됩니다.
- 서버에는 초대코드와 로그인 토큰의 SHA-256 해시만 보관합니다. 원문 토큰은 휴대폰의 Android Keystore 기반 암호화 저장소에 보관합니다.
- 토큰은 Authorization 헤더로 전송합니다. 쿠키·기본 비밀번호 로그인·공개 가입·공개 관리자 API는 제공하지 않습니다.
- 로그아웃은 현재 기기의 서버 세션을 삭제합니다. `Manage-Family -Revoke`는 해당 계정의 모든 세션과 미사용 초대를 삭제합니다.
- 로그인은 원격 주소당 분당 60회로 제한합니다. 전달받은 프록시 헤더를 임의로 신뢰하지 않습니다. Tunnel에서는 가족이 같은 제한을 공유할 수 있습니다.
- 가족 앨범은 로그인한 다섯 명 모두에게 공유됩니다. 작성자만 게시물을 삭제할 수 있습니다.
- 원본·미리보기·썸네일 모두 인증과 `Cache-Control: no-store`를 적용합니다. 앱은 원격 사진의 디스크 캐시를 사용하지 않습니다.

로그인 요청을 제외한 가족 API는 인증이 필요합니다. 상태 API `/api/v1/health` 및 `/actuator/health`만 공개되며, 상세 환경·DB·경로·가족 목록은 공개하지 않습니다.

## 외부 접속

구성은 `가족 앱 → HTTPS → Cloudflare Tunnel → 127.0.0.1:8080`입니다. Tunnel을 사용하면 공유기 포트포워딩이 필요하지 않습니다. 공개 Tunnel 실행은 별도 사용자 승인 후 진행합니다.

### 임시 휴대폰 테스트

공식 배포처에서 검증한 `cloudflared.exe`를 `.tools/cloudflared.exe`에 준비한 뒤, **공개 접속 승인 후** 저장소 루트에서:

```powershell
.\scripts\Start-Demo.ps1
```

서버와 Tunnel을 숨김 창으로 실행하고 임시 HTTPS 주소를 출력합니다. 프로그램 정보와 로그는 `.tools/private/`에만 저장합니다. Windows 서비스나 자동 시작을 등록하지 않습니다. 중지:

```powershell
.\scripts\Stop-Demo.ps1
```

Stop 스크립트는 기록된 PID의 실제 명령이 이 저장소 프로그램인지 확인한 뒤 종료합니다. DB와 사진은 삭제하지 않습니다. 재부팅 뒤에는 재시작해야 하며 Quick Tunnel 주소도 바뀝니다. 앱에서 기존 서버 주소의 로그인 세션은 다른 주소로 보내지 않습니다. 주소가 바뀌면 새 초대코드로 로그인해야 합니다.

Quick Tunnel은 테스트용이고 가동 보장이 없습니다. 정식 운영에는 본인 도메인과 계정의 고정 Tunnel을 연결하세요. Cloudflare 공개 경로에는 이미지·대용량 전송 조건이 적용됩니다. 사진 몇 장까지 허용한다는 수치는 없으며, 압축만 하면 어떤 사용량도 허용된다고 전제하지 않습니다.

공식 자료: [Quick Tunnel](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [공개 Tunnel 설정](https://developers.cloudflare.com/tunnel/get-started/), [CDN 조건](https://www.cloudflare.com/service-specific-terms-application-services/).

## 실행 설정

| 설정 | 기본값 |
| --- | --- |
| `MOA_SERVER_ADDRESS` | `127.0.0.1` |
| `MOA_SERVER_PORT` | `8080` |
| `MOA_DATA_DIR` | 직접 JAR 실행 시 `./data` |

`Run-Server.ps1 -Port ... -DataDirectory ...`의 명시적 인자가 환경 변수보다 우선합니다. 직접 `bootRun`할 때 상대 경로는 현재 작업 폴더 기준이므로 운영은 스크립트나 절대 경로를 권장합니다. `.env` 파일은 자동으로 읽지 않습니다.

## 백업·업데이트

1. 서버를 중지합니다. 데모를 쓰는 경우 `Stop-Demo.ps1`을 사용합니다.
2. `data/` 전체를 별도 드라이브에 복사합니다. SQLite 파일 하나만 복사하거나 실행 중인 WAL을 누락하면 안 됩니다.
3. `.tools/private/moa-release.p12`와 `android-signing.properties`도 별도로 안전하게 백업합니다. 초대 파일은 필요할 때만 가족에게 직접 전달합니다.
4. 새 JAR로 교체한 후 동일한 데이터 경로로 시작합니다. 복구할 때도 서버를 중지한 상태에서 전체 스냅샷을 복원합니다.

v0.3은 시작할 때 `schema.sql`의 `CREATE TABLE IF NOT EXISTS`로 `race_rolls`와 `race_rewards`, 게임 인덱스를 추가합니다. 기존 v0.2 테이블·사진·로그인 정보를 삭제하거나 초기화하지 않습니다. 업데이트 전에 백업하고, 새 JAR를 같은 데이터 경로로 실행하세요. 향후 기존 컬럼 변경은 별도 마이그레이션이 필요합니다.

게임은 PC 시간 기준 `Asia/Seoul`을 사용합니다. Windows 시각을 정확히 맞춰 두세요. 주간 보상은 다음 경기의 첫 게임 조회·주사위 요청에서 트랜잭션으로 결산합니다. 서버가 월요일에 꺼져 있어도 다음 접속 시 누락된 경기들을 한 번씩 처리합니다. 별도 스케줄러·상시 게임 타이머·추가 서비스가 필요하지 않습니다. 게임 API와 규칙은 [운빨 달리기](family-race.md)를 참고하세요.

업로드 도중 프로세스가 비정상 종료되면 DB에 연결되지 않은 파일이 남을 수 있지만 API에서는 조회되지 않습니다. 원본을 자동으로 정리하지 않으며, 디스크 사용량과 복구 백업을 확인해야 합니다.
