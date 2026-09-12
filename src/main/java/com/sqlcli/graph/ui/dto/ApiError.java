package com.sqlcli.graph.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private String code;
    private String message;
    private Map<String, Object> details;

    public ApiError() {}

    public ApiError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static ApiError notFound(String message) {
        return new ApiError("NOT_FOUND", message);
    }

    public static ApiError badRequest(String message) {
        return new ApiError("BAD_REQUEST", message);
    }

    public static ApiError unauthorized(String message) {
        return new ApiError("UNAUTHORIZED", message);
    }

    public static ApiError internal(String message) {
        return new ApiError("INTERNAL_ERROR", message);
    }
}
