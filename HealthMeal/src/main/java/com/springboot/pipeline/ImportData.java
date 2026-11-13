package com.springboot.pipeline;

import com.springboot.HealthMealApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

/**
 * 데이터 구성 프로그램 진입점. 웹 서버와 별도로 실행한다.
 * 사용: ./gradlew importData --args="collect" | --args="stats" | --args="match" | --args="goldset" | --args="load-foods"
 */
public class ImportData {

	public static void main(String[] args) throws Exception {
		String step = args.length > 0 ? args[0] : "";
		switch (step) {
			case "collect" -> new Collector(Arrays.copyOfRange(args, 1, args.length)).run();
			case "stats" -> IngredientStats.run();
			case "match" -> IngredientMatching.run();
			case "goldset" -> GoldSetAccuracy.report();
			case "load-foods" -> {
				// DB 접속이 필요한 단계만 스프링을 띄운다 (웹 서버는 띄우지 않음)
				try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HealthMealApplication.class)
						.web(WebApplicationType.NONE).run()) {
					ctx.getBean(FoodLoader.class).load(Collector.RAW.resolve("food-detail"));
				}
			}
			default -> {
				System.err.println("사용법: ./gradlew importData --args=\"collect [recipes|foods] | stats | match | goldset | load-foods\"");
				System.exit(1);
			}
		}
	}
}
