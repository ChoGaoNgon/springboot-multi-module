package jp.co.htkk.dto.admin.user.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.pagehelper.Page;
import jp.co.htkk.dto.common.PageMeta;
import jp.co.htkk.dto.common.RESPONSE;
import jp.co.htkk.entity.generator.User;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
public class UserListResponse extends RESPONSE {

    @JsonProperty("data")
    private List<UserResponse.UserData> data;

    @JsonProperty("page")
    private PageMeta page;

    public static UserListResponse of(Page<User> users) {
        UserListResponse response = new UserListResponse();
        response.setData(users.getResult().stream().map(UserResponse.UserData::of).collect(Collectors.toList()));
        response.setPage(PageMeta.of(users));
        return response;
    }
}
