package com.springboot.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gemini API(generateContent) 호출. 키는 환경변수 GEMINI_API_KEY로만 받는다.
 * 공개 데이터(식품명·재료명·레시피 문장)만 보낸다. 회원·검진 데이터는 이 클래스로 보내지 않는다.
 */
final class GeminiClient implements AiReview.Ai {

	private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
	static final String DEFAULT_MODEL = "gemini-4-hydrogen";

	private final String key;
	private final String model;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final JsonMapper json = JsonMapper.builder().build();

	GeminiClient(String key, String model) {
		this.key = key;
		this.model = model;
	}

	static GeminiClient fromEnv() {
		String key = System.getenv("GEMINI_API_KEY");
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("환경변수 GEMINI_API_KEY가 없습니다. 이클립스 Run Configurations → Environment에 넣으세요.");
		}
		String model = System.getenv("GEMINI_MODEL");
		return new GeminiClient(key.trim(), model == null || model.isBlank() ? DEFAULT_MODEL : model.trim());
	}

	String model() {
		return model;
	}

	/** JSON으로만 답하게 하고 답 본문을 돌려준다. 무료 등급 한도(429)에 걸리면 기다렸다가 다시 시도한다 */
	@Override
	public String ask(String prompt) throws IOException, InterruptedException {
		String body = json.writeValueAsString(Map.of(
				"contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
				"generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0)));
		HttpRequest req = HttpRequest.newBuilder(URI.create(URL.formatted(model)))
				.timeout(Duration.ofSeconds(180))
				.header("Content-Type", "application/json")
				.header("x-goog-api-key", key)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		for (int attempt = 1; ; attempt++) {
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (res.statusCode() == 200) {
				JsonNode text = json.readTree(res.body()).path("candidates").path(0).path("content").path("parts").path(0).path("text");
				if (text.isMissingNode()) throw new IOException("Gemini 응답에 본문이 없습니다: " + cut(res.body()));
				return text.asString();
			}
			if ((res.statusCode() == 429 || res.statusCode() == 503) && attempt < 6) {
				System.out.println("  Gemini " + res.statusCode() + " (한도·혼잡) → " + 20 * attempt + "초 뒤 다시 시도");
				Thread.sleep(20_000L * attempt);
				continue;
			}
			String hint = res.statusCode() == 404 ? " (모델 이름 확인: 환경변수 GEMINI_MODEL, 현재 " + model + ")"
					: res.statusCode() == 400 || res.statusCode() == 403 ? " (API 키 확인)" : "";
			throw new IOException("Gemini 오류 " + res.statusCode() + hint + ": " + cut(res.body()));
		}
	}

	private static String cut(String s) {
		return s.length() > 500 ? s.substring(0, 500) + "…" : s;
	}
}
