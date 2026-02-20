package org.tripsphere.user.service;

import java.util.Optional;
import org.tripsphere.user.v1.User;

public interface UserService {

    void register(String username, String password);

    LoginResult login(String username, String password);

    void changePassword(String username, String oldPassword, String newPassword);

    Optional<User> findByUsername(String username);

    record LoginResult(User user, String token) {}
}
