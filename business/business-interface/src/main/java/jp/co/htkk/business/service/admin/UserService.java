package jp.co.htkk.business.service.admin;

import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.User;

import java.util.List;

public interface UserService {

    User createUser(UserDxo dxo);

    User getUser(Long userId);

    List<User> listUsers();
}
