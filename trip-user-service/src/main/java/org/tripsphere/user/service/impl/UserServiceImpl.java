package org.tripsphere.user.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.tripsphere.user.exception.AlreadyExistsException;
import org.tripsphere.user.exception.InvalidArgumentException;
import org.tripsphere.user.exception.NotFoundException;
import org.tripsphere.user.exception.UnauthenticatedException;
import org.tripsphere.user.mapper.UserMapper;
import org.tripsphere.user.model.Role;
import org.tripsphere.user.model.UserEntity;
import org.tripsphere.user.repository.UserRepository;
import org.tripsphere.user.service.UserService;
import org.tripsphere.user.util.JwtUtil;
import org.tripsphere.user.v1.User;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    // Username pattern: allows letters, numbers, and underscores
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^\\w+$");

    // Password pattern: at least 6 characters, only letters and numbers
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{6,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper = UserMapper.INSTANCE;

    @Override
    public void register(String username, String password) {
        log.debug("Registering user: {}", username);

        validateUsername(username);
        validatePassword(password);

        if (userRepository.existsByUsername(username)) {
            log.warn("Registration failed: username already exists - {}", username);
            throw new AlreadyExistsException("Username", username);
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.getRoles().add(Role.USER);

        userRepository.save(user);
        log.info("User registered successfully - username: {}, userId: {}", username, user.getId());
    }

    @Override
    public LoginResult login(String username, String password) {
        log.debug("User login attempt: {}", username);

        validateUsername(username);
        validatePasswordNotEmpty(password);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
        } catch (Exception e) {
            log.warn("Login failed: invalid credentials - username: {}", username);
            throw UnauthenticatedException.invalidCredentials();
        }

        UserEntity userEntity =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new NotFoundException("User", username));

        List<String> rolesList = userEntity.getRoles().stream().map(Role::name).toList();
        String token = jwtUtil.generateToken(username, rolesList);

        User user = userMapper.toProto(userEntity);
        log.info(
                "User login successful - username: {}, userId: {}, roles: {}",
                username,
                userEntity.getId(),
                rolesList);

        return new LoginResult(user, token);
    }

    @Override
    public void changePassword(String username, String oldPassword, String newPassword) {
        log.debug("Changing password for user: {}", username);

        validateUsernameNotEmpty(username);
        validatePasswordNotEmpty(oldPassword, "Old password");
        validatePassword(newPassword, "New password");

        UserEntity user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new NotFoundException("User", username));

        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("Change password failed: invalid old password - username: {}", username);
            throw new UnauthenticatedException("Invalid old password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info(
                "Password changed successfully - username: {}, userId: {}", username, user.getId());
    }

    @Override
    public Optional<User> findByUsername(String username) {
        log.debug("Finding user by username: {}", username);
        return userRepository.findByUsername(username).map(userMapper::toProto);
    }

    private void validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw InvalidArgumentException.empty("Username");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw InvalidArgumentException.invalid(
                    "Username", "can only contain letters, numbers, and underscores");
        }
    }

    private void validateUsernameNotEmpty(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw InvalidArgumentException.empty("Username");
        }
    }

    private void validatePassword(String password) {
        validatePassword(password, "Password");
    }

    private void validatePassword(String password, String fieldName) {
        if (password == null || password.trim().isEmpty()) {
            throw InvalidArgumentException.empty(fieldName);
        }
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw InvalidArgumentException.invalid(
                    fieldName,
                    "must be at least 6 characters and can only contain letters and numbers");
        }
    }

    private void validatePasswordNotEmpty(String password) {
        validatePasswordNotEmpty(password, "Password");
    }

    private void validatePasswordNotEmpty(String password, String fieldName) {
        if (password == null || password.trim().isEmpty()) {
            throw InvalidArgumentException.empty(fieldName);
        }
    }
}
