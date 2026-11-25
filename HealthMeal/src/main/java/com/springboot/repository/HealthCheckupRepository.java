package com.springboot.repository;

import com.springboot.domain.HealthCheckup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 검진 결과 저장소: 회원별 목록·최근 기록, 본인 결과만 찾기 */
public interface HealthCheckupRepository extends JpaRepository<HealthCheckup, Long> {

	List<HealthCheckup> findByMemberUsernameOrderByCreatedAtDesc(String username);

	Optional<HealthCheckup> findByIdAndMemberUsername(Long id, String username);

	/** PDF를 지워도 확인한 수치는 남긴다 */
	@Modifying
	@Query("update HealthCheckup h set h.checkupFile = null where h.checkupFile.id = :fileId")
	void detachFile(@Param("fileId") Long fileId);
}
