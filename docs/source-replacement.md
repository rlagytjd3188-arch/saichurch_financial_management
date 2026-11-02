# 코드 교체 및 실행 안내

사용자 요청으로 기존 Next.js 웹앱 전체를 [typemild/church-steward](https://github.com/typemild/church-steward) 기반으로 교체했습니다.

- 원본 main 커밋: `9b5033655f227dc59d717f8c1cc17563c9b8cff6`
- 애플리케이션 버전: `1.2.0`
- 원본 MIT 라이선스·저작권 및 글꼴 라이선스 유지
- 원본 main과 v1.2.0 태그의 차이는 문서·테스트·릴리스 설정이며 애플리케이션 Java 소스는 같습니다.
- 이 저장소에서 추가한 사항: Windows 실행 도우미, 소스 빌드 도우미, 데이터/캐시 제외 규칙, Gradle 배포 체크섬, Gradle 라이선스 생성 태스크와 Spotless의 실행 순서 선언.

## Windows에서 실행

1. GitHub Desktop에서 현재 앱을 종료한 뒤 Fetch origin → Pull origin을 누릅니다.
2. Repository → Show in Explorer에서 **Start-Windows.cmd**를 더블클릭합니다.
3. 최초에는 Java가 포함된 원본 공식 v1.2.0 Windows ZIP(약 111MB)을 내려받아 검증한 후 `ChurchSteward/`에 풉니다. 다운로드 때는 인터넷이 필요합니다.
4. `ChurchSteward.exe`가 실행됩니다. 다음부터는 같은 cmd 또는 exe로 실행할 수 있으며 일상 업무에 인터넷이 필요하지 않습니다.
5. 최초 실행에서 관리자 비밀번호를 설정합니다. 원본 README의 비밀번호·백업 안내를 확인하세요.

Node.js와 별도 Java 설치가 필요하지 않습니다. 기본 도우미는 **원본의 공식 Windows 배포본**을 실행합니다. 이 저장소를 수정한 소스의 실행이 필요하면 아래 소스 빌드 절차를 사용하세요.

공식 ZIP URL:
`https://github.com/typemild/church-steward/releases/download/v1.2.0/ChurchSteward-1.2.0-windows.zip`

GitHub 공식 릴리스가 게시한 SHA-256:
`971fb5a9b69ac99b286f8443a15cf71b99f4b86c2845eac8acc11786db148503`

체크섬이 다르면 실행하지 않습니다. 기존 ChurchSteward 폴더에 실행 파일이 없으면 덮어쓰지 않고 중단합니다. 업데이트할 때는 원본 README와 docs/update.md의 백업·복원 절차를 따르세요.

## 소스에서 빌드·실행

JDK **25**가 필요합니다. JRE만으로는 소스를 빌드할 수 없습니다.

```text
Windows: Build-Windows.cmd
그 외: ./gradlew test bootJar spotlessCheck
```

Windows 도우미는 테스트·빌드 후 JAR를 `ChurchSteward-Source/church-steward.jar`에 복사해 실행합니다. 이 앱의 데이터는 해당 폴더의 data 및 application.yml에 생깁니다. 원본 공식 실행본의 데이터와는 분리됩니다.

Linux 등에서는 생성 JAR를 **build 폴더 밖의 별도 실행 폴더**로 복사한 뒤 `java -jar church-steward-1.2.0.jar`로 실행하세요. 데이터가 JAR 옆에 생기므로 build/libs에서 계속 운영하면 Gradle clean으로 지워질 수 있습니다. GUI 디스플레이가 필요합니다.

Windows 실행 폴더를 소스에서 직접 만들려면 Windows + JDK 25에서 `gradlew.bat packageWindows`를 실행합니다. jpackage는 다른 OS의 실행본을 교차 빌드하지 못하므로 Linux에서 Windows exe를 새로 생성했다고 주장하지 않습니다.

## 기존 웹앱과 데이터

기존 웹앱 소스는 Git의 `backup/webapp-before-steward-20261102` 브랜치에 보존했습니다. 작업환경에도 소스·Git bundle·로컬 데이터 백업을 체크아웃 바깥 `/workspace/backups/steward-replacement-20261102`에 보관했습니다.

현재 PC의 기존 `data` 폴더와 비밀설정은 Git 교체로 삭제하지 않습니다. 새 앱의 기본 실행 데이터는 **ChurchSteward/data**, 암호화 키 설정은 **ChurchSteward/application.yml**에 생성됩니다. 이 둘을 함께 보존해야 합니다. 이전 웹앱의 root data/finance.sqlite와는 별개입니다.

SQLite 재정자료·이전 웹앱의 자체 엑셀 가져오기·Supabase 외부 조회는 자동 승계하지 않습니다. 새 앱은 단식부기 데스크톱 앱이며 복식부기·재무상태표·웹/스마트폰 조회는 제공하지 않습니다. 이전 데이터를 자동 변환하거나 섞지 않았습니다. 교회 장부를 청지기의 예산/거래 가져오기 서식에 맞추는 작업은 별도입니다.

기존 data나 application.yml을 삭제·덮어쓰기 전에 백업하세요. 실제 재정자료·계정·비밀번호는 Git에 포함하지 않습니다.
