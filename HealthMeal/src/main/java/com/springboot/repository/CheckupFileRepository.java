package com.springboot.repository;

import com.springboot.domain.CheckupFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CheckupFileRepository extends JpaRepository<CheckupFile, Long> {

	List<CheckupFile> findByMemberUsernameOrderByUploadedAtDesc(String username);

	/** 본인 파일만 찾는다. 다른 회원의 파일은 없는 것과 같다 */
	Optional<CheckupFile> findByIdAndMemberUsername(Long id, String username);
}
