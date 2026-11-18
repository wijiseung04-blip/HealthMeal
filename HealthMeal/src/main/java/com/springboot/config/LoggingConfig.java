package com.springboot.config;

import com.springboot.interceptor.MonitoringInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 12장 인터셉터 등록: 정적 파일(css·js)을 뺀 모든 요청을 기록한다 */
@Configuration
public class LoggingConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new MonitoringInterceptor()).excludePathPatterns("/css/**", "/js/**", "/favicon.ico", "/error");
	}
}
