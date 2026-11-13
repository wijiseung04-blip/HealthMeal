package com.springboot.pipeline;

import java.util.Arrays;

/**
 * 데이터 구성 프로그램 진입점. 웹 서버와 별도로 실행한다.
 * 사용: ./gradlew importData --args="collect"
 */
public class ImportData {

	public static void main(String[] args) throws Exception {
		String step = args.length > 0 ? args[0] : "";
		switch (step) {
			case "collect" -> new Collector(Arrays.copyOfRange(args, 1, args.length)).run();
			default -> {
				System.err.println("사용법: ./gradlew importData --args=\"collect [recipes|foods]\"");
				System.exit(1);
			}
		}
	}
}
