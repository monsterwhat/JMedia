package Models.Xtream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

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
        public long expDate;
        @JsonProperty("is_trial")
        public int isTrial;
        @JsonProperty("active_cons")
        public int activeCons;
        @JsonProperty("created_at")
        public long createdAt;
        @JsonProperty("max_connections")
        public int maxConnections;
        @JsonProperty("allowed_output_formats")
        public java.util.List<String> allowedOutputFormats;
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
        public String process;
    }
}
