# HealthMeal

공공 식품 데이터로 계산하는 맞춤형 레시피 영양 조절 서비스 (Spring Boot + Thymeleaf + MySQL).

## 실행

1. MySQL에 DB 생성: `CREATE DATABASE healthmeal DEFAULT CHARACTER SET utf8mb4;`
2. 접속 정보는 환경변수로 지정 (기본값: `localhost:3306/healthmeal`, `root`, 비밀번호 없음)
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
3. `HealthMeal` 폴더에서 `./gradlew bootRun` → http://localhost:8080

테스트는 H2 인메모리 DB로 실행: `./gradlew test`

## 데이터 수집 (별도 프로그램)

공공 API 원본 응답을 `HealthMeal/data/raw/`에 그대로 저장한다. 키는 환경변수로만 받는다.

| 환경변수 | 발급처 | 비고 |
| --- | --- | --- |
| `FOODSAFETY_API_KEY` | 식품안전나라 (COOKRCP01) | |
| `DATA_GO_KR_API_KEY` | 공공데이터포털 (국가표준식품성분) | **Decoding 키** |

```bash
./gradlew importData --args="collect"          # 레시피 + 식품 전체
./gradlew importData --args="collect recipes"  # 레시피만
./gradlew importData --args="collect foods"    # 식품만
```

이미 받은 파일은 건너뛰므로 중간에 끊겨도 다시 실행하면 이어서 받는다.

## 구조

| 패키지 | 계층 |
| --- | --- |
| `controller` | MVC 컨트롤러, 전역 예외 처리 |
| `service` | 비즈니스 로직 |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 |
| `dto` | 폼 객체와 검증 |
| `config` | 보안 설정 |
