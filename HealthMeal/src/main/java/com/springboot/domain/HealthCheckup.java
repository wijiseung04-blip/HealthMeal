package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 사용자가 확인한 건강검진 결과 한 건. 수치는 CheckupValue에 암호화되어 저장된다 */
@Entity
@Table(name = "health_checkups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HealthCheckup {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	/** 수치를 뽑은 PDF (직접 입력이면 null, 파일을 지우면 null) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "checkup_file_id")
	private CheckupFile checkupFile;

	private LocalDate examDate;

	@Column(nullable = false, length = 1)
	private String sex;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	@OneToMany(mappedBy = "checkup", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<CheckupValue> values = new ArrayList<>();

	public HealthCheckup(Member member, CheckupFile checkupFile, LocalDate examDate, String sex) {
		this.member = member;
		this.checkupFile = checkupFile;
		this.examDate = examDate;
		this.sex = sex;
		this.createdAt = LocalDateTime.now();
	}

	public void addValue(String item, Double value, String source) {
		values.add(new CheckupValue(this, item, value, source));
	}
}
