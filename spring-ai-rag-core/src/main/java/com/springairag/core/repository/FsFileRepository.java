package com.springairag.core.repository;

import com.springairag.core.entity.FsFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for {@link FsFile} entities.
 * The path field is the primary key.
 */
@Repository
public interface FsFileRepository extends JpaRepository<FsFile, String> {

    /**
     * Find all files whose path starts with the given prefix.
     * Used for listing all files under a virtual directory.
     *
     * @param prefix path prefix (e.g., "papers/skin-care-research/" — trailing slash included)
     * @return list of matching files ordered by path
     */
    List<FsFile> findByPathStartingWithOrderByPathAsc(String prefix);

    /**
     * Find all text files under a path prefix.
     *
     * @param prefix path prefix
     * @return list of text files
     */
    List<FsFile> findByPathStartingWithAndIsTextTrueOrderByPathAsc(String prefix);

    /**
     * Count files under a path prefix.
     *
     * @param prefix path prefix
     * @return count of files
     */
    long countByPathStartingWith(String prefix);

    /**
     * Check if a path exists.
     *
     * @param path file path
     * @return true if exists
     */
    boolean existsByPath(String path);

    /**
     * Delete all files under a path prefix.
     *
     * @param prefix path prefix
     */
    void deleteByPathStartingWith(String prefix);

    /**
     * List all root-level entries (files directly under a virtual directory).
     * Finds entries whose path has no more than one "/" segment beyond basePrefix,
     * or entries that equal basePrefix.
     *
     * @param basePrefix the base path prefix (e.g., "papers/skin-care-research/")
     * @return entries directly under the base prefix
     */
    @Query("""
        SELECT f FROM FsFile f
        WHERE f.path = :basePrefix
           OR (f.path LIKE :likePrefix AND f.path NOT LIKE :recursivePrefix)
        ORDER BY f.path ASC
        """)
    List<FsFile> findDirectChildren(@Param("basePrefix") String basePrefix,
                                     @Param("likePrefix") String likePrefix,
                                     @Param("recursivePrefix") String recursivePrefix);
}
