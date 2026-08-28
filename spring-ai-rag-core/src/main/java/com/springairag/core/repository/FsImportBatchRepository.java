package com.springairag.core.repository;

import com.springairag.core.entity.FsImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FsImportBatchRepository extends JpaRepository<FsImportBatch, UUID> {

    Optional<FsImportBatch> findByEntryPath(String entryPath);

    List<FsImportBatch> findAllByImportIdIn(Collection<UUID> importIds);
}
