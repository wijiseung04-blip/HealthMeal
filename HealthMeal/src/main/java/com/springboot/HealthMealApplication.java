package com.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 스프링 부트 웹 앱 시작점 (./gradlew bootRun). 데이터 적재는 별도 진입점 pipeline.ImportData */
@SpringBootApplication
public class HealthMealApplication {

	public static void main(String[] args) {
		SpringApplication.run(HealthMealApplication.class, args);
	}

}
