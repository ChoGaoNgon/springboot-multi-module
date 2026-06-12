package jp.co.htkk.dto.admin.user.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.dto.common.REQUEST;
import jp.co.htkk.framework.validation.annotation.RequiredNotBlank;
import lombok.Data;

import java.lang.reflect.InvocationTargetException;

@Data
public class UserCreateRequest extends REQUEST {

    @Schema(description = "User name", example = "taro")
    @RequiredNotBlank
    private String username;

    @Schema(description = "Email", example = "taro@example.com")
    @RequiredNotBlank
    private String email;

    @Override
    public UserDxo toDxo() throws IllegalAccessException, InstantiationException, InvocationTargetException {
        UserDxo dxo = new UserDxo();
        dxo.setUsername(this.username);
        dxo.setEmail(this.email);
        return dxo;
    }
}
