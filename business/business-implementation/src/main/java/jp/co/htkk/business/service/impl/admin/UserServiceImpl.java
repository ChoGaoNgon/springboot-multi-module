package jp.co.htkk.business.service.impl.admin;

import jp.co.htkk.business.service.AbstractBaseService;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.generator.User;
import jp.co.htkk.entity.generator.UserCriteria;
import jp.co.htkk.framework.exception.type.ServiceException;
import jp.co.htkk.persistence.dao.generator.UserMapper;
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
        userMapper.insertSelective(user);
        return user;
    }

    @Override
    public User getUser(Long userId) {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andUserIdEqualTo(userId).andDelFlagEqualTo((short) 0);
        User user = userMapper.selectOneByExample(criteria);
        if (user == null) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return user;
    }

    @Override
    public List<User> listUsers() {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andDelFlagEqualTo((short) 0);
        criteria.setOrderByClause("user_id");
        return userMapper.selectByExample(criteria);
    }
}
