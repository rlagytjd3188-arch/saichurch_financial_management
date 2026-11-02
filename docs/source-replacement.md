# 코드 교체 이력과 기존 데이터

사용자 요청으로 Next.js 웹앱을 [typemild/church-steward](https://github.com/typemild/church-steward) 기반 Java 데스크톱 앱으로 교체했습니다. 원본 커밋은 `9b5033655f227dc59d717f8c1cc17563c9b8cff6`, 당시 버전은 1.2.0입니다. 이후 사이교회 재무관리 1.3.0으로 확장했습니다.

**현재 설치·실행 방법은 [README](../README.md), 변경·마이그레이션 상세는 [1.3.0 보고서](saichurch-1.3.md)를 따르세요.** 예전 공식 1.2.0 실행본을 다운로드하는 방식은 현재 수정본을 실행하지 못하므로 사용하지 않습니다.

## 보존한 자료

- 기존 웹앱 소스: GitHub `backup/webapp-before-steward-20261102` 브랜치
- 클라우드 작업환경의 Git bundle·소스·SQLite 백업: `/workspace/backups/steward-replacement-20261102`
- 기존 root `data/finance.sqlite`: 삭제하거나 H2로 덮어쓰지 않음
- 원본 MIT 저작권과 글꼴 라이선스: 유지

예전 웹앱의 SQLite, 사용자 전용 엑셀 가져오기, Supabase 구성은 Java 앱으로 자동 이전되지 않았습니다. 파일의 의미·계정 매핑을 추정해 데이터를 합치지 않았습니다. 특히 인수인계 이전 참고용 시트는 임의로 현재 회계에 포함하지 않습니다.

현재 앱의 운영 자료는 실행 폴더의 `data/`와 `application.yml`입니다. H2 백업 파일 `.csbackup`은 앱의 백업·복원 기능으로 처리합니다. SQLite를 H2 데이터 폴더에 복사해도 변환되지 않습니다.

새 버전은 기존 H2 원본 거래를 유지하고 V4 migration으로 복식부기 테이블을 추가합니다. 불확실한 이전 거래는 REVIEW로 표시합니다. 실제 운영 DB를 개발 테스트에 사용하지 않았습니다.
