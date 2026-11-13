package com.springboot.exception;

public class DuplicateEmailException extends RuntimeException {

	public DuplicateEmailException(String email) {
		super("이미 가입된 이메일입니다: " + email);
	}
}
