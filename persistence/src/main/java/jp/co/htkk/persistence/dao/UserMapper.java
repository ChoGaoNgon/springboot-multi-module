package jp.co.htkk.persistence.dao;

import jp.co.htkk.entity.User;

import java.util.List;

public interface UserMapper {

    int insertUser(User user);

    User selectById(Long userId);

    List<User> selectAll();
}
