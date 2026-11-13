package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 외부 데이터 적재 이력. 계산 결과를 재현할 때 어느 데이터였는지 확인한다 */
@Entity
@Table(name = "data_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataVersion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String source;

	@Column(nullable = false, length = 50)
	private String version;

	private int recordCount;

	@Column(nullable = false)
	private LocalDateTime loadedAt;
}
