package com.springairag.core.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagPdfProperties;
import com.springairag.core.controller.PdfImportController;
import com.springairag.core.entity.FsFile;
import com.springairag.core.entity.FsImportBatch;
import com.springairag.core.repository.FsFileRepository;
import com.springairag.core.repository.FsImportBatchRepository;
import com.springairag.core.service.MarkdownRendererService;
import com.springairag.core.service.PdfImportService;
import com.springairag.core.service.PdfToRagService;
import com.springairag.core.service.pdf.PdfBoxConverter;
import com.springairag.core.versioning.ApiVersionConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PDF 导入的真实 HTTP、PDFBox、JPA、Flyway 与 PostgreSQL 事务验收。
 */
@EnabledIfSystemProperty(named = "pdf-import.it.enabled", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = PdfImportPostgresIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.ai.openai.chat.enabled=false",
        "spring.ai.openai.embedding.enabled=false",
        "rag.pdf.enabled=true"
})
class PdfImportPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse(
                    System.getProperty(
                            "testcontainers.pg.image",
                            System.getenv().getOrDefault(
                                    "TESTCONTAINERS_PG_IMAGE",
                                    "pgvector/pgvector:pg16")))
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("pdf_import")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FsFileRepository fsFileRepository;

    @MockitoSpyBean
    private FsImportBatchRepository fsImportBatchRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanImportedFiles() {
        fsFileRepository.deleteAll();
        fsFileRepository.flush();
    }

    @Test
    void importsReadableMetadataAndPreservesHistoricalFallbackAndCascade()
            throws Exception {
        assertEquals("59", jdbc.queryForObject("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """, String.class));

        MvcResult importResult = mockMvc.perform(multipart("/api/v1/rag/files/pdf")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "../../Readable warranty policy.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                minimalPdf()))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename")
                        .value("Readable warranty policy.pdf"))
                .andExpect(jsonPath("$.displayName")
                        .value("Readable warranty policy.pdf"))
                .andReturn();

        JsonNode imported = objectMapper.readTree(
                importResult.getResponse().getContentAsString());
        String importId = imported.path("uuid").asText();
        String entryPath = importId + "/default.md";

        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fs_files WHERE path LIKE ?",
                Long.class, importId + "/%"));
        assertEquals("Readable warranty policy.pdf", jdbc.queryForObject(
                "SELECT display_name FROM fs_import_batches WHERE import_id = ?::uuid",
                String.class, importId));
        String markdown = jdbc.queryForObject(
                "SELECT content_txt FROM fs_files WHERE path = ?",
                String.class, entryPath);
        org.junit.jupiter.api.Assertions.assertTrue(markdown.startsWith("# source"));

        mockMvc.perform(get("/api/v1/rag/files/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.importId == '%s')].displayName"
                        .formatted(importId))
                        .value(hasItem("Readable warranty policy.pdf")))
                .andExpect(jsonPath("$.entries[?(@.importId == '%s')].name"
                        .formatted(importId))
                        .value(hasItem(importId)));

        mockMvc.perform(get("/api/v1/rag/files/tree")
                        .param("path", importId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importMetadata.importId").value(importId))
                .andExpect(jsonPath("$.importMetadata.entryPath").value(entryPath))
                .andExpect(jsonPath("$.importMetadata.originalFilename")
                        .value("Readable warranty policy.pdf"));

        UUID historicalId = UUID.randomUUID();
        fsFileRepository.saveAndFlush(new FsFile(
                historicalId + "/default.md",
                true,
                "# Historical".getBytes(StandardCharsets.UTF_8),
                "# Historical",
                "text/markdown",
                12L));

        mockMvc.perform(get("/api/v1/rag/files/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.name == '%s')].displayName"
                        .formatted(historicalId))
                        .value(hasItem(nullValue())))
                .andExpect(jsonPath("$.entries[?(@.name == '%s')].importId"
                        .formatted(historicalId))
                        .value(hasItem(nullValue())));

        mockMvc.perform(get("/api/v1/rag/files/tree")
                        .param("path", historicalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importMetadata").doesNotExist());

        fsFileRepository.deleteById(entryPath);
        fsFileRepository.flush();
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fs_import_batches WHERE import_id = ?::uuid",
                Long.class, importId));
    }

    @Test
    void batchFailureRollsBackFilesAlreadyFlushedByJpa() throws Exception {
        doThrow(new IllegalStateException("forced batch failure"))
                .when(fsImportBatchRepository)
                .save(any(FsImportBatch.class));

        mockMvc.perform(multipart("/api/v1/rag/files/pdf")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file",
                                "rollback.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                minimalPdf())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("PDF import failed: forced batch failure"));

        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fs_files", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM fs_import_batches", Long.class));
    }

    private static byte[] minimalPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(
                        Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Warranty policy integration test");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.ai.model.minimax.autoconfigure.MiniMaxEmbeddingAutoConfiguration",
            "org.springframework.ai.model.minimax.autoconfigure.MiniMaxChatAutoConfiguration"
    })
    @EntityScan(basePackageClasses = {FsFile.class, FsImportBatch.class})
    @EnableJpaRepositories(basePackageClasses = {
            FsFileRepository.class,
            FsImportBatchRepository.class
    })
    @EnableTransactionManagement
    @EnableConfigurationProperties(RagPdfProperties.class)
    @Import({
            ApiVersionConfig.class,
            PdfImportController.class,
            PdfImportService.class,
            PdfBoxConverter.class,
            MarkdownRendererService.class
    })
    static class TestApplication {

        @Bean
        @Primary
        PdfToRagService pdfToRagService() {
            return mock(PdfToRagService.class);
        }
    }
}
