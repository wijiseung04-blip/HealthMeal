package com.springboot.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String username;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false, length = 50)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Member(String username, String encodedPassword, String name, Role role) {
		this.username = username;
		this.password = encodedPassword;
		this.name = name;
		this.role = role;
		this.createdAt = LocalDateTime.now();
	}
}
