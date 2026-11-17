package com.springboot.service;

import com.springboot.domain.CheckupFile;
import com.springboot.domain.Member;
import com.springboot.exception.InvalidUploadException;
import com.springboot.repository.CheckupFileRepository;
import com.springboot.repository.HealthCheckupRepository;
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

/** 건강검진 결과 파일(PDF 또는 결과표 사진) 업로드·다운로드·삭제. 회원 본인 파일만 다룬다 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class CheckupFileService {

	static final long MAX_SIZE = 10 * 1024 * 1024;

	/** 올릴 수 있는 형식: 확장자와 파일 앞부분 서명이 모두 맞아야 한다 */
	public enum Kind {
		PDF("application/pdf", ".pdf"), JPEG("image/jpeg", ".jpg"), PNG("image/png", ".png"), WEBP("image/webp", ".webp");

		private final String mimeType;
		private final String extension;

		Kind(String mimeType, String extension) {
			this.mimeType = mimeType;
			this.extension = extension;
		}

		public String mimeType() {
			return mimeType;
		}

		public boolean image() {
			return this != PDF;
		}

		static Optional<Kind> ofName(String name) {
			String n = name.toLowerCase();
			if (n.endsWith(".pdf")) return Optional.of(PDF);
			if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return Optional.of(JPEG);
			if (n.endsWith(".png")) return Optional.of(PNG);
			if (n.endsWith(".webp")) return Optional.of(WEBP);
			return Optional.empty();
		}

		/** 저장 이름(UUID + 확장자)으로. 예전 파일은 모두 PDF */
		public static Kind ofStored(String storedName) {
			return ofName(storedName).orElse(PDF);
		}

		boolean matches(byte[] head) {
			return switch (this) {
				case PDF -> startsWith(head, 0, "%PDF-".getBytes());
				case JPEG -> head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
				case PNG -> startsWith(head, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
				case WEBP -> startsWith(head, 0, "RIFF".getBytes()) && startsWith(head, 8, "WEBP".getBytes());
			};
		}

		private static boolean startsWith(byte[] head, int offset, byte[] sig) {
			if (head.length < offset + sig.length) return false;
			return Arrays.equals(Arrays.copyOfRange(head, offset, offset + sig.length), sig);
		}
	}

	private final CheckupFileRepository fileRepository;
	private final MemberRepository memberRepository;
	private final HealthCheckupRepository checkupRepository;
	private final Path uploadDir;

	public CheckupFileService(CheckupFileRepository fileRepository, MemberRepository memberRepository,
							  HealthCheckupRepository checkupRepository, @Value("${app.upload-dir}") String uploadDir) {
		this.fileRepository = fileRepository;
		this.memberRepository = memberRepository;
		this.checkupRepository = checkupRepository;
		this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	public record Download(Resource resource, String originalName, long size, Kind kind) {
	}

	public List<CheckupFile> list(String username) {
		return fileRepository.findByMemberUsernameOrderByUploadedAtDesc(username);
	}

	@Transactional
	public CheckupFile upload(String username, MultipartFile file) throws IOException {
		if (file == null || file.isEmpty()) throw new InvalidUploadException("파일을 선택하세요.");
		if (file.getSize() > MAX_SIZE) throw new InvalidUploadException("파일은 10MB 이하만 올릴 수 있습니다.");
		String original = cleanName(file.getOriginalFilename());
		Kind kind = Kind.ofName(original)
				.orElseThrow(() -> new InvalidUploadException("PDF 또는 사진(JPG·PNG·WEBP) 파일만 올릴 수 있습니다."));
		// 확장자만 바꾼 파일을 막기 위해 내용의 서명(PDF: %PDF-, JPG: FFD8FF …)을 확인한다
		try (InputStream in = file.getInputStream()) {
			if (!kind.matches(in.readNBytes(12))) {
				throw new InvalidUploadException(kind == Kind.PDF ? "PDF 형식이 아닌 파일입니다." : "사진 형식이 확장자와 맞지 않습니다.");
			}
		}
		Member member = memberRepository.findByUsername(username).orElseThrow();
		Files.createDirectories(uploadDir);
		String stored = UUID.randomUUID() + kind.extension; // 원래 이름은 경로에 쓰지 않는다
		try (InputStream in = file.getInputStream()) {
			Files.copy(in, uploadDir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
		}
		CheckupFile saved = fileRepository.save(new CheckupFile(member, original, stored, file.getSize()));
		log.info("검진 파일 업로드: memberId={}, fileId={}, size={}", member.getId(), saved.getId(), saved.getSize());
		return saved;
	}

	public Optional<Download> download(String username, Long id) {
		return fileRepository.findByIdAndMemberUsername(id, username)
				.map(f -> new Download(new FileSystemResource(uploadDir.resolve(f.getStoredName())), f.getOriginalName(), f.getSize(),
						Kind.ofStored(f.getStoredName())))
				.filter(d -> d.resource().exists());
	}

	/** 본인 파일의 내용 (수치 추출용) */
	public Optional<byte[]> readBytes(String username, Long id) throws IOException {
		Optional<CheckupFile> f = fileRepository.findByIdAndMemberUsername(id, username);
		if (f.isEmpty() || !Files.exists(uploadDir.resolve(f.get().getStoredName()))) return Optional.empty();
		return Optional.of(Files.readAllBytes(uploadDir.resolve(f.get().getStoredName())));
	}

	public Optional<CheckupFile> find(String username, Long id) {
		return fileRepository.findByIdAndMemberUsername(id, username);
	}

	@Transactional
	public boolean delete(String username, Long id) throws IOException {
		Optional<CheckupFile> found = fileRepository.findByIdAndMemberUsername(id, username);
		if (found.isEmpty()) return false;
		Files.deleteIfExists(uploadDir.resolve(found.get().getStoredName()));
		checkupRepository.detachFile(id);
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
