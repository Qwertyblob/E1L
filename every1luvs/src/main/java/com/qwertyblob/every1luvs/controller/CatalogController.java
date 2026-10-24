package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.AdminCatalogResponse;
import com.qwertyblob.every1luvs.dto.CatalogDraftActionRequest;
import com.qwertyblob.every1luvs.dto.PublicCatalogResponse;
import com.qwertyblob.every1luvs.dto.SaveCatalogDraftRequest;
import com.qwertyblob.every1luvs.service.CatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // Public: the live catalog the landing page and booking flow render from. no-cache so a
    // browser/proxy always revalidates — a stale copy would show customers outdated prices.
    @GetMapping("/catalog")
    public ResponseEntity<PublicCatalogResponse> catalog() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(catalogService.publicCatalog());
    }

    @GetMapping("/admin/catalog")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCatalogResponse adminCatalog() {
        return catalogService.adminView();
    }

    @PutMapping("/admin/catalog/draft")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCatalogResponse saveDraft(@RequestBody SaveCatalogDraftRequest request, Authentication authentication) {
        return catalogService.saveDraft(request, authentication.getName());
    }

    @PostMapping("/admin/catalog/draft/discard")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCatalogResponse discardDraft(@RequestBody CatalogDraftActionRequest request) {
        return catalogService.discardDraft(request);
    }

    @PostMapping("/admin/catalog/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminCatalogResponse publish(@RequestBody CatalogDraftActionRequest request, Authentication authentication) {
        return catalogService.publish(request, authentication.getName());
    }
}
