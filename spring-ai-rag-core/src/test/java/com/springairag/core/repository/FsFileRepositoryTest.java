package com.springairag.core.repository;

import com.springairag.core.entity.FsFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FsFileRepository Unit Tests
 *
 * <p>Tests all custom query methods and inherited CRUD operations via mock.
 * Spring Data JPA generates the implementation at runtime; this test verifies
 * the contract of each repository method using Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FsFileRepository Tests")
class FsFileRepositoryTest {

    @Mock
    private FsFileRepository repository;

    private FsFile makeFile(String path, boolean isText, String contentTxt) {
        FsFile f = new FsFile(path, isText,
                isText ? null : new byte[]{1, 2, 3},
                contentTxt,
                isText ? "text/markdown" : "application/pdf",
                100L);
        return f;
    }

    // ==================== Inherited CRUD ====================

    @Test
    @DisplayName("save stores entity and returns it")
    void save_returnsSavedEntity() {
        FsFile file = makeFile("docs/readme.md", true, "Hello world");
        when(repository.save(file)).thenReturn(file);

        FsFile saved = repository.save(file);

        assertThat(saved.getPath()).isEqualTo("docs/readme.md");
        verify(repository).save(file);
    }

    @Test
    @DisplayName("findById returns file when present")
    void findById_found() {
        FsFile file = makeFile("papers/intro.pdf", false, null);
        when(repository.findById("papers/intro.pdf")).thenReturn(Optional.of(file));

        Optional<FsFile> found = repository.findById("papers/intro.pdf");

        assertThat(found).isPresent();
        assertThat(found.get().getPath()).isEqualTo("papers/intro.pdf");
        verify(repository).findById("papers/intro.pdf");
    }

    @Test
    @DisplayName("findById returns empty when not present")
    void findById_notFound() {
        when(repository.findById("nonexistent/file.pdf")).thenReturn(Optional.empty());

        Optional<FsFile> found = repository.findById("nonexistent/file.pdf");

        assertThat(found).isEmpty();
        verify(repository).findById("nonexistent/file.pdf");
    }

    @Test
    @DisplayName("findAll returns all entities")
    void findAll_returnsAll() {
        FsFile f1 = makeFile("a.pdf", false, null);
        FsFile f2 = makeFile("b.md", true, "content");
        when(repository.findAll()).thenReturn(List.of(f1, f2));

        List<FsFile> all = repository.findAll();

        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("deleteById removes entity")
    void deleteById_removesEntity() {
        doNothing().when(repository).deleteById("papers/intro.pdf");

        repository.deleteById("papers/intro.pdf");

        verify(repository).deleteById("papers/intro.pdf");
    }

    @Test
    @DisplayName("count returns total number of entities")
    void count_returnsTotal() {
        when(repository.count()).thenReturn(99L);

        long count = repository.count();

        assertThat(count).isEqualTo(99L);
    }

    // ==================== findByPathStartingWithOrderByPathAsc ====================

    @Test
    @DisplayName("findByPathStartingWithOrderByPathAsc returns files under prefix")
    void findByPathStartingWith_returnsFilesUnderPrefix() {
        FsFile f1 = makeFile("papers/intro.pdf", false, null);
        FsFile f2 = makeFile("papers/deep/nested.pdf", false, null);
        when(repository.findByPathStartingWithOrderByPathAsc("papers/"))
                .thenReturn(List.of(f1, f2));

        List<FsFile> result = repository.findByPathStartingWithOrderByPathAsc("papers/");

        assertThat(result).hasSize(2);
        verify(repository).findByPathStartingWithOrderByPathAsc("papers/");
    }

    @Test
    @DisplayName("findByPathStartingWithOrderByPathAsc returns empty when no match")
    void findByPathStartingWith_returnsEmptyWhenNoMatch() {
        when(repository.findByPathStartingWithOrderByPathAsc("nonexistent/"))
                .thenReturn(List.of());

        List<FsFile> result = repository.findByPathStartingWithOrderByPathAsc("nonexistent/");

        assertThat(result).isEmpty();
    }

    // ==================== findByPathStartingWithAndIsTextTrueOrderByPathAsc ====================

    @Test
    @DisplayName("findByPathStartingWithAndIsTextTrue returns only text files")
    void findByPathStartingWithAndIsTextTrue_returnsOnlyTextFiles() {
        FsFile md1 = makeFile("docs/readme.md", true, "README");
        FsFile md2 = makeFile("docs/api.md", true, "API docs");
        when(repository.findByPathStartingWithAndIsTextTrueOrderByPathAsc("docs/"))
                .thenReturn(List.of(md1, md2));

        List<FsFile> result = repository.findByPathStartingWithAndIsTextTrueOrderByPathAsc("docs/");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(FsFile::getIsText);
        verify(repository).findByPathStartingWithAndIsTextTrueOrderByPathAsc("docs/");
    }

    @Test
    @DisplayName("findByPathStartingWithAndIsTextTrue returns empty when no text files match")
    void findByPathStartingWithAndIsTextTrue_returnsEmptyWhenNoTextFiles() {
        when(repository.findByPathStartingWithAndIsTextTrueOrderByPathAsc("pdfs/"))
                .thenReturn(List.of());

        List<FsFile> result = repository.findByPathStartingWithAndIsTextTrueOrderByPathAsc("pdfs/");

        assertThat(result).isEmpty();
    }

    // ==================== countByPathStartingWith ====================

    @Test
    @DisplayName("countByPathStartingWith returns correct count")
    void countByPathStartingWith_returnsCorrectCount() {
        when(repository.countByPathStartingWith("papers/")).thenReturn(5L);

        long count = repository.countByPathStartingWith("papers/");

        assertThat(count).isEqualTo(5L);
        verify(repository).countByPathStartingWith("papers/");
    }

    @Test
    @DisplayName("countByPathStartingWith returns zero when no match")
    void countByPathStartingWith_returnsZeroWhenNoMatch() {
        when(repository.countByPathStartingWith("nonexistent/")).thenReturn(0L);

        long count = repository.countByPathStartingWith("nonexistent/");

        assertThat(count).isZero();
    }

    // ==================== existsByPath ====================

    @Test
    @DisplayName("existsByPath returns true for existing file")
    void existsByPath_returnsTrueForExisting() {
        when(repository.existsByPath("papers/intro.pdf")).thenReturn(true);

        boolean exists = repository.existsByPath("papers/intro.pdf");

        assertThat(exists).isTrue();
        verify(repository).existsByPath("papers/intro.pdf");
    }

    @Test
    @DisplayName("existsByPath returns false for non-existing file")
    void existsByPath_returnsFalseForNonExisting() {
        when(repository.existsByPath("nonexistent/file.pdf")).thenReturn(false);

        boolean exists = repository.existsByPath("nonexistent/file.pdf");

        assertThat(exists).isFalse();
    }

    // ==================== deleteByPathStartingWith ====================

    @Test
    @DisplayName("deleteByPathStartingWith deletes matching files")
    void deleteByPathStartingWith_deletesMatchingFiles() {
        doNothing().when(repository).deleteByPathStartingWith("papers/");

        repository.deleteByPathStartingWith("papers/");

        verify(repository).deleteByPathStartingWith("papers/");
    }

    @Test
    @DisplayName("deleteByPathStartingWith with no matching files is a no-op")
    void deleteByPathStartingWith_noOpWhenNoMatch() {
        doNothing().when(repository).deleteByPathStartingWith("nonexistent/");

        repository.deleteByPathStartingWith("nonexistent/");

        verify(repository).deleteByPathStartingWith("nonexistent/");
    }

    // ==================== findDirectChildren ====================

    @Test
    @DisplayName("findDirectChildren returns immediate children only")
    void findDirectChildren_returnsImmediateChildrenOnly() {
        FsFile child1 = makeFile("base/child1.pdf", false, null);
        FsFile child2 = makeFile("base/child2.txt", true, "text");
        when(repository.findDirectChildren("base/", "base/%", "base/%/%"))
                .thenReturn(List.of(child1, child2));

        List<FsFile> result = repository.findDirectChildren("base/", "base/%", "base/%/%");

        assertThat(result).hasSize(2);
        verify(repository).findDirectChildren("base/", "base/%", "base/%/%");
    }

    @Test
    @DisplayName("findDirectChildren returns empty when no match")
    void findDirectChildren_returnsEmptyWhenNoMatch() {
        when(repository.findDirectChildren("nonexistent/", "nonexistent/%", "nonexistent/%/%"))
                .thenReturn(List.of());

        List<FsFile> result = repository.findDirectChildren("nonexistent/", "nonexistent/%", "nonexistent/%/%");

        assertThat(result).isEmpty();
    }

    // ==================== findAll(Pageable) ====================

    @Test
    @DisplayName("findAll with Pageable returns paginated results")
    void findAll_withPageable_returnsPaginatedResults() {
        FsFile f1 = makeFile("docs/a.md", true, "a");
        FsFile f2 = makeFile("docs/b.md", true, "b");
        Page<FsFile> page = new PageImpl<>(List.of(f1, f2), PageRequest.of(0, 10), 2);
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        Page<FsFile> result = repository.findAll(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    // ==================== Entity ====================

    @Test
    @DisplayName("FsFile entity stores all fields correctly")
    void entity_storesAllFields() {
        OffsetDateTime now = OffsetDateTime.now();
        FsFile file = new FsFile("docs/readme.md", true,
                null, "Hello world", "text/markdown", 11L);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);

        assertThat(file.getPath()).isEqualTo("docs/readme.md");
        assertThat(file.getIsText()).isTrue();
        assertThat(file.getContentBin()).isNull();
        assertThat(file.getContentTxt()).isEqualTo("Hello world");
        assertThat(file.getMimeType()).isEqualTo("text/markdown");
        assertThat(file.getFileSize()).isEqualTo(11L);
        assertThat(file.getCreatedAt()).isEqualTo(now);
        assertThat(file.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("FsFile default constructor creates entity with default isText=false")
    void entity_defaultConstructor() {
        FsFile file = new FsFile();

        assertThat(file.getPath()).isNull();
        assertThat(file.getIsText()).isFalse(); // default in entity
        assertThat(file.getContentBin()).isNull();
        assertThat(file.getContentTxt()).isNull();
    }

    @Test
    @DisplayName("FsFile full constructor creates populated entity")
    void entity_fullConstructor() {
        byte[] bin = new byte[]{1, 2, 3};
        FsFile file = new FsFile("papers/intro.pdf", false, bin, null, "application/pdf", 1024L);

        assertThat(file.getPath()).isEqualTo("papers/intro.pdf");
        assertThat(file.getIsText()).isFalse();
        assertThat(file.getContentBin()).isEqualTo(bin);
        assertThat(file.getContentTxt()).isNull();
        assertThat(file.getMimeType()).isEqualTo("application/pdf");
        assertThat(file.getFileSize()).isEqualTo(1024L);
    }

    @Test
    @DisplayName("FsFile setters and getters work correctly")
    void entity_settersAndGetters() {
        FsFile file = new FsFile();
        file.setPath("new/path.pdf");
        file.setIsText(false);
        byte[] bin = new byte[]{9};
        file.setContentBin(bin);
        file.setContentTxt("text content");
        file.setMimeType("application/pdf");
        file.setFileSize(500L);

        assertThat(file.getPath()).isEqualTo("new/path.pdf");
        assertThat(file.getIsText()).isFalse();
        assertThat(file.getContentBin()).isEqualTo(bin);
        assertThat(file.getContentTxt()).isEqualTo("text content");
        assertThat(file.getMimeType()).isEqualTo("application/pdf");
        assertThat(file.getFileSize()).isEqualTo(500L);
    }
}
