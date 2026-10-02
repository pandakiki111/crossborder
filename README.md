# crossborder

## 로컬 실행

필요한 것: JDK 21, Docker

```bash
docker compose up -d          # MariaDB, Redis
./gradlew :oms:bootRun        # http://localhost:8082
```

`bootRun`은 `local` 프로필로 실행되며, 로컬 DB 비밀번호와 JWT 서명 키는 각 모듈의 `application-local.yml`에 들어 있다.
이 값들은 로컬 전용 더미 값이라 별도로 전달받거나 환경변수를 설정할 필요가 없다.

## 시크릿 관리

실제 시크릿은 저장소에 커밋하지 않는다. 기본 프로필(`application.yml`)은 환경변수만 참조하고 기본값을 두지 않으므로,
값이 주입되지 않으면 애플리케이션이 기동 단계에서 실패한다.

| 환경변수 | 사용 모듈 | 설명 |
|---|---|---|
| `JWT_SECRET` | auth, oms, cbt | HS256 서명 키 (Base64, 디코딩 후 256bit 이상). 세 모듈이 같은 값을 써야 한다 |
| `DB_PASSWORD` | oms | DB 비밀번호 |

키 생성 예: `openssl rand -base64 32`
