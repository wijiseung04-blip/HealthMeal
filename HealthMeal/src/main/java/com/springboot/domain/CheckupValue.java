package com.springboot.domain;

import com.springboot.config.HealthDataCipher;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 검사 항목 하나의 값. value는 DB에 암호문으로 저장된다 */
@Entity
@Table(name = "checkup_values")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckupValue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "checkup_id")
	private HealthCheckup checkup;

	/** TestItem 이름 (SYSTOLIC_BP 등) */
	@Column(nullable = false, length = 30)
	private String item;

	@Convert(converter = HealthDataCipher.EncryptedDouble.class)
	@Column(name = "value_encrypted", nullable = false, length = 120)
	private Double value;

	/** TEXT_EXTRACTED(추출 그대로) / USER_EDITED(추출값 수정) / USER_ENTERED(직접 입력) */
	@Column(nullable = false, length = 20)
	private String source;

	CheckupValue(HealthCheckup checkup, String item, Double value, String source) {
		this.checkup = checkup;
		this.item = item;
		this.value = value;
		this.source = source;
	}
}
