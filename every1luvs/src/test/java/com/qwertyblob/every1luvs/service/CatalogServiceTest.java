package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.AdminCatalogResponse;
import com.qwertyblob.every1luvs.dto.CatalogDocument;
import com.qwertyblob.every1luvs.dto.CatalogDraftActionRequest;
import com.qwertyblob.every1luvs.dto.CatalogImageResponse;
import com.qwertyblob.every1luvs.dto.SaveCatalogDraftRequest;
import com.qwertyblob.every1luvs.dto.UploadCatalogImageRequest;
import com.qwertyblob.every1luvs.entity.CatalogImageEntity;
import com.qwertyblob.every1luvs.entity.CatalogVersionEntity;
import com.qwertyblob.every1luvs.repository.CatalogImageRepository;
import com.qwertyblob.every1luvs.repository.CatalogVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ADMIN = "admin@example.com";

    @Mock CatalogVersionRepository repository;
    @Mock CatalogImageRepository imageRepository;
    @Mock ImageSanitizer imageSanitizer;
    @Mock PlatformTransactionManager transactionManager;

    private CatalogService catalogService;
    private final CatalogDocument seed = TestCatalogs.seedDocument();

    @BeforeEach
    void setUp() {
        catalogService = new CatalogService(repository, imageRepository, imageSanitizer, transactionManager);
        lenient().when(repository.saveAndFlush(any(CatalogVersionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Every photo referenced by a test catalog exists unless a test says otherwise.
        lenient().when(imageRepository.existsById(any())).thenReturn(true);
    }

    // ─── startup ──────────────────────────────────────────────────────────────────

    @Test
    void init_seedsThePublishedCatalogWhenTableIsEmpty() {
        when(repository.findByStatus(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(CatalogVersionEntity.class))).thenAnswer(invocation -> {
            CatalogVersionEntity row = invocation.getArgument(0);
            row.setId(1L);
            return row;
        });

        catalogService.init();

        ArgumentCaptor<CatalogVersionEntity> saved = ArgumentCaptor.forClass(CatalogVersionEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CatalogVersionEntity.PUBLISHED);
        assertThat(read(saved.getValue())).isEqualTo(seed);
        assertThat(catalogService.current().versionId()).isEqualTo(1L);
        assertThat(catalogService.current().service("classic")).get()
                .extracting(BookingCatalog.Service::price).isEqualTo(58);
    }

    @Test
    void init_loadsTheExistingPublishedRow_andNeverReseeds() {
        CatalogDocument repriced = withClassicPrice(62);
        when(repository.findByStatus(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(7L, repriced)));

        catalogService.init();

        verify(repository, never()).saveAndFlush(any());
        assertThat(catalogService.current().versionId()).isEqualTo(7L);
        assertThat(catalogService.current().service("classic")).get()
                .extracting(BookingCatalog.Service::price).isEqualTo(62);
    }

    @Test
    void install_neverReplacesANewerCatalogWithAnOlderOne() {
        // Commit callbacks of two quick publishes can run out of order; the older must not win.
        catalogService.install(BookingCatalog.of(3L, seed));
        catalogService.install(BookingCatalog.of(2L, withClassicPrice(99)));

        assertThat(catalogService.current().versionId()).isEqualTo(3L);
        assertThat(catalogService.current().service("classic")).get()
                .extracting(BookingCatalog.Service::price).isEqualTo(58);
    }

    @Test
    void current_hidesHiddenItemsFromBookingLookups() {
        catalogService.install(BookingCatalog.of(1L, withClassicHidden()));

        assertThat(catalogService.current().service("classic")).isEmpty();
        assertThat(catalogService.publicCatalog().nailServices())
                .extracting(CatalogDocument.ServiceItem::id).doesNotContain("classic");
    }

    // ─── draft create ─────────────────────────────────────────────────────────────

    @Test
    void saveDraft_create_basedOnTheLiveCatalog_insertsADraft() {
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));
        when(repository.existsByStatus(CatalogVersionEntity.DRAFT)).thenReturn(false);
        when(repository.saveAndFlush(any(CatalogVersionEntity.class))).thenAnswer(invocation -> {
            CatalogVersionEntity row = invocation.getArgument(0);
            row.setId(9L);
            row.setVersion(0L);
            return row;
        });

        AdminCatalogResponse response = catalogService.saveDraft(
                new SaveCatalogDraftRequest(null, null, 5L, withClassicPrice(62)), ADMIN);

        ArgumentCaptor<CatalogVersionEntity> saved = ArgumentCaptor.forClass(CatalogVersionEntity.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CatalogVersionEntity.DRAFT);
        assertThat(saved.getValue().getBasePublishedId()).isEqualTo(5L);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(ADMIN);
        assertThat(response.published().id()).isEqualTo(5L);
        assertThat(response.draft().id()).isEqualTo(9L);
        assertThat(response.draft().content().nailServices().get(1).price()).isEqualTo(62);
    }

    @Test
    void saveDraft_create_fromAnOlderLiveCatalog_is409AndWritesNothing() {
        // Admin A loaded v5, admin B published v6, A's first save must not re-base A's edits onto v6.
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(6L, seed)));

        assertStatus(() -> catalogService.saveDraft(new SaveCatalogDraftRequest(null, null, 5L, seed), ADMIN),
                HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void saveDraft_create_whenADraftAlreadyExists_is409() {
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));
        when(repository.existsByStatus(CatalogVersionEntity.DRAFT)).thenReturn(true);

        assertStatus(() -> catalogService.saveDraft(new SaveCatalogDraftRequest(null, null, 5L, seed), ADMIN),
                HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void saveDraft_create_withoutBasePublishedId_is400() {
        assertStatus(() -> catalogService.saveDraft(new SaveCatalogDraftRequest(null, null, null, seed), ADMIN),
                HttpStatus.BAD_REQUEST);
    }

    // ─── draft update / discard ───────────────────────────────────────────────────

    @Test
    void saveDraft_update_atTheExpectedVersion_writesTheNewContent() {
        CatalogVersionEntity draft = draft(9L, 3L, 5L, seed);
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft));
        when(repository.findByStatus(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));

        catalogService.saveDraft(new SaveCatalogDraftRequest(9L, 3L, null, withClassicPrice(62)), ADMIN);

        assertThat(read(draft).nailServices().get(1).price()).isEqualTo(62);
        verify(repository).saveAndFlush(draft);
    }

    @Test
    void saveDraft_update_atAStaleVersion_is409AndWritesNothing() {
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 4L, 5L, seed)));

        assertStatus(() -> catalogService.saveDraft(new SaveCatalogDraftRequest(9L, 3L, null, seed), ADMIN),
                HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void saveDraft_update_ofADraftThatNoLongerExists_is409() {
        // The draft the admin had open was published (or discarded and restarted) by someone else.
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(10L, 0L, 5L, seed)));

        assertStatus(() -> catalogService.saveDraft(new SaveCatalogDraftRequest(9L, 3L, null, seed), ADMIN),
                HttpStatus.CONFLICT);
    }

    @Test
    void discardDraft_atAStaleVersion_is409AndDeletesNothing() {
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 4L, 5L, seed)));

        assertStatus(() -> catalogService.discardDraft(new CatalogDraftActionRequest(9L, 3L)), HttpStatus.CONFLICT);
        verify(repository, never()).delete(any());
    }

    @Test
    void discardDraft_atTheExpectedVersion_deletesIt() {
        CatalogVersionEntity draft = draft(9L, 3L, 5L, seed);
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft));
        when(repository.findByStatus(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));

        AdminCatalogResponse response = catalogService.discardDraft(new CatalogDraftActionRequest(9L, 3L));

        verify(repository).delete(draft);
        assertThat(response.draft()).isNull();
    }

    // ─── publish ──────────────────────────────────────────────────────────────────

    @Test
    void publish_supersedesTheLiveRow_promotesTheDraft_andSwapsTheLiveCatalog() {
        catalogService.install(BookingCatalog.of(5L, seed));
        CatalogVersionEntity live = published(5L, seed);
        CatalogVersionEntity draft = draft(9L, 3L, 5L, withClassicPrice(62));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(live));

        AdminCatalogResponse response = catalogService.publish(new CatalogDraftActionRequest(9L, 3L), ADMIN);

        // The live row must be released before the draft takes the one-PUBLISHED index.
        var inOrder = inOrder(repository);
        inOrder.verify(repository).saveAndFlush(live);
        inOrder.verify(repository).saveAndFlush(draft);
        assertThat(live.getStatus()).isEqualTo(CatalogVersionEntity.SUPERSEDED);
        assertThat(draft.getStatus()).isEqualTo(CatalogVersionEntity.PUBLISHED);
        assertThat(draft.getPublishedBy()).isEqualTo(ADMIN);
        assertThat(response.published().id()).isEqualTo(9L);
        assertThat(response.draft()).isNull();
        // No transaction synchronization in a unit test, so the swap applies immediately.
        assertThat(catalogService.current().versionId()).isEqualTo(9L);
        assertThat(catalogService.current().service("classic")).get()
                .extracting(BookingCatalog.Service::price).isEqualTo(62);
    }

    @Test
    void publish_ofAStaleDraftVersion_is409AndChangesNothing() {
        catalogService.install(BookingCatalog.of(5L, seed));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 4L, 5L, seed)));

        assertStatus(() -> catalogService.publish(new CatalogDraftActionRequest(9L, 3L), ADMIN), HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
        assertThat(catalogService.current().versionId()).isEqualTo(5L);
    }

    @Test
    void publish_whenTheLiveCatalogMovedSinceTheDraftStarted_is409() {
        catalogService.install(BookingCatalog.of(6L, seed));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 3L, 5L, seed)));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(6L, seed)));

        assertStatus(() -> catalogService.publish(new CatalogDraftActionRequest(9L, 3L), ADMIN), HttpStatus.CONFLICT);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_ofAnInvalidDraft_is400AndChangesNothing() {
        catalogService.install(BookingCatalog.of(5L, seed));
        List<CatalogDocument.ServiceItem> services = new ArrayList<>(seed.nailServices());
        CatalogDocument.ServiceItem express = services.get(0);
        services.set(0, new CatalogDocument.ServiceItem(express.id(), express.label(), express.name(),
                express.desc(), -1, express.durationMin(), false, false, express.imageId())); // negative price
        CatalogDocument invalid = new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 3L, 5L, invalid)));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));

        assertStatus(() -> catalogService.publish(new CatalogDraftActionRequest(9L, 3L), ADMIN), HttpStatus.BAD_REQUEST);
        verify(repository, never()).saveAndFlush(any());
        assertThat(catalogService.current().versionId()).isEqualTo(5L);
    }

    // ─── photos ───────────────────────────────────────────────────────────────────

    @Test
    void saveDraft_aServiceWithoutAPicture_is400() {
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));
        List<CatalogDocument.ServiceItem> services = new ArrayList<>(seed.nailServices());
        services.add(new CatalogDocument.ServiceItem("gel-pedicure", null, "Gel Pedicure", null, 65, 60, false, false));
        CatalogDocument noPicture = new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());

        var ex = catchThrowableOfType(() -> catalogService.saveDraft(
                new SaveCatalogDraftRequest(null, null, 5L, noPicture), ADMIN), ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("\"Gel Pedicure\" needs a picture");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_aServiceWhosePictureWasNeverUploaded_is400() {
        catalogService.install(BookingCatalog.of(5L, seed));
        when(imageRepository.existsById("seed-classic")).thenReturn(false);
        when(repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)).thenReturn(Optional.of(draft(9L, 3L, 5L, seed)));
        when(repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)).thenReturn(Optional.of(published(5L, seed)));

        assertStatus(() -> catalogService.publish(new CatalogDraftActionRequest(9L, 3L), ADMIN), HttpStatus.BAD_REQUEST);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void uploadImage_storesTheSanitizedPhotoUnderItsDigest() {
        byte[] cleaned = {1, 2, 3};
        when(imageSanitizer.sanitizeServicePhoto("aGVsbG8=", CatalogService.PHOTO_MAX_EDGE))
                .thenReturn(new SanitizedImage("photo.jpg", "image/jpeg", cleaned));
        when(imageRepository.existsById(any())).thenReturn(false);

        CatalogImageResponse response = catalogService.uploadImage(
                new UploadCatalogImageRequest("image/png", "aGVsbG8="), ADMIN);

        // SHA-256 of {1,2,3}
        assertThat(response.id()).isEqualTo("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
        ArgumentCaptor<CatalogImageEntity> saved = ArgumentCaptor.forClass(CatalogImageEntity.class);
        verify(imageRepository).save(saved.capture());
        assertThat(saved.getValue().getData()).isEqualTo(cleaned);
        assertThat(saved.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(ADMIN);
    }

    @Test
    void uploadImage_rejectsNonImagesAndUnreadablePhotos() {
        assertStatus(() -> catalogService.uploadImage(new UploadCatalogImageRequest("image/gif", "aGVsbG8="), ADMIN),
                HttpStatus.BAD_REQUEST);
        when(imageSanitizer.sanitizeServicePhoto(any(), anyInt())).thenReturn(null);
        assertStatus(() -> catalogService.uploadImage(new UploadCatalogImageRequest("image/jpeg", "bm90LWFuLWltYWdl"), ADMIN),
                HttpStatus.BAD_REQUEST);
        verify(imageRepository, never()).save(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────

    private CatalogDocument withClassicPrice(int price) {
        List<CatalogDocument.ServiceItem> services = new ArrayList<>(seed.nailServices());
        CatalogDocument.ServiceItem classic = services.get(1);
        services.set(1, new CatalogDocument.ServiceItem(classic.id(), classic.label(), classic.name(), classic.desc(),
                price, classic.durationMin(), classic.popular(), classic.hidden(), classic.imageId()));
        return new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());
    }

    private CatalogDocument withClassicHidden() {
        List<CatalogDocument.ServiceItem> services = new ArrayList<>(seed.nailServices());
        CatalogDocument.ServiceItem classic = services.get(1);
        services.set(1, new CatalogDocument.ServiceItem(classic.id(), classic.label(), classic.name(), classic.desc(),
                classic.price(), classic.durationMin(), classic.popular(), true, classic.imageId()));
        return new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());
    }

    private static CatalogVersionEntity published(long id, CatalogDocument content) {
        CatalogVersionEntity row = new CatalogVersionEntity();
        row.setId(id);
        row.setStatus(CatalogVersionEntity.PUBLISHED);
        row.setVersion(0L);
        row.setContent(JSON.writeValueAsString(content));
        return row;
    }

    private static CatalogVersionEntity draft(long id, long version, long basePublishedId, CatalogDocument content) {
        CatalogVersionEntity row = new CatalogVersionEntity();
        row.setId(id);
        row.setStatus(CatalogVersionEntity.DRAFT);
        row.setVersion(version);
        row.setBasePublishedId(basePublishedId);
        row.setContent(JSON.writeValueAsString(content));
        return row;
    }

    private static CatalogDocument read(CatalogVersionEntity row) {
        return JSON.readValue(row.getContent(), CatalogDocument.class);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        ResponseStatusException ex = catchThrowableOfType(call::run, ResponseStatusException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(status);
    }
}
