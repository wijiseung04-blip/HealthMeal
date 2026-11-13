package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 회원이 올린 건강검진 결과 PDF. 파일은 공개 폴더 밖에 임의 이름으로 저장하고 본인만 내려받는다 */
@Entity
@Table(name = "checkup_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckupFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false, length = 255)
	private String originalName;

	@Column(nullable = false, unique = true, length = 100)
	private String storedName;

	private long size;

	@Column(nullable = false)
	private LocalDateTime uploadedAt;

	public CheckupFile(Member member, String originalName, String storedName, long size) {
		this.member = member;
		this.originalName = originalName;
		this.storedName = storedName;
		this.size = size;
		this.uploadedAt = LocalDateTime.now();
	}
}
