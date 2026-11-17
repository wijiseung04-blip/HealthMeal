package com.springboot.checkup;

import java.io.IOException;

/** AI 판독 실패 이유 (화면 안내용). retryAfterSeconds: 다시 시도해도 되는 시각까지 남은 초 (모르면 0) */
public class VisionException extends IOException {

	public enum Reason { RATE_LIMIT, BUSY, TIMEOUT, KEY, OTHER }

	private final Reason reason;
	private final long retryAfterSeconds;

	public VisionException(Reason reason, long retryAfterSeconds) {
		super("AI 판독 실패: " + reason);
		this.reason = reason;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public Reason reason() {
		return reason;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

	/** 회원에게 보여 줄 안내 */
	public String userMessage() {
		return switch (reason) {
			case RATE_LIMIT -> "Gemini 무료 사용 한도(분당 요청 수)를 넘었습니다. "
					+ (retryAfterSeconds > 0 ? retryAfterSeconds + "초" : "1분") + " 뒤 한 번만 다시 눌러 주세요.";
			case BUSY -> "Gemini 서버가 혼잡합니다. 잠시 뒤 다시 시도하세요.";
			case TIMEOUT -> "Gemini 응답이 5분 안에 오지 않았습니다. Gemini가 혼잡한 것 같습니다. 잠시 뒤 다시 시도하거나 결과표를 보면서 직접 입력하세요.";
			case KEY -> "Gemini 요청이 거부되었습니다(API 키 또는 모델 설정 확인).";
			case OTHER -> "AI 판독에 실패했습니다. 잠시 뒤 다시 시도하거나 직접 입력하세요.";
		};
	}
}
