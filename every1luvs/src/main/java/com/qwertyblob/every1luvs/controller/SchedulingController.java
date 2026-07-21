package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.SchedulingConflictResponse;
import com.qwertyblob.every1luvs.service.SchedulingAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only, read-only view of scheduling-invariant conflicts (the §8 pre-deployment audit). Under
 * {@code /api/admin/**}, which requires ROLE_ADMIN. Never mutates anything — operators use it to
 * find and resolve pre-existing over-capacity schedules before trusting the invariant.
 */
@RestController
@RequestMapping("/api/admin/scheduling")
public class SchedulingController {

    private final SchedulingAuditService schedulingAuditService;

    public SchedulingController(SchedulingAuditService schedulingAuditService) {
        this.schedulingAuditService = schedulingAuditService;
    }

    @GetMapping("/conflicts")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SchedulingConflictResponse> conflicts() {
        return schedulingAuditService.findConflicts();
    }
}
