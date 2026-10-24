package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.AdminCatalogResponse;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CatalogDocument;
import com.qwertyblob.every1luvs.dto.CatalogDraftActionRequest;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.dto.SaveCatalogDraftRequest;
import com.qwertyblob.every1luvs.entity.CatalogVersionEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.exception.CodedResponseStatusException;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.CatalogVersionRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin catalog draft → publish lifecycle against real PostgreSQL with the real Flyway
 * migrations, so the V13 partial unique indexes, row locks and transaction rollback are exercised
 * (an H2 create-drop schema would have none of them).
 */
@SpringBootTest(properties = {
        "app.auth.token-secret=integration-test-secret-that-is-32-chars"
})
@Testcontainers
@AutoConfigureMockMvc
class CatalogIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int SUCCESS = 200;
    private static final String ADMIN_A = "admin-a@test.local";
    private static final String ADMIN_B = "admin-b@test.local";

    @Autowired private CatalogService catalogService;
    @Autowired private BookingService bookingService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private CatalogVersionRepository catalogRepository;
    @Autowired private com.qwertyblob.every1luvs.repository.CatalogImageRepository imageRepository;
    @Autowired private ImageSanitizer imageSanitizer;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void resetToSeed() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        // One statement, so the self-referencing base_published_id FK is checked only at its end.
        catalogRepository.deleteAllInBatch();
        // Re-seed as on first boot. The new row's BIGSERIAL id is larger than anything installed
        // before, so install() accepts it.
        catalogService.init();
    }

    @Test
    void firstBoot_seedsExactlyOnePublishedRow_withTodaysCatalog() {
        assertThat(catalogRepository.findAll()).singleElement()
                .extracting(CatalogVersionEntity::getStatus).isEqualTo(CatalogVersionEntity.PUBLISHED);
        var publicCatalog = catalogService.publicCatalog();
        assertThat(publicCatalog.version()).isEqualTo(liveId());
        assertThat(publicCatalog.nailServices()).extracting(CatalogDocument.ServiceItem::price)
                .containsExactly(42, 58, 72, 108);
        assertThat(publicCatalog.repairs()).extracting(CatalogDocument.RepairItem::priceNote)
                .containsExactly("S$3/nail", "S$8/nail");
    }

    @Test
    void partialUniqueIndexes_allowOnlyOneDraftAndOnePublished() {
        catalogService.saveDraft(new SaveCatalogDraftRequest(null, null, liveId(), seed()), ADMIN_A);

        assertThatThrownBy(() -> catalogRepository.saveAndFlush(row(CatalogVersionEntity.DRAFT)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> catalogRepository.saveAndFlush(row(CatalogVersionEntity.PUBLISHED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void competingSavesAtTheSameVersion_exactlyOneWins() throws Exception {
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(60));

        List<Integer> results = runSimultaneously(List.of(
                () -> statusOf(() -> catalogService.saveDraft(
                        new SaveCatalogDraftRequest(draft.id(), draft.version(), null, withClassicPrice(61)), ADMIN_A)),
                () -> statusOf(() -> catalogService.saveDraft(
                        new SaveCatalogDraftRequest(draft.id(), draft.version(), null, withClassicPrice(62)), ADMIN_B))));

        assertThat(results).containsExactlyInAnyOrder(SUCCESS, 409);
        AdminCatalogResponse.Draft after = catalogService.adminView().draft();
        assertThat(after.version()).isEqualTo(draft.version() + 1);
        assertThat(classicPrice(after.content())).isIn(61, 62);
    }

    @Test
    void publishRacingDiscard_exactlyOneWins() throws Exception {
        long before = liveId();
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(62));
        CatalogDraftActionRequest action = new CatalogDraftActionRequest(draft.id(), draft.version());

        List<Integer> results = runSimultaneously(List.of(
                () -> statusOf(() -> catalogService.publish(action, ADMIN_A)),
                () -> statusOf(() -> catalogService.discardDraft(action))));

        assertThat(results).containsExactlyInAnyOrder(SUCCESS, 409);
        assertThat(catalogRepository.findByStatus(CatalogVersionEntity.DRAFT)).isEmpty();
        assertThat(catalogRepository.findAll().stream()
                .filter(r -> CatalogVersionEntity.PUBLISHED.equals(r.getStatus())).count()).isEqualTo(1);
        if (liveId() != before) {
            assertThat(catalogService.current().versionId()).isEqualTo(draft.id());
        }
    }

    @Test
    void publishingAStaleVersion_afterAnotherAdminsSave_is409AndChangesNothing() {
        AdminCatalogResponse.Draft reviewedByA = createDraft(ADMIN_A, withClassicPrice(60));
        // B edits the draft after A reviewed it.
        catalogService.saveDraft(new SaveCatalogDraftRequest(reviewedByA.id(), reviewedByA.version(), null,
                withClassicPrice(99)), ADMIN_B);
        long liveBefore = liveId();

        assertConflict(() -> catalogService.publish(
                new CatalogDraftActionRequest(reviewedByA.id(), reviewedByA.version()), ADMIN_A));
        assertConflict(() -> catalogService.discardDraft(
                new CatalogDraftActionRequest(reviewedByA.id(), reviewedByA.version())));

        assertThat(liveId()).isEqualTo(liveBefore);
        assertThat(catalogService.current().versionId()).isEqualTo(liveBefore);
        assertThat(classicPrice(catalogService.adminView().draft().content())).isEqualTo(99);
    }

    @Test
    void creatingADraftFromAnOlderLiveCatalog_is409AndCreatesNoDraft() {
        long loadedByA = liveId();
        // B drafts and publishes while A is still editing what they loaded.
        AdminCatalogResponse.Draft byB = createDraft(ADMIN_B, withClassicPrice(70));
        catalogService.publish(new CatalogDraftActionRequest(byB.id(), byB.version()), ADMIN_B);

        assertConflict(() -> catalogService.saveDraft(
                new SaveCatalogDraftRequest(null, null, loadedByA, withClassicPrice(62)), ADMIN_A));

        assertThat(catalogRepository.findByStatus(CatalogVersionEntity.DRAFT)).isEmpty();
        assertThat(classicPrice(catalogService.current().document())).isEqualTo(70);
    }

    @Test
    void creatingDraftWhilePublicationCommits_is409AndCreatesNoDraft() throws Exception {
        long original = liveId();
        var draft = createDraft(ADMIN_A, withClassicPrice(70));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        var waitingSave = new AtomicReference<Future<Integer>>();
        try {
            // publish joins this transaction: both status changes are flushed, but the old live
            // row remains locked until the callback returns and the outer transaction commits.
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                catalogService.publish(new CatalogDraftActionRequest(draft.id(), draft.version()), ADMIN_A);
                waitingSave.set(pool.submit(() -> statusOf(() -> catalogService.saveDraft(
                        new SaveCatalogDraftRequest(null, null, original, seed()), ADMIN_B))));

                // Observe the wait itself instead of guessing how long the other thread needs.
                // Once released, PostgreSQL rechecks the old row's status and returns no row.
                boolean observedLockWait = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    jdbcTemplate.execute("select pg_stat_clear_snapshot()");
                    Integer waiting = jdbcTemplate.queryForObject("""
                            select count(*) from pg_stat_activity
                            where pid <> pg_backend_pid() and datname = current_database()
                              and wait_event_type = 'Lock' and query ilike '%tbl_catalog_versions%'
                            """, Integer.class);
                    if (waiting != null && waiting > 0) {
                        observedLockWait = true;
                        break;
                    }
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted waiting for the draft-creation lock", e);
                    }
                }
                assertThat(observedLockWait).as("draft creation waited on the published row").isTrue();
            });

            assertThat(waitingSave.get().get(10, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(catalogRepository.findByStatus(CatalogVersionEntity.DRAFT)).isEmpty();
            assertThat(liveId()).isEqualTo(draft.id());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureAfterTheLiveRowWasSuperseded_rollsBackEverything_andKeepsTheLiveCatalog() {
        long liveBefore = liveId();
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(62));
        // Make the database reject the second write of publish (DRAFT → PUBLISHED). The old
        // PUBLISHED row has already been flushed to SUPERSEDED by then, so this proves both status
        // changes roll back together.
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_fail_draft_promotion() RETURNS trigger AS $$
                BEGIN
                    IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
                        RAISE EXCEPTION 'simulated failure while promoting the draft';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_draft_promotion BEFORE UPDATE ON tbl_catalog_versions
                FOR EACH ROW EXECUTE FUNCTION test_fail_draft_promotion()""");
        try {
            assertThatThrownBy(() -> catalogService.publish(
                    new CatalogDraftActionRequest(draft.id(), draft.version()), ADMIN_A))
                    .rootCause().hasMessageContaining("simulated failure");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_fail_draft_promotion ON tbl_catalog_versions");
            jdbcTemplate.execute("DROP FUNCTION test_fail_draft_promotion()");
        }

        CatalogVersionEntity live = catalogRepository.findById(liveBefore).orElseThrow();
        CatalogVersionEntity stillDraft = catalogRepository.findById(draft.id()).orElseThrow();
        assertThat(live.getStatus()).isEqualTo(CatalogVersionEntity.PUBLISHED);
        assertThat(stillDraft.getStatus()).isEqualTo(CatalogVersionEntity.DRAFT);
        assertThat(stillDraft.getVersion()).isEqualTo(draft.version());
        assertThat(catalogService.current().versionId()).isEqualTo(liveBefore);
        assertThat(classicPrice(catalogService.current().document())).isEqualTo(58);
    }

    @Test
    void invalidDraftPublish_changesNothing() {
        long liveBefore = liveId();
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(62));
        // Corrupt the stored draft behind the service's back (validation runs again at publish).
        CatalogVersionEntity row = catalogRepository.findById(draft.id()).orElseThrow();
        row.setContent(row.getContent().replace("\"price\":62", "\"price\":-1"));
        row = catalogRepository.saveAndFlush(row);

        long version = row.getVersion();
        var ex = catchThrowableOfType(() -> catalogService.publish(
                new CatalogDraftActionRequest(draft.id(), version), ADMIN_A), ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(liveId()).isEqualTo(liveBefore);
        assertThat(catalogService.current().versionId()).isEqualTo(liveBefore);
    }

    @Test
    void catalogPublishedBeforePhotosExisted_getsTheSeedPhotosBackfilledOnStartup() {
        // Simulate a database whose live catalog predates service photos: no imageIds anywhere.
        CatalogVersionEntity live = catalogRepository.findByStatus(CatalogVersionEntity.PUBLISHED).orElseThrow();
        long liveId = live.getId();
        live.setContent(live.getContent().replaceAll("\"imageId\":\"[^\"]*\"", "\"imageId\":null"));
        catalogRepository.saveAndFlush(live);

        CatalogService restarted = new CatalogService(catalogRepository, imageRepository, imageSanitizer, transactionManager);
        restarted.init();

        assertThat(restarted.current().versionId()).as("same live version — content-only fix").isEqualTo(liveId);
        assertThat(restarted.current().document().nailServices())
                .extracting(CatalogDocument.ServiceItem::imageId)
                .containsExactly("seed-express", "seed-classic", "seed-structured", "seed-apres");
        assertThat(catalogRepository.findById(liveId).orElseThrow().getContent()).contains("\"imageId\":\"seed-classic\"");
    }

    @Test
    void restart_servesThePublishedRow_notTheSeed() {
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(66));
        catalogService.publish(new CatalogDraftActionRequest(draft.id(), draft.version()), ADMIN_A);
        long rows = catalogRepository.count();

        // A fresh service against the same database = an app restart.
        CatalogService restarted = new CatalogService(catalogRepository, imageRepository, imageSanitizer, transactionManager);
        restarted.init();

        assertThat(restarted.current().versionId()).isEqualTo(draft.id());
        assertThat(classicPrice(restarted.current().document())).isEqualTo(66);
        assertThat(catalogRepository.count()).as("no re-seed on restart").isEqualTo(rows);
    }

    @Test
    void publishDuringCheckout_rejectsTheOldQuote_andPricesTheReReviewedOneFromTheNewCatalog() {
        long reviewedVersion = catalogService.current().versionId();
        SlotEntity earlierSlot = createFutureSlot(1);
        // The 3-arg overload is the @Transactional entry point the controllers call.
        BookingResponse earlier = bookingService.createBooking(guestBooking(earlierSlot.getId(), "early@test.local")
                .withCatalogVersion(reviewedVersion), null, null);
        assertThat(earlier.totalPrice()).isEqualTo(58);

        // The admin raises Classic from S$58 to S$62 while a customer is mid-checkout.
        AdminCatalogResponse.Draft draft = createDraft(ADMIN_A, withClassicPrice(62));
        catalogService.publish(new CatalogDraftActionRequest(draft.id(), draft.version()), ADMIN_A);
        SlotEntity slot = createFutureSlot(2);

        var ex = catchThrowableOfType(() -> bookingService.createBooking(
                guestBooking(slot.getId(), "late@test.local").withCatalogVersion(reviewedVersion), null, null),
                CodedResponseStatusException.class);
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo(CodedResponseStatusException.CATALOG_VERSION_MISMATCH);

        BookingResponse rebooked = bookingService.createBooking(guestBooking(slot.getId(), "late@test.local")
                .withCatalogVersion(catalogService.current().versionId()), null, null);
        assertThat(rebooked.totalPrice()).isEqualTo(62);
        assertThat(bookingRepository.findById(earlier.id()).orElseThrow().getTotalPrice())
                .as("an existing booking keeps the price it was made at").isEqualTo(58);
    }

    // ─── HTTP surface ─────────────────────────────────────────────────────────────

    @Test
    void publicCatalog_isAnonymous_uncached_andCarriesTheVersion() throws Exception {
        mockMvc.perform(get("/api/catalog"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.version").value(liveId()))
                .andExpect(jsonPath("$.NAIL_SERVICES[1].price").value(58))
                .andExpect(jsonPath("$.REPAIRS[0].priceNote").value("S$3/nail"));
    }

    @Test
    void seedPhotos_areStoredAndServedPublicly_withLongCaching() throws Exception {
        mockMvc.perform(get("/api/catalog/images/seed-classic"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=31536000")));
        mockMvc.perform(get("/api/catalog/images/does-not-exist")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/catalog/images").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError()); // admin-only (and CSRF-protected)
    }

    @Test
    void adminCatalog_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/catalog")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestBookingWithoutACatalogVersion_returnsTheMachineReadableCode() throws Exception {
        SlotEntity slot = createFutureSlot(1);
        String body = """
                {"slotId": %d, "customerName": "Guest", "customerEmail": "guest@test.local",
                 "serviceId": "classic", "nailArtId": "none", "removalId": "none"}
                """.formatted(slot.getId());

        mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CodedResponseStatusException.CATALOG_VERSION_MISMATCH))
                .andExpect(jsonPath("$.message").value("Our prices were just updated — please review your booking."));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────

    private long liveId() {
        return catalogRepository.findByStatus(CatalogVersionEntity.PUBLISHED).orElseThrow().getId();
    }

    private AdminCatalogResponse.Draft createDraft(String admin, CatalogDocument content) {
        return catalogService.saveDraft(new SaveCatalogDraftRequest(null, null, liveId(), content), admin).draft();
    }

    private static CatalogDocument seed() {
        return TestCatalogs.seedDocument();
    }

    private static CatalogDocument withClassicPrice(int price) {
        CatalogDocument seed = seed();
        List<CatalogDocument.ServiceItem> services = new ArrayList<>(seed.nailServices());
        CatalogDocument.ServiceItem classic = services.get(1);
        services.set(1, new CatalogDocument.ServiceItem(classic.id(), classic.label(), classic.name(), classic.desc(),
                price, classic.durationMin(), classic.popular(), classic.hidden(), classic.imageId()));
        return new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());
    }

    private static int classicPrice(CatalogDocument document) {
        return document.nailServices().stream().filter(s -> "classic".equals(s.id())).findFirst().orElseThrow().price();
    }

    private static CatalogVersionEntity row(String status) {
        CatalogVersionEntity row = new CatalogVersionEntity();
        row.setStatus(status);
        row.setContent("{}");
        return row;
    }

    private static CreateBookingRequest guestBooking(Long slotId, String email) {
        return new CreateBookingRequest(slotId, "Guest", email, null, null, null,
                null, null, null, null, null, "classic", null, "none", "none");
    }

    private SlotEntity createFutureSlot(int dayOffset) {
        Instant start = BookingWindow.earliestBookableStartUtc().plus(Duration.ofDays(dayOffset));
        SlotEntity slot = new SlotEntity();
        slot.setTitle("Test slot");
        slot.setStartTime(start);
        slot.setEndTime(start.plus(Duration.ofHours(2)));
        slot.setCapacity(1);
        slot.setBookedCount(0);
        return slotRepository.save(slot);
    }

    private static void assertConflict(Runnable call) {
        var ex = catchThrowableOfType(call::run, ResponseStatusException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
    }

    private List<Integer> runSimultaneously(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int statusOf(Callable<?> call) {
        try {
            call.call();
            return SUCCESS;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } catch (Exception e) {
            throw new AssertionError("unexpected non-ResponseStatusException from the service", e);
        }
    }
}
