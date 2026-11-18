package com.springboot.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 9장 사용자 정의 제약사항 애너테이션: 이미 가입된 아이디면 오류 (검사는 UniqueUsernameValidator) */
@Constraint(validatedBy = UniqueUsernameValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueUsername {

	String message() default "{signup.username.duplicate}";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
