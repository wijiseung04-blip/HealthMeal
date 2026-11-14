package com.springboot.checkup;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 웹과 별도로 실행하는 검진 결과 추출 프로그램.
 * 사용: ./gradlew extractCheckup --args="검진결과.pdf"
 */
public class CheckupExtractorCli {

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("사용법: ./gradlew extractCheckup --args=\"검진결과.pdf\"");
			System.exit(1);
		}
		CheckupExtractor.Result r = CheckupExtractor.extract(Files.readAllBytes(Path.of(args[0])));
		if (!r.hasText()) {
			System.out.println("PDF에 글자가 없습니다 (스캔 이미지일 수 있음). 수치를 직접 입력하세요.");
			return;
		}
		System.out.println("검진일: " + (r.examDate() == null ? "(찾지 못함)" : r.examDate()));
		System.out.printf("%-16s %10s  %s%n", "항목", "값", "원문 줄");
		for (TestItem item : TestItem.values()) {
			CheckupExtractor.Extracted e = r.values().get(item);
			System.out.printf("%-16s %10s  %s%n", item.getLabel(),
					e == null ? "-" : e.value() + " " + item.getUnit(), e == null ? "(찾지 못함)" : e.sourceLine());
		}
		System.out.println(r.values().size() + "/" + TestItem.values().length + "개 항목 추출");
	}
}
