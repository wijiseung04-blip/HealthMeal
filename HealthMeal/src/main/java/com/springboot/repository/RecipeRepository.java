package com.springboot.repository;

import com.springboot.domain.Recipe;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

	/** 공공 레시피의 이름 검색과 음식 종류 필터(개인 레시피는 제외). 값이 null이면 그 조건은 쓰지 않는다 */
	@Query("""
			select r from Recipe r
			where r.owner is null
			  and (:q is null or r.name like concat('%', :q, '%'))
			  and (:category is null or r.category = :category)""")
	Page<Recipe> search(@Param("q") String q, @Param("category") String category, Pageable pageable);

	/** 원본 번호 → 지금 id (다시 적재하면 id가 바뀐다) */
	@Query("select r.seq, r.id from Recipe r where r.seq in :seqs")
	List<Object[]> findIdsBySeqs(@Param("seqs") java.util.Collection<String> seqs);

	@Query("select distinct r.category from Recipe r where r.owner is null and r.category is not null order by r.category")
	List<String> findCategories();
}
