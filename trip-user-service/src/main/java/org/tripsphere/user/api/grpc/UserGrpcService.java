package org.tripsphere.user.api.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.tripsphere.user.exception.NotFoundException;
import org.tripsphere.user.exception.UnauthenticatedException;
import org.tripsphere.user.security.JwtAuthenticationToken;
import org.tripsphere.user.service.UserService;
import org.tripsphere.user.v1.ChangePasswordRequest;
import org.tripsphere.user.v1.ChangePasswordResponse;
import org.tripsphere.user.v1.GetCurrentUserRequest;
import org.tripsphere.user.v1.GetCurrentUserResponse;
import org.tripsphere.user.v1.LoginRequest;
import org.tripsphere.user.v1.LoginResponse;
import org.tripsphere.user.v1.RegisterRequest;
import org.tripsphere.user.v1.RegisterResponse;
import org.tripsphere.user.v1.User;
import org.tripsphere.user.v1.UserServiceGrpc.UserServiceImplBase;

@GrpcService
@RequiredArgsConstructor
public class UserGrpcService extends UserServiceImplBase {

    private final UserService userService;

    @Override
    public void register(
            RegisterRequest request, StreamObserver<RegisterResponse> responseObserver) {
        String username = request.getUsername();
        String password = request.getPassword();

        userService.register(username, password);

        responseObserver.onNext(RegisterResponse.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void login(LoginRequest request, StreamObserver<LoginResponse> responseObserver) {
        String username = request.getUsername();
        String password = request.getPassword();

        UserService.LoginResult result = userService.login(username, password);

        LoginResponse response =
                LoginResponse.newBuilder().setUser(result.user()).setToken(result.token()).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void changePassword(
            ChangePasswordRequest request,
            StreamObserver<ChangePasswordResponse> responseObserver) {
        String username = request.getUsername();
        String oldPassword = request.getOldPassword();
        String newPassword = request.getNewPassword();

        userService.changePassword(username, oldPassword, newPassword);

        responseObserver.onNext(ChangePasswordResponse.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void getCurrentUser(
            GetCurrentUserRequest request,
            StreamObserver<GetCurrentUserResponse> responseObserver) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken)) {
            throw UnauthenticatedException.authenticationRequired();
        }

        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        String username = jwtAuth.getUsername();

        if (username == null || username.trim().isEmpty()) {
            throw UnauthenticatedException.authenticationRequired();
        }

        User user =
                userService
                        .findByUsername(username)
                        .orElseThrow(() -> new NotFoundException("User", username));

        GetCurrentUserResponse response = GetCurrentUserResponse.newBuilder().setUser(user).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
