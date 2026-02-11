package org.tripsphere.user.service;

import java.util.Optional;
import org.tripsphere.user.v1.User;

/** Service interface for user management operations. */
public interface UserService {

    /**
     * Register a new user.
     *
     * @param username the username
     * @param password the raw password
     */
    void register(String username, String password);

    /**
     * Authenticate user and generate JWT token.
     *
     * @param username the username
     * @param password the raw password
     * @return login result containing user info and token
     */
    LoginResult login(String username, String password);

    /**
     * Change user password.
     *
     * @param username the username
     * @param oldPassword the old password
     * @param newPassword the new password
     */
    void changePassword(String username, String oldPassword, String newPassword);

    /**
     * Find user by username.
     *
     * @param username the username
     * @return the user if found
     */
    Optional<User> findByUsername(String username);

    /** Result object for login operation. */
    record LoginResult(User user, String token) {}
}
