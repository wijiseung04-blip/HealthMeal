package com.springboot.exception;

public class InvalidUploadException extends RuntimeException {

	public InvalidUploadException(String message) {
		super(message);
	}
}
