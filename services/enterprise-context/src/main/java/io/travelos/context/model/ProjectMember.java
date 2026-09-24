package io.travelos.context.model;

import java.time.Instant;

public record ProjectMember(String projectId, String employeeId, String role, Instant since) {}
