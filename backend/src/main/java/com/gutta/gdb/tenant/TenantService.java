package com.gutta.gdb.tenant;

import com.gutta.gdb.application.ApplicationRepository;
import com.gutta.gdb.application.GdbApplicationEntity;
import com.gutta.gdb.config.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.gutta.gdb.tenant.TenantDtos.*;

@Service
public class TenantService {
    private final TenantRepository tenants;
    private final ApplicationRepository applications;
    public TenantService(TenantRepository tenants, ApplicationRepository applications) { this.tenants = tenants; this.applications = applications; }

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        GdbApplicationEntity app = applications.findById(request.appId()).filter(GdbApplicationEntity::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Active application was not found."));
        String code = request.tenantCode().trim().toUpperCase(Locale.ROOT);
        if (tenants.findByApplication_IdAndCode(app.getId(), code).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "TENANT_CODE_EXISTS", "Tenant code already exists for this application.");
        Tenant tenant = tenants.save(new Tenant(UUID.randomUUID(), app, code, request.tenantName().trim(), Instant.now()));
        return TenantResponse.from(tenant);
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> list() { return tenants.findAllByOrderByCreatedAtDesc().stream().map(TenantResponse::from).toList(); }

    @Transactional(readOnly = true)
    public Tenant requireActive(UUID appId, UUID tenantId) {
        return tenants.findByIdAndApplication_Id(tenantId, appId).filter(Tenant::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Active tenant was not found."));
    }

    @Transactional(readOnly = true)
    public Tenant require(UUID id) { return tenants.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "Tenant was not found.")); }
}
