package API;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private T data;
    private String error;

    private ApiResponse(T data, String error) {
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<T> error(String error) {
        return new ApiResponse<>(null, error);
    }

    public T getData() {
        return data;
    }

    public String getError() {
        return error;
    }
}
