package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class XtreamLoginResponse {
    @JsonProperty("user_info")
    public UserInfo userInfo;

    @JsonProperty("server_info")
    public ServerInfo serverInfo;

    public static class UserInfo {
        public String username;
        public String password;
        public String message;
        public int auth;
        public String status;
        @JsonProperty("exp_date")
        public String expDate;
        @JsonProperty("is_trial")
        public String isTrial;
        @JsonProperty("active_cons")
        public String activeCons;
        @JsonProperty("created_at")
        public String createdAt;
        @JsonProperty("max_connections")
        public String maxConnections;
        @JsonProperty("allowed_output_formats")
        public List<String> allowedOutputFormats;
    }

    public static class ServerInfo {
        public String url;
        public String port;
        @JsonProperty("https_port")
        public String httpsPort;
        @JsonProperty("server_protocol")
        public String serverProtocol;
        @JsonProperty("rtmp_port")
        public String rtmpPort;
        public String timezone;
        @JsonProperty("timestamp_now")
        public long timestampNow;
        @JsonProperty("time_now")
        public String timeNow;
        public Boolean process;
    }
}
