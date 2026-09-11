package io.travelos.workflows.trip;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "travelos.workflow")
public record WorkflowProperties(String principal, Duration approvalTimeout, String paymentToken) {

  public WorkflowProperties {
    principal = principal == null ? "agent/trip-planner/v1" : principal;
    approvalTimeout = approvalTimeout == null ? Duration.ofHours(48) : approvalTimeout;
    paymentToken = paymentToken == null ? "tok_corp_visa_sandbox" : paymentToken;
  }
}
