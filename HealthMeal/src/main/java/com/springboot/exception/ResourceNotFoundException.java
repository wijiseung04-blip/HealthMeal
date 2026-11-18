package com.springboot.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 11장 @ResponseStatus: 본인 것이 아니거나 없는 파일·검진 결과·후보 등은 404 (다른 회원 데이터의 존재를 드러내지 않는다) */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}
}
