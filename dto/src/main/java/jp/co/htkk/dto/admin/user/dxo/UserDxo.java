package jp.co.htkk.dto.admin.user.dxo;

import jp.co.htkk.dto.common.DXO;
import jp.co.htkk.dto.common.PRM;
import lombok.Data;

@Data
public class UserDxo extends DXO {

    private String username;
    private String email;

    @Override
    public <T extends PRM> T toPrm() {
        return null;
    }
}
