package com.springboot.exception;

/** 허용하지 않는 검진 파일(형식·크기·빈 파일)을 올렸을 때. 메시지는 화면에 그대로 보여 준다 */
public class InvalidUploadException extends RuntimeException {

	public InvalidUploadException(String message) {
		super(message);
	}
}
