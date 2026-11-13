# HealthMeal

공공 식품 데이터로 계산하는 맞춤형 레시피 영양 조절 서비스 (Spring Boot + Thymeleaf + MySQL).

## 실행

1. MySQL에 DB 생성: `CREATE DATABASE healthmeal DEFAULT CHARACTER SET utf8mb4;`
2. 접속 정보는 환경변수로 지정 (기본값: `localhost:3306/healthmeal`, `root`, 비밀번호 없음)
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
3. `HealthMeal` 폴더에서 `./gradlew bootRun` → http://localhost:8080

테스트는 H2 인메모리 DB로 실행: `./gradlew test`

## 구조

| 패키지 | 계층 |
| --- | --- |
| `controller` | MVC 컨트롤러, 전역 예외 처리 |
| `service` | 비즈니스 로직 |
| `repository` | Spring Data JPA |
| `domain` | 엔티티 |
| `dto` | 폼 객체와 검증 |
| `config` | 보안 설정 |
