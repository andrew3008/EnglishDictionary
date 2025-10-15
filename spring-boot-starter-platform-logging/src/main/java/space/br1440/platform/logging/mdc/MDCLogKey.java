package space.br1440.platform.logging.mdc;

import lombok.Getter;

@Getter
public enum MDCLogKey {

    START_TIME("start_time"),
    END_TIME("end_time"),
    DURATION("duration"),
    SERVICE("service"),
    USERNAME("username"),
    PID("pid"),
    //HTTP,
    REQUEST_ID("request_id"),
    URI("http_url"),
    HTTP_STATUS_CODE("http_status_code"),
    HTTP_METHOD("http_method"),
    HTTP_USER_AGENT("http_user_agent"),
    HTTP_REMOTE_IP("http_remote_ip"),
    //GRPC
    GRPC_METHOD("grpc_method"),
    GRPC_STATUS_CODE("grpc_status_code");


    private final String value;

    MDCLogKey(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

}
