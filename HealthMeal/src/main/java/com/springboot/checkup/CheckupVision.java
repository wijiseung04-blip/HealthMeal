package com.springboot.checkup;

import java.io.IOException;
import java.util.Map;

/**
 * 검진 결과표 사진(또는 글자 없는 스캔 PDF)을 AI로 읽어 검사 수치 후보를 만든다.
 * 회원이 화면에서 동의하고 요청할 때만 쓰며, 결과는 규칙 추출과 같이 후보일 뿐이다 (사용자가 확인한 값만 저장).
 */
public interface CheckupVision {

	/** 키가 설정돼 있어 쓸 수 있는지 */
	boolean available();

	/** mimeType: image/jpeg, image/png, image/webp, application/pdf */
	CheckupExtractor.Result read(byte[] data, String mimeType) throws IOException, InterruptedException;

	/** AI에게 줄 항목 목록: 이름 → "라벨 (단위)" */
	static Map<String, String> itemGuide() {
		Map<String, String> m = new java.util.LinkedHashMap<>();
		for (TestItem item : TestItem.values()) m.put(item.name(), item.getLabel() + " (" + item.getUnit() + ")");
		return m;
	}
}
