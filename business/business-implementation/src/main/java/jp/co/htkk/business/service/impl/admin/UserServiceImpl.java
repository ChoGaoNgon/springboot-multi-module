package jp.co.htkk.business.service.impl.admin;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jp.co.htkk.business.service.AbstractBaseService;
import jp.co.htkk.business.service.admin.UserService;
import jp.co.htkk.dto.admin.user.dxo.UserDxo;
import jp.co.htkk.entity.generator.User;
import jp.co.htkk.entity.generator.UserCriteria;
import jp.co.htkk.framework.enums.EDeleteFlag;
import jp.co.htkk.framework.exception.type.ServiceException;
import jp.co.htkk.persistence.dao.generator.UserMapper;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class UserServiceImpl extends AbstractBaseService implements UserService {

    private final UserMapper userMapper;

    @Override
    public User createUser(UserDxo dxo) {
        User user = dxo.convertToUser();
        userMapper.insertSelective(user);
        return user;
    }

    @Override
    public User getUser(Long userId) {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andUserIdEqualTo(userId).andDelFlagEqualTo(EDeleteFlag.NOT_DELETED.getCode());
        User user = userMapper.selectOneByExample(criteria);
        if (user == null) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return user;
    }

    @Override
    public Page<User> listUsers(int pageNum, int pageSize) {
        UserCriteria criteria = new UserCriteria();
        criteria.createCriteria().andDelFlagEqualTo(EDeleteFlag.NOT_DELETED.getCode());
        criteria.setOrderByClause(User.Column.userId.asc());
        return PageHelper.startPage(pageNum, pageSize)
                .doSelectPage(() -> userMapper.selectByExample(criteria));
    }
}
