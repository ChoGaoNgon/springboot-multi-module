package jp.co.htkk.business.service.admin;

import com.github.pagehelper.Page;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.generator.User;

public interface UserService {

    User createUser(UserDxo dxo);

    User getUser(Long userId);

    Page<User> listUsers(int pageNum, int pageSize);
}
