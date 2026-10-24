package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.AdminCatalogResponse;
import com.qwertyblob.every1luvs.dto.CatalogDocument;
import com.qwertyblob.every1luvs.dto.CatalogDraftActionRequest;
import com.qwertyblob.every1luvs.dto.PublicCatalogResponse;
import com.qwertyblob.every1luvs.dto.SaveCatalogDraftRequest;
import com.qwertyblob.every1luvs.entity.CatalogVersionEntity;
import com.qwertyblob.every1luvs.repository.CatalogVersionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the admin-editable catalog: the live (PUBLISHED) snapshot every price/duration lookup reads,
 * and the single DRAFT the admin edits before publishing it live in one step.
 *
 * <p>Concurrency contract (two admins, or an admin and a checkout):
 * <ul>
 *   <li>Every draft write names the exact revision it applies to — {@code draftId}/{@code version}
 *       for save/discard/publish, {@code basePublishedId} when creating — and is checked under a
 *       row lock, so nobody publishes or discards changes they didn't review (409 otherwise).</li>
 *   <li>Publish is one transaction (supersede the live row, promote the draft); the in-memory
 *       {@link #current()} snapshot is swapped only after commit, and {@link #install} never lets
 *       an older version replace a newer one even if commit callbacks run out of order.</li>
 * </ul>
 */
@Service
public class CatalogService {

    static final String SEED_RESOURCE = "/catalog/seed-services.json";
    static final String SEED_PUBLISHER = "seed";

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CatalogVersionRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final AtomicReference<BookingCatalog> live = new AtomicReference<>();

    public CatalogService(CatalogVersionRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Load the live catalog at startup, seeding it from the bundled classpath file on first boot
     * (an empty table). Seeding is guarded by the one-PUBLISHED partial unique index, so a racing
     * second instance just reads the row the first one inserted.
     */
    @PostConstruct
    void init() {
        CatalogVersionEntity published;
        try {
            published = transactionTemplate.execute(status ->
                    repository.findByStatus(CatalogVersionEntity.PUBLISHED).orElseGet(this::insertSeed));
        } catch (DataIntegrityViolationException e) {
            published = transactionTemplate.execute(status ->
                    repository.findByStatus(CatalogVersionEntity.PUBLISHED).orElseThrow(() ->
                            new IllegalStateException("Catalog seed raced but no PUBLISHED row exists.", e)));
        }
        Objects.requireNonNull(published, "published catalog");
        install(BookingCatalog.of(published.getId(), parse(published)));
    }

    /** The live catalog. Callers take it ONCE per calculation so all lookups share one version. */
    public BookingCatalog current() {
        BookingCatalog catalog = live.get();
        if (catalog == null) {
            throw new IllegalStateException("Catalog not loaded yet.");
        }
        return catalog;
    }

    public PublicCatalogResponse publicCatalog() {
        BookingCatalog catalog = current();
        return PublicCatalogResponse.of(catalog.versionId(), catalog.document());
    }

    @Transactional(readOnly = true)
    public AdminCatalogResponse adminView() {
        CatalogVersionEntity published = repository.findByStatus(CatalogVersionEntity.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException("No PUBLISHED catalog row."));
        CatalogVersionEntity draft = repository.findByStatus(CatalogVersionEntity.DRAFT).orElse(null);
        return view(published, draft);
    }

    @Transactional
    public AdminCatalogResponse saveDraft(SaveCatalogDraftRequest request, String adminEmail) {
        if (request == null || request.content() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The catalog is empty.");
        }
        CatalogVersionEntity draft;
        CatalogVersionEntity published;
        if (request.draftId() == null) {
            if (request.basePublishedId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "basePublishedId is required to start a draft.");
            }
            // Lock the live row so a concurrent publish can't move it between this check and the
            // insert: the new draft is then guaranteed to be based on what the admin edited.
            published = lockPublished();
            if (!published.getId().equals(request.basePublishedId())) {
                throw conflict("The live catalog was updated since you started editing. "
                        + "Load the latest version, then re-apply your changes.");
            }
            if (repository.existsByStatus(CatalogVersionEntity.DRAFT)) {
                throw conflict("Another admin has already started a draft. Load the latest draft to continue.");
            }
            CatalogDocument document = CatalogValidator.validate(request.content(), parse(published));
            draft = new CatalogVersionEntity();
            draft.setStatus(CatalogVersionEntity.DRAFT);
            draft.setBasePublishedId(published.getId());
            draft.setContent(write(document));
            draft.setUpdatedBy(adminEmail);
            try {
                // The one-DRAFT partial unique index is the real guard against a racing create.
                draft = repository.saveAndFlush(draft);
            } catch (DataIntegrityViolationException e) {
                throw conflict("Another admin has already started a draft. Load the latest draft to continue.");
            }
        } else {
            draft = lockDraft(request.draftId(), request.version());
            published = repository.findByStatus(CatalogVersionEntity.PUBLISHED)
                    .orElseThrow(() -> new IllegalStateException("No PUBLISHED catalog row."));
            CatalogDocument document = CatalogValidator.validate(request.content(), parse(published));
            draft.setContent(write(document));
            draft.setUpdatedBy(adminEmail);
            draft = repository.saveAndFlush(draft);
        }
        return view(published, draft);
    }

    @Transactional
    public AdminCatalogResponse discardDraft(CatalogDraftActionRequest request) {
        CatalogVersionEntity draft = lockDraft(request == null ? null : request.draftId(),
                request == null ? null : request.version());
        repository.delete(draft);
        repository.flush();
        CatalogVersionEntity published = repository.findByStatus(CatalogVersionEntity.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException("No PUBLISHED catalog row."));
        return view(published, null);
    }

    /**
     * Make the reviewed draft live. Lock order is DRAFT then PUBLISHED (draft creation takes only
     * PUBLISHED and never waits on DRAFT), so these can't deadlock. Any failure rolls back both
     * status changes and leaves {@link #current()} untouched: the swap is registered for after commit.
     */
    @Transactional
    public AdminCatalogResponse publish(CatalogDraftActionRequest request, String adminEmail) {
        CatalogVersionEntity draft = lockDraft(request == null ? null : request.draftId(),
                request == null ? null : request.version());
        CatalogVersionEntity published = lockPublished();
        if (!published.getId().equals(draft.getBasePublishedId())) {
            throw conflict("The live catalog was updated since this draft was started. "
                    + "Discard this draft and start again from the latest version.");
        }
        CatalogDocument document = CatalogValidator.validate(parse(draft), parse(published));

        published.setStatus(CatalogVersionEntity.SUPERSEDED);
        // Flush now so the one-PUBLISHED partial unique index is free before the draft takes it.
        repository.saveAndFlush(published);

        draft.setStatus(CatalogVersionEntity.PUBLISHED);
        draft.setContent(write(document));
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(adminEmail);
        draft = repository.saveAndFlush(draft);

        BookingCatalog next = BookingCatalog.of(draft.getId(), document);
        afterCommit(() -> install(next));
        log.info("Catalog version {} published by {} (superseding {}).", draft.getId(), adminEmail, published.getId());
        return view(draft, null);
    }

    /**
     * Install a newly published snapshot unless a newer one is already live. Published ids come from
     * a BIGSERIAL, so a later publish always has a larger id — a commit callback that runs late can
     * therefore never put an older catalog back.
     */
    void install(BookingCatalog next) {
        live.accumulateAndGet(next, (installed, candidate) ->
                installed == null || candidate.versionId() > installed.versionId() ? candidate : installed);
    }

    /** The bundled first-boot catalog (also used by tests as a known-good document). */
    public static CatalogDocument loadSeedDocument() {
        try (InputStream in = CatalogService.class.getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Catalog seed resource not found: " + SEED_RESOURCE);
            }
            return JSON.readValue(in, CatalogDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read catalog seed " + SEED_RESOURCE, e);
        }
    }

    private CatalogVersionEntity insertSeed() {
        CatalogDocument document = CatalogValidator.validate(loadSeedDocument(), null);
        CatalogVersionEntity seed = new CatalogVersionEntity();
        seed.setStatus(CatalogVersionEntity.PUBLISHED);
        seed.setContent(write(document));
        seed.setPublishedAt(Instant.now());
        seed.setPublishedBy(SEED_PUBLISHER);
        seed.setUpdatedBy(SEED_PUBLISHER);
        CatalogVersionEntity saved = repository.saveAndFlush(seed);
        log.info("Seeded catalog version {} from {}.", saved.getId(), SEED_RESOURCE);
        return saved;
    }

    private CatalogVersionEntity lockPublished() {
        return repository.findByStatusForUpdate(CatalogVersionEntity.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException("No PUBLISHED catalog row."));
    }

    // The draft the admin reviewed, locked, or 409 if it has since been published/discarded/edited.
    private CatalogVersionEntity lockDraft(Long draftId, Long version) {
        if (draftId == null || version == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "draftId and version are required.");
        }
        CatalogVersionEntity draft = repository.findByStatusForUpdate(CatalogVersionEntity.DRAFT)
                .filter(row -> row.getId().equals(draftId))
                .orElseThrow(() -> conflict("This draft was already published or discarded. "
                        + "Load the latest version."));
        if (!version.equals(draft.getVersion())) {
            throw conflict("This draft was changed by someone else since you loaded it. "
                    + "Load the latest draft, then re-apply your changes.");
        }
        return draft;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static AdminCatalogResponse view(CatalogVersionEntity published, CatalogVersionEntity draft) {
        AdminCatalogResponse.Published live = new AdminCatalogResponse.Published(
                published.getId(), parse(published), published.getPublishedAt(), published.getPublishedBy());
        AdminCatalogResponse.Draft working = draft == null ? null : new AdminCatalogResponse.Draft(
                draft.getId(), draft.getVersion(), draft.getBasePublishedId(), parse(draft),
                draft.getUpdatedAt(), draft.getUpdatedBy());
        return new AdminCatalogResponse(live, working);
    }

    private static CatalogDocument parse(CatalogVersionEntity row) {
        return JSON.readValue(row.getContent(), CatalogDocument.class);
    }

    private static String write(CatalogDocument document) {
        return JSON.writeValueAsString(document);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
