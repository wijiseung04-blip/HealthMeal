package com.springboot.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StopWatch;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 12장 인터셉터로 모든 요청 URL 기록: 접근한 URL, 처리 시작·종료 시각, 소요 시간.
 * 콘솔과 logs/monitor.log 에 남긴다 (logging/log4j2.xml). 요청 본문(비밀번호·검진 수치)은 기록하지 않는다
 */
@Slf4j
public class MonitoringInterceptor implements HandlerInterceptor {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
	private final ThreadLocal<StopWatch> stopWatchLocal = new ThreadLocal<>();

	/** 컨트롤러를 호출하기 전 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		StopWatch stopWatch = new StopWatch(handler.toString());
		stopWatch.start(handler.toString());
		stopWatchLocal.set(stopWatch);
		log.info("접근한 URL 경로 : {} {}", request.getMethod(), getURLPath(request));
		log.info("요청 처리 시작 시각 : {}", getCurrentTime());
		return true;
	}

	/** 컨트롤러가 처리한 뒤 */
	@Override
	public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
		log.info("요청 처리 종료 시각 : {}", getCurrentTime());
	}

	/** 뷰까지 응답한 뒤 (예외가 나도 실행) */
	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
		StopWatch stopWatch = stopWatchLocal.get();
		if (stopWatch == null) return;
		stopWatch.stop();
		log.info("요청 처리 소요 시간 : {} ms (상태 {})", stopWatch.getTotalTimeMillis(), response.getStatus());
		stopWatchLocal.remove();
		log.info("--------------------------------------------");
	}

	/** 요청 URL과 쿼리문 */
	private String getURLPath(HttpServletRequest request) {
		String queryString = request.getQueryString();
		return request.getRequestURI() + (queryString == null ? "" : "?" + queryString);
	}

	private String getCurrentTime() {
		return LocalDateTime.now().format(FORMAT);
	}
}
