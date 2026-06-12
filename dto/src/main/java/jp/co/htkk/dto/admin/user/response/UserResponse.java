package jp.co.htkk.dto.admin.user.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jp.co.htkk.dto.common.RESPONSE;
import jp.co.htkk.entity.User;
import lombok.Data;

@Data
public class UserResponse extends RESPONSE {

    @JsonProperty("data")
    private UserData data;

    public static UserResponse of(User user) {
        UserResponse response = new UserResponse();
        response.setData(UserData.of(user));
        return response;
    }

    @Schema(name = "UserResponse.Data")
    @Data
    public static class UserData {
        private Long userId;
        private String username;
        private String email;

        public static UserData of(User user) {
            UserData d = new UserData();
            d.setUserId(user.getUserId());
            d.setUsername(user.getUsername());
            d.setEmail(user.getEmail());
            return d;
        }
    }
}
