# 코드 교체 검증 기록

2026-11-02, Linux / Temurin JDK 25.0.5+8 / Gradle 9.5.1에서 확인했습니다.

- 사용자 지정 원본: typemild/church-steward main `9b5033655f227dc59d717f8c1cc17563c9b8cff6`
- 기존 웹앱 코드·Git 이력·로컬 데이터 백업 보존. Git bundle 및 SQLite 백업 무결성 확인.
- 원본 애플리케이션 Java 소스·테스트·리소스·MIT 및 글꼴 라이선스 전체 반영.
- 공식 JDK 및 Gradle 배포 체크섬 검증. TLS 검증 유지.
- `./gradlew test bootJar spotlessCheck` 성공. 355개 테스트 결과 XML 기준: 총 1,227개 중 **1,226개 통과, 1개 건너뜀, 실패·오류 0**. 건너뛴 항목은 고정 덤프 생성 옵션이 꺼진 테스트용 생성기입니다.
- 원본의 라이선스 리소스 생성과 Spotless 작업 사이에 누락된 순서를 선언해 Gradle 검증 오류 수정.
- `build/libs/church-steward-1.2.0.jar` 생성.
- 별도 임시 폴더와 Xvfb에서 JAR 실제 실행: 첫 실행 안내와 관리자 계정 설정 화면 표시 확인. 실제 재정자료·계정 생성 없이 확인.
- 공식 Windows v1.2.0 ZIP: GitHub가 게시한 SHA-256 일치, ZIP CRC 검증, PE 실행 파일과 내장 JVM 존재 확인.

Windows에서 exe와 cmd 자체를 실행한 검증이나 Windows용 커스텀 패키지 교차 빌드는 하지 않았습니다. Start-Windows.cmd는 검증된 원본 공식 Windows 배포본을 내려받습니다. 이 저장소의 소스 빌드는 JDK 25에서 Build-Windows.cmd로 수행합니다.

기존 SQLite 데이터의 H2 변환, 이전 웹앱의 교회별 가져오기·복식부기·외부 조회 기능은 새 앱으로 이식하지 않았습니다. 이는 사용자 요청에 따른 전체 코드 교체이며 데이터 형식을 혼합하지 않습니다.
