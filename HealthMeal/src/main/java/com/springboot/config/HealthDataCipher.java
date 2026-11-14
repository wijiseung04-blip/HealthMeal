package com.springboot.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 건강검진 수치(민감정보) 컬럼 암호화. AES-256-GCM, 값마다 임의 IV.
 * 키는 환경변수 HEALTH_DATA_KEY(32바이트 Base64)로 받는다.
 */
@Component
public class HealthDataCipher {

	private static final String PREFIX = "v1:";
	private static volatile HealthDataCipher current;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public HealthDataCipher(@Value("${app.health-data-key}") String base64Key) {
		byte[] raw = Base64.getDecoder().decode(base64Key);
		if (raw.length != 32) throw new IllegalStateException("HEALTH_DATA_KEY는 32바이트(Base64)여야 합니다.");
		this.key = new SecretKeySpec(raw, "AES");
		current = this;
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[12];
			random.nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + enc.length).put(iv).put(enc).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("암호화 실패", e);
		}
	}

	public String decrypt(String stored) {
		try {
			byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
			return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("복호화 실패 (키가 바뀌었을 수 있음)", e);
		}
	}

	/** JPA 컬럼 변환기: Hibernate가 만들므로 스프링이 만든 암호기를 정적으로 참조한다 */
	@Converter
	public static class EncryptedDouble implements AttributeConverter<Double, String> {

		@Override
		public String convertToDatabaseColumn(Double value) {
			return value == null ? null : current.encrypt(Double.toString(value));
		}

		@Override
		public Double convertToEntityAttribute(String stored) {
			return stored == null ? null : Double.valueOf(current.decrypt(stored));
		}
	}
}
