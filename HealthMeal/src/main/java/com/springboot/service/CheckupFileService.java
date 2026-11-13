package com.springboot.service;

import com.springboot.domain.CheckupFile;
import com.springboot.domain.Member;
import com.springboot.exception.InvalidUploadException;
import com.springboot.repository.CheckupFileRepository;
import com.springboot.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 건강검진 PDF 업로드·다운로드·삭제. 회원 본인 파일만 다룬다 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CheckupFileService {

	static final long MAX_SIZE = 10 * 1024 * 1024;
	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes();

	private final CheckupFileRepository fileRepository;
	private final MemberRepository memberRepository;
	private final Path uploadDir;

	public CheckupFileService(CheckupFileRepository fileRepository, MemberRepository memberRepository,
							  @Value("${app.upload-dir}") String uploadDir) {
		this.fileRepository = fileRepository;
		this.memberRepository = memberRepository;
		this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	public record Download(Resource resource, String originalName, long size) {
	}

	public List<CheckupFile> list(String email) {
		return fileRepository.findByMemberEmailOrderByUploadedAtDesc(email);
	}

	@Transactional
	public CheckupFile upload(String email, MultipartFile file) throws IOException {
		if (file == null || file.isEmpty()) throw new InvalidUploadException("파일을 선택하세요.");
		if (file.getSize() > MAX_SIZE) throw new InvalidUploadException("파일은 10MB 이하만 올릴 수 있습니다.");
		String original = cleanName(file.getOriginalFilename());
		if (!original.toLowerCase().endsWith(".pdf")) throw new InvalidUploadException("PDF 파일만 올릴 수 있습니다.");
		// 확장자만 바꾼 파일을 막기 위해 내용의 PDF 서명(%PDF-)을 확인한다
		try (InputStream in = file.getInputStream()) {
			if (!Arrays.equals(in.readNBytes(PDF_SIGNATURE.length), PDF_SIGNATURE)) {
				throw new InvalidUploadException("PDF 형식이 아닌 파일입니다.");
			}
		}
		Member member = memberRepository.findByEmail(email).orElseThrow();
		Files.createDirectories(uploadDir);
		String stored = UUID.randomUUID() + ".pdf"; // 원래 이름은 경로에 쓰지 않는다
		try (InputStream in = file.getInputStream()) {
			Files.copy(in, uploadDir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
		}
		CheckupFile saved = fileRepository.save(new CheckupFile(member, original, stored, file.getSize()));
		log.info("검진 파일 업로드: memberId={}, fileId={}, size={}", member.getId(), saved.getId(), saved.getSize());
		return saved;
	}

	public Optional<Download> download(String email, Long id) {
		return fileRepository.findByIdAndMemberEmail(id, email)
				.map(f -> new Download(new FileSystemResource(uploadDir.resolve(f.getStoredName())), f.getOriginalName(), f.getSize()))
				.filter(d -> d.resource().exists());
	}

	@Transactional
	public boolean delete(String email, Long id) throws IOException {
		Optional<CheckupFile> found = fileRepository.findByIdAndMemberEmail(id, email);
		if (found.isEmpty()) return false;
		Files.deleteIfExists(uploadDir.resolve(found.get().getStoredName()));
		fileRepository.delete(found.get());
		log.info("검진 파일 삭제: fileId={}", id);
		return true;
	}

	/** 경로 문자를 지우고 길이를 제한한다 (표시·다운로드 이름으로만 쓴다) */
	static String cleanName(String name) {
		String n = name == null ? "" : name.replace('\\', '/');
		n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
		if (n.isEmpty()) n = "checkup.pdf";
		return n.length() > 200 ? n.substring(n.length() - 200) : n;
	}
}
