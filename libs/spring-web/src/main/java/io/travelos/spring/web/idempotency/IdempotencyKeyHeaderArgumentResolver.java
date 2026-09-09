package io.travelos.spring.web.idempotency;

import io.travelos.spring.web.error.ApiException;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public final class IdempotencyKeyHeaderArgumentResolver implements HandlerMethodArgumentResolver {

  public static final String HEADER = "Idempotency-Key";
  private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_:.-]{1,128}$");

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(IdempotencyKeyHeader.class)
        && String.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String value = webRequest.getHeader(HEADER);
    if (value == null || value.isBlank()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_REQUIRED",
          "the " + HEADER + " header is required on this request");
    }
    if (!FORMAT.matcher(value).matches()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_MALFORMED",
          HEADER + " must be 1-128 characters of [A-Za-z0-9_:.-]");
    }
    return value;
  }
}
