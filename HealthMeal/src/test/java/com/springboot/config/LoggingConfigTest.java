package com.springboot.config;

import com.springboot.interceptor.MonitoringInterceptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 12장: logging/log4j2.xml 설정으로 MonitoringInterceptor가 요청 URL·소요 시간을 logs/monitor.log 에 남기는지 */
class LoggingConfigTest {

	@Test
	void 요청_URL과_소요_시간을_파일에_기록한다() throws Exception {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		var previous = context.getConfigLocation();
		Configurator.reconfigure(getClass().getResource("/logging/log4j2.xml").toURI());
		try {
			var request = new MockHttpServletRequest("GET", "/recipes");
			request.setQueryString("q=logtest" + System.nanoTime());
			var response = new MockHttpServletResponse();
			MonitoringInterceptor interceptor = new MonitoringInterceptor();
			interceptor.preHandle(request, response, "handler");
			interceptor.postHandle(request, response, "handler", null);
			interceptor.afterCompletion(request, response, "handler", null);

			String text = Files.readString(Path.of("logs", "monitor.log"));
			assertThat(text).contains("접근한 URL 경로 : GET /recipes?" + request.getQueryString()).contains("요청 처리 소요 시간");
		} finally {
			if (previous != null) Configurator.reconfigure(previous);
		}
	}
}
