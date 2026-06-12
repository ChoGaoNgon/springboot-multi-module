package jp.co.htkk.dto.admin.user.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import jp.co.htkk.dto.common.RESPONSE;
import jp.co.htkk.entity.User;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
public class UserListResponse extends RESPONSE {

    @JsonProperty("data")
    private List<UserResponse.UserData> data;

    public static UserListResponse of(List<User> users) {
        UserListResponse response = new UserListResponse();
        response.setData(users.stream().map(UserResponse.UserData::of).collect(Collectors.toList()));
        return response;
    }
}
