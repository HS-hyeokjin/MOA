# 이 저장소의 GitHub 계정

원격 저장소: `https://github.com/HS-hyeokjin/MOA`

이 PC의 `D:\MOA` 저장소에만 아래 설정을 적용합니다. 다른 프로젝트의 전역 Git 작성자·인증 설정은 바꾸지 않습니다.

- 작성자: `HS-hyeokjin`
- 이메일: `90303458+HS-hyeokjin@users.noreply.github.com`
- 원격 URL의 HTTPS 사용자: `HS-hyeokjin`
- 자격 증명: GitHub CLI에 저장된 `HS-hyeokjin` 계정에서 가져오기

작성자 설정과 실제 푸시 인증은 별개입니다. 저장소 전용 credential helper가 `gh auth token --hostname github.com --user HS-hyeokjin`을 사용하도록 구성합니다. 토큰 자체는 저장소나 설정 파일에 넣지 않습니다.

확인:

```powershell
git config --local user.name
git config --local user.email
git remote -v
gh auth status
```

새 PC에서는 이 저장소의 로컬 설정이 따라오지 않습니다. 해당 계정으로 GitHub CLI 로그인을 하고 저장소별로 설정해야 합니다. 이 문서에는 토큰을 출력하는 명령을 직접 실행할 필요가 없습니다.
