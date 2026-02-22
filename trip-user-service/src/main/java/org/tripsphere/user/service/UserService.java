package org.tripsphere.user.service;

import java.util.Optional;
import org.tripsphere.user.v1.User;

public interface UserService {

    void signUp(String name, String email, String password);

    SignInResult signIn(String email, String password);

    Optional<User> findByEmail(String email);

    record SignInResult(User user, String token) {}
}
