package jp.co.htkk.business.service.impl.admin;

import jp.co.htkk.business.service.AbstractBaseService;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.User;
import jp.co.htkk.framework.exception.type.ServiceException;
import jp.co.htkk.persistence.dao.UserMapper;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class UserServiceImpl extends AbstractBaseService implements UserService {

    private final UserMapper userMapper;

    @Override
    public User createUser(UserDxo dxo) {
        User user = new User();
        user.setUsername(dxo.getUsername());
        user.setEmail(dxo.getEmail());
        // AuditInterceptor fills created_by/at, updated_by/at; useGeneratedKeys sets userId
        userMapper.insertUser(user);
        return user;
    }

    @Override
    public User getUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return user;
    }

    @Override
    public List<User> listUsers() {
        return userMapper.selectAll();
    }
}
