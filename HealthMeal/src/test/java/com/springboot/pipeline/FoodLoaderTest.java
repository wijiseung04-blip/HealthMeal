package com.springboot.pipeline;

import com.springboot.HealthMealApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 적재 프로그램과 같은 방식(웹 서버 없이)으로 스프링을 띄워 H2에 적재해 본다 */
class FoodLoaderTest {

	@Test
	void 식품과_영양소를_적재하고_다시_실행해도_중복되지_않는다(@TempDir Path dir) throws Exception {
		String xml = Files.readString(Path.of("src/test/resources/food-detail-sample.xml"));
		Files.writeString(dir.resolve("A001001A010a.txt"), xml);

		try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HealthMealApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.datasource.url=jdbc:h2:mem:loader;MODE=MySQL")
				.run()) {
			FoodLoader loader = ctx.getBean(FoodLoader.class);
			JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

			loader.load(dir, dir.resolve("없음.csv"));
			loader.load(dir, dir.resolve("없음.csv"));

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM foods", Integer.class)).isEqualTo(2); // 식품 + 물
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM food_nutrients", Integer.class)).isEqualTo(4);
			assertThat(jdbc.queryForObject("SELECT content FROM food_nutrients WHERE nutrient_code = '나트륨'", Double.class)).isEqualTo(1100.0);
			// 미측정은 0이 아니라 NULL
			assertThat(jdbc.queryForObject("SELECT content FROM food_nutrients WHERE nutrient_code = '비타민 D'", Double.class)).isNull();
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_versions", Integer.class)).isEqualTo(2);
		}
	}
}
