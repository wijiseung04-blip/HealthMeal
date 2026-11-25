package com.springboot.exception;

/** 이미 가입된 아이디로 가입하려 할 때 (서비스 단의 마지막 확인, 화면 검증은 @UniqueUsername) */
public class DuplicateUsernameException extends RuntimeException {

	public DuplicateUsernameException(String username) {
		super("이미 사용 중인 아이디입니다: " + username);
	}
}
