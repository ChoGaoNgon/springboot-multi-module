package jp.co.htkk.dto.admin.user.dxo;

import jp.co.htkk.dto.common.DXO;
import jp.co.htkk.dto.common.PRM;
import jp.co.htkk.entity.generator.User;
import lombok.Data;

@Data
public class UserDxo extends DXO {

    private String username;
    private String email;

    public User convertToUser() {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }

    @Override
    public <T extends PRM> T toPrm() {
        return null;
    }
}
