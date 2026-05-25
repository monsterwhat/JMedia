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
        public String exp_date;
        public String is_trial;
        public String active_cons;
        public String created_at;
        public String max_connections;
        public java.util.List<String> allowed_output_formats;
    }

    public static class ServerInfo {
        public String url;
        public String port;
        public String https_port;
        public String server_protocol;
        public String rtmp_port;
        public String timezone;
        public long timestamp_now;
        public String time_now;
        public String process;
    }
}
