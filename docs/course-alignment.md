# 수업 자료(3~14장)와 HealthMeal 코드 대응

수업 자료 [repository2](https://github.com/wijiseung04-blip/repository2)의 장별 내용에 맞춰 HealthMeal 코드를 고친 내역과, 고치지 않은(못한) 부분을 정리한다.
경로는 `HealthMeal/` 아래 기준.

## 장별 대응표

| 장 | 수업 내용 | HealthMeal 적용 위치 | 이번에 바꾼 것 |
| --- | --- | --- | --- |
| 3 | 계층 구조 (domain · repository · service · controller), 롬복 | `domain/`, `repository/`, `service/`, `controller/` | 없음 (이미 계층 구조) — 인터페이스+Impl 분리는 아래 "적용하지 않은 것" |
| 4 | `@Controller`, `@RestController`, `@RequestMapping`·`@GetMapping`·`@PostMapping` | `controller/*Controller.java`, `RecipeApiController`, `FavoriteApiController` | REST 컨트롤러 `FavoriteApiController` 추가 |
| 5 | `@RequestParam`, `@PathVariable` | 모든 컨트롤러 (이클립스 빌드를 위해 이름 명시) | 없음 |
| 6 | 폼 태그 `th:object`·`th:field`, `@ModelAttribute`, `@InitBinder` | `signup.html`, `goals/profile.html`, `myrecipes/form.html` | **`@InitBinder` + `setAllowedFields`** 추가: `MemberController`, `GoalController`, `MyRecipeController` (폼에 없는 파라미터는 바인딩하지 않음) |
| 7 | 파일 업로드 `MultipartFile`, `spring.servlet.multipart.*` | `MyPageController`, `CheckupFileService`, `application.properties` | 없음 (검진 PDF·사진 업로드가 이미 이 방식) |
| 8 | 다국어: `messages_ko`·`messages_en`, `spring.messages.*`, `SessionLocaleResolver` + `LocaleChangeInterceptor`(lang) | `resources/messages/`, `config/MessageConfig.java` | **새로 추가**. 헤더에 한국어/English, `?lang=en` |
| 9 | Bean Validation + 메시지 리소스, 사용자 정의 애너테이션(`ConstraintValidator`), `Validator` 인터페이스, 연동 설정 | `dto/*Form.java`, `validator/`, `config/ValidationConfig.java` | **오류 메시지를 메시지 리소스로**(`{키}`), **`@UniqueUsername`**(중복 아이디), **`PasswordConfirmValidator`**(비밀번호 확인, `@InitBinder`로 등록) |
| 10 | 스프링 시큐리티: `SecurityFilterChain`, `PasswordEncoder`, `UserDetailsService`, 폼 로그인·로그아웃, `sec:authorize`·`sec:authentication` | `config/SecurityConfig.java`, `config/PasswordConfig.java`, `service/MemberUserDetailsService.java`, `login.html` | **`thymeleaf-extras-springsecurity6`** 추가, 헤더·레시피 상세에서 `sec:authorize`·`sec:authentication`, 로그아웃 후 `/login?logout` 안내 |
| 11 | `@ResponseStatus` 예외, 컨트롤러 `@ExceptionHandler`(ModelAndView), `@ControllerAdvice` 전역 처리 | `exception/` | **`RecipeNotFoundException`·`ResourceNotFoundException`**(`@ResponseStatus(404)`), `RecipeController.handleError`(→ `error/recipe.html`), 전역 처리를 **`exception/CommonException`**(→ `error/common.html`)으로 이동 |
| 12 | Log4j2 (`spring-boot-starter-log4j2`, 로그백 제외, `logging/log4j2.xml`), 인터셉터 `MonitoringInterceptor` + `LoggingConfig` | `build.gradle`, `resources/logging/log4j2.xml`, `interceptor/`, `config/LoggingConfig.java` | **새로 추가**. 콘솔 + `logs/monitor.log`(하루 단위, 30일 보관) |
| 13 | RESTful: `@RestController`, `@RequestBody`/`@ResponseBody`, GET·PUT·DELETE, `HiddenHttpMethodFilter`, 자바스크립트로 호출 | `controller/FavoriteApiController.java`, `static/js/favorite.js` | **즐겨찾기 REST API** (`GET/PUT/DELETE /api/favorites`), 별 버튼이 fetch로 호출, 마이페이지 즐겨찾기 빼기는 `th:method="delete"` |
| 14 | MySQL, `JdbcTemplate`, JPA(`JpaRepository`, `@Query`), `ddl-auto` | `repository/`, 각 서비스의 `JdbcTemplate`, `application.properties` | 없음 (이미 둘 다 사용) |

## 파일 위치를 바꾼 것

| 전 | 후 | 이유 |
| --- | --- | --- |
| `controller/GlobalControllerAdvice.java` | `exception/CommonException.java` (예외 처리) + `controller/GlobalModelAttributes.java` (헤더용 로그인 아이디) | 11장처럼 전역 예외 처리는 exception 패키지에 |
| `templates/error/general.html` | `templates/error/common.html` | 11장 `errorCommon` 대응 |
| (없음) | `interceptor/`, `validator/`, `resources/messages/`, `resources/logging/`, `static/js/` | 12·9·8·12·13장 실습과 같은 위치 |

## 적용하지 않은 것 (수정 불가 또는 하지 않는 편이 나은 것)

| 수업 내용 | 이유 |
| --- | --- |
| 3장 서비스·저장소를 **인터페이스 + Impl**로 나누기 (`BookService`/`BookServiceImpl`) | 서비스 15개가 결과 레코드(`SubstitutionService.Option` 등)와 정적 계산 함수(`SubstitutionService.verdict`, `MealPlanService.words` 등)를 함께 갖고 있고 테스트·다른 계층이 이를 직접 쓴다. 나누면 거의 모든 파일이 바뀌는데 동작상 이득이 없다. 저장소는 Spring Data JPA 인터페이스(`JpaRepository`)를 써서 이미 인터페이스 방식이다. 필요하면 별도 작업으로 진행 가능 |
| 3장 `server.servlet.context-path=/BookMarket` 같은 실행 경로 | 모든 링크·테스트·README 주소가 `/` 기준이라 바꾸지 않음 |
| 5장 `@MatrixVariable` | 쓸 만한 화면이 없다(검색 조건은 쿼리 파라미터가 표준). 억지로 넣으면 `UrlPathHelper` 설정만 늘어남 |
| 7장 업로드 폴더를 `ResourceHandlerRegistry`로 **정적 리소스로 공개** | 업로드 파일이 건강검진 결과(민감정보)라 공개 URL로 열면 안 된다. 대신 본인 확인 후 내려받는 컨트롤러(`/mypage/checkups/{id}/file`)를 쓴다 |
| 8장 **모든 화면** 번역 | 헤더·홈·로그인·회원가입·오류 화면·즐겨찾기 안내·입력 오류 메시지만 영어를 지원한다. 레시피 이름·재료·영양소 이름·판정 문구는 공공 데이터(한국어)와 계산 결과라 번역할 원문이 없고, 나머지 화면 문구(마이페이지·식사 조합·검진·목표 등 약 490곳)는 메시지 키로 옮기는 작업이 커서 이번 범위에서 뺐다 |
| 10장 `InMemoryUserDetailsManager`(Admin/Admin1234 고정 계정) | 회원가입한 사용자를 DB에서 읽는 `UserDetailsService`(`MemberUserDetailsService`)를 이미 쓴다. 고정 관리자 계정은 보안상 두지 않음 |
| 10장 별도 `LoginController`·`/loginfailed` | 로그인 화면은 `MemberController`의 `/login`, 실패는 `/login?error`로 같은 역할을 한다 |
| 12장 로그 파일 위치 `C:\Logs\monitor.log` | 운영체제마다 다르므로 실행 폴더의 `logs/monitor.log`로 (`.gitignore`). 요청 본문(비밀번호·검진 수치)은 기록하지 않는다 |
| 13장 `@RequestBody`로 JSON 받기 | 즐겨찾기 API는 주소의 레시피 번호만 있으면 되어 요청 본문이 없다. 다른 폼은 서버 화면(Thymeleaf) 방식 유지 |
| 14장 `BeanPropertyRowMapper` | 조회 결과를 자바 record로 받기 위해 `RowMapper` 람다를 쓴다 (수업의 대안 방식) |
| Java 버전 | 프로젝트는 JDK 26 toolchain. 코드 자체는 Java 21에서도 빌드·테스트됨 |

## 확인

- 전체 테스트 148개 통과 (이번에 추가: 다국어 오류 화면, 중복 아이디·비밀번호 확인 메시지의 한/영 전환, `@InitBinder`, 즐겨찾기 REST API의 PUT·DELETE·CSRF, 마이페이지 DELETE 폼, Log4j2 파일 기록)
