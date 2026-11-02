package com.gutta.gdb.application;

import com.gutta.gdb.config.ApiException;
import com.gutta.gdb.shared.DatabaseSchemaProvisioner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.gutta.gdb.application.ApplicationDtos.*;

@Service
public class ApplicationService {
    private final ApplicationRepository applications;
    private final DatabaseSchemaProvisioner schemas;
    private final ObjectMapper mapper;

    public ApplicationService(ApplicationRepository applications, DatabaseSchemaProvisioner schemas, ObjectMapper mapper) {
        this.applications = applications; this.schemas = schemas; this.mapper = mapper;
    }

    @Transactional
    public ApplicationResponse create(CreateApplicationRequest request) {
        String code = request.appCode().trim().toUpperCase(Locale.ROOT);
        if (applications.findByCode(code).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_CODE_EXISTS", "Application code already exists.");
        }
        String schema = DatabaseSchemaProvisioner.schemaFor(code);
        Instant now = Instant.now();
        GdbApplicationEntity app = new GdbApplicationEntity(UUID.randomUUID(), code, request.displayName().trim(),
                request.versionMetadata(), schema,
                json(request.permittedDatasets(), "[]", true), json(request.snapshotPolicy(), "{}", false), now);
        schemas.provision(schema);
        return ApplicationResponse.from(applications.save(app));
    }

    @Transactional(readOnly = true)
    public List<ApplicationResponse> list() {
        return applications.findAllByOrderByCode().stream().map(ApplicationResponse::from).toList();
    }

    @Transactional
    public ApplicationResponse setStatus(UUID id, String status) {
        GdbApplicationEntity app = applications.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Application was not found."));
        if ("ACTIVE".equals(status)) app.activate(Instant.now()); else if ("INACTIVE".equals(status)) app.deactivate(Instant.now());
        else throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_APPLICATION_STATUS", "Application status is invalid.");
        return ApplicationResponse.from(app);
    }

    @Transactional(readOnly = true)
    public GdbApplicationEntity requireActive(String code) {
        return applications.findByCode(code.toUpperCase(Locale.ROOT)).filter(GdbApplicationEntity::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "APPLICATION_INACTIVE", "Application is not active."));
    }

    private String json(String value, String fallback, boolean array) {
        if (value == null || value.isBlank()) return fallback;
        try {
            var node = mapper.readTree(value);
            if (array != node.isArray()) throw new IllegalArgumentException("wrong JSON shape");
            return mapper.writeValueAsString(node);
        } catch (JacksonException | IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_APPLICATION_POLICY", "Application policy metadata must be valid JSON.");
        }
    }
}
